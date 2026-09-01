package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.rebasedBy

/**
 * 문서 하나의 평평한 블록 목록을 [blocksIn]에 이어 [rebasedBy]를 적용하는 방식과 동일하게 섹션별로
 * 나누되, 모든 블록을 섹션마다 다시 훑는 대신 두 목록을 한 번에 훑는다.
 *
 * 책을 저장할 때는 각 섹션의 블록을 섹션 상대 좌표로 저장하므로, 호출자는 예전에
 * `sections.map { }` 안에서
 * `document.blocks.blocksIn(section.range.start, section.range.end).rebasedBy(section.range.start)`를
 * 실행했었다([DocumentRepositoryImpl.persistParsedDocument] 참고). 이는 O(S * B)다: 책의 모든
 * 블록 하나하나가 모든 섹션에 대해 테스트되며, 수만 개의 블록과 수백 개의 섹션을 가진 350만 자
 * 짜리 책은 임포트 스레드에서 그 곱셈 비용을 그대로 치른다. 실제 책이 가진 형태 — [TextPageLayoutEngine]이
 * 보장하는, 오름차순이며 겹치지 않는 섹션 분할 — 에 대해서는 이 헬퍼가 O((S + B) log B)다: 블록을
 * 한 번 시작 위치로 정렬한 다음, 전진 커서와 섹션 경계를 넘나드는 드문 블록을 위한 작은 이월
 * 목록을 함께 사용해 섹션과 블록을 함께 순회하므로, 각 블록은 실제로 겹치는 섹션들에만 딱 한 번씩
 * 배출되고 도달할 수 없는 섹션에 대해 다시 테스트되는 일이 없다.
 *
 * 멤버십 규칙은 [blocksIn]과 정확히 동일하게 유지되어, 리더에게 보이는 것이 하나도 달라지지 않는다:
 * - 폭이 0이 아닌 블록(`range.start != range.end`)은, 자신의 반개구간 범위가 겹치는 모든 섹션의
 *   반개구간 `[start, end)`에 속한다 — `block.start < section.end && block.end > section.start`.
 *   따라서 섹션 경계를 넘어가는 블록은 하나의 섹션이 아니라 그것이 걸치는 모든 섹션에 배출된다.
 * - 폭이 0인 블록(`range.start == range.end`)은 그 점이 섹션의 반개구간 범위 안에 있을 때
 *   (`section.start <= block.start < section.end`) 그 섹션에 속하며, [blocksIn]이 함께 허용하는
 *   퇴화 사례 하나가 더 있다: 빈 섹션(`section.start == section.end`)이면서 그 점이 그 공유된
 *   오프셋과 같은 경우.
 *
 * 반환되는 각 섹션 목록 안에서 블록들은 [blocks]에서의 원래 순서를 유지하며, 반환되는 목록들 자체도
 * [sections]와 같은 순서다; 두 순서 모두 예전의 섹션별 `blocksIn`/`map`이 만들던 것과 일치한다. 각
 * 블록은 자신이 속한 섹션의 시작 위치를 기준으로 [rebasedBy]를 통해 섹션 상대 좌표로 복사되므로,
 * 결과는 저장되는 섹션 상대 형태를 그대로 대체할 수 있다.
 *
 * 정확성은 결코 빠른 경로의 가정에 의존하지 않는다. [sections]가 [isAscendingDisjointPartition]이
 * 확인하는 오름차순의, 겹치지 않는, 비어 있지 않은 분할이 아닐 때마다 — 순서가 어긋나거나, 겹치거나,
 * 비어 있는 섹션이 하나라도 끼어들었을 때 — 이 함수는 정확히 예전의 섹션별
 * `blocks.blocksIn(...).rebasedBy(...)`로 폴백하므로, 그 벗어난 경우에만 O(S * B) 스캔 비용을
 * 치를 뿐 [blocksIn]과 어떤 입력에 대해서도 결과는 동일하다. 전진 커서 스윕은 그 단조 커서 불변식이
 * 성립함이 알려진 경우에만 사용되므로, 섹션 순서 역전으로 커서 뒤에 남겨졌을 블록을 신경 쓸 필요가
 * 결코 없다.
 *
 * @param sections 블록을 나눠 담을 섹션 범위들. 결과가 따라야 할 순서 그대로.
 * @param blocks 문서의 평평한 블록 목록. 각 섹션 목록이 유지해야 할 문서 순서 그대로.
 * @return [sections]의 각 항목에 대응하는 목록 하나씩, [sections] 순서대로. 각각은 [blocks] 순서로
 *   유지된, 그 섹션과 겹치는 블록들을 담으며, 이미 그 섹션의 시작 위치를 기준으로 섹션 상대 좌표로
 *   재기준화되어 있다.
 */
internal fun distributeBlocksIntoSections(
    sections: List<TextRange>,
    blocks: List<ReaderBlock>,
): List<List<ReaderBlock>> {
    if (sections.isEmpty()) return emptyList()
    if (blocks.isEmpty()) return List(sections.size) { emptyList() }

    if (!sections.isAscendingDisjointPartition()) {
        return sections.map { section ->
            blocks.blocksIn(section.start, section.end).rebasedBy(section.start)
        }
    }

    val blockOrder = blocks.indices.sortedWith(
        compareBy({ blocks[it].range.start }, { it }),
    )

    val gathered = List(sections.size) { mutableListOf<Int>() }
    var blockCursor = 0
    var carryOver = mutableListOf<Int>()

    for (sectionIndex in sections.indices) {
        val section = sections[sectionIndex]
        val target = gathered[sectionIndex]

        val nextCarryOver = mutableListOf<Int>()
        for (carriedBlockIndex in carryOver) {
            if (blockMatchesSection(blocks[carriedBlockIndex], section)) target += carriedBlockIndex
            if (blocks[carriedBlockIndex].range.end > section.end) nextCarryOver += carriedBlockIndex
        }

        while (blockCursor < blockOrder.size && blocks[blockOrder[blockCursor]].range.start < section.end) {
            val blockIndex = blockOrder[blockCursor]
            val block = blocks[blockIndex]
            if (blockMatchesSection(block, section)) target += blockIndex
            if (block.range.end > section.end) nextCarryOver += blockIndex
            blockCursor += 1
        }

        carryOver = nextCarryOver
    }

    return sections.indices.map { sectionIndex ->
        val sectionStart = sections[sectionIndex].start
        gathered[sectionIndex]
            .sorted()
            .map { blockIndex -> blocks[blockIndex].rebasedBy(sectionStart) }
    }
}

/**
 * 이 섹션 범위들이 [distributeBlocksIntoSections]의 빠른 스윕이 의존하는, 오름차순의 겹치지 않는
 * 분할인지 여부: 시작 위치 기준으로 엄격히 오름차순이고, 각 범위가 비어 있지 않으며(`start < end`),
 * 어떤 범위도 이전 범위가 끝나기 전에 시작하지 않는다. 이는 정확히 [TextPageLayoutEngine]이
 * 보장하는 형태다 — 페이지는 결코 두 섹션에 걸치지 않으므로, 섹션들은 문서를 순서대로 타일링한다 —
 * 그리고 이것이 성립하지 않는 순간(순서가 어긋나거나, 겹치거나, 비어 있는 섹션이 끼어든 순간),
 * 호출자는 섹션별 [blocksIn]으로 폴백하므로 정확성은 결코 이 가정에 의존하지 않는다.
 *
 * @receiver 나눠 담길 순서 그대로의, 검사할 섹션 범위들.
 * @return 스윕의 단조 커서 가정이 모두 성립할 때만 true.
 */
private fun List<TextRange>.isAscendingDisjointPartition(): Boolean {
    for (index in indices) {
        val current = this[index]
        if (current.start >= current.end) return false
        if (index > 0 && current.start < this[index - 1].end) return false
    }
    return true
}

/**
 * [blocksIn]의 정확한 반개구간, 폭 0 인식 규칙 아래에서 [block]이 [section]에 속하는지 여부.
 *
 * [distributeBlocksIntoSections]의 스윕과 어떤 동등성 테스트든 각자 경계 산술을 반복 서술하는 대신
 * [blocksIn]과 하나의 멤버십 정의를 공유하도록 함수 하나로 유지한다.
 *
 * @param block 절대 문서 오프셋 기준의 후보 블록.
 * @param section 멤버십을 테스트할 섹션 범위.
 * @return `listOf(block).blocksIn(section.start, section.end)`가 [block]을 유지할 때와 정확히
 *   같으면 true.
 */
private fun blockMatchesSection(block: ReaderBlock, section: TextRange): Boolean =
    if (block.range.start == block.range.end) {
        block.range.start in section.start until section.end ||
            (block.range.start == section.end && section.end == section.start)
    } else {
        block.range.start < section.end && block.range.end > section.start
    }

/**
 * 이미 디코딩된 페이지 시작 배열을, 새로 측정된 섹션별 배열들과 함께 하나의 `LongArray`로, 순서대로,
 * 도중에 오프셋을 `List<Long>`로 박싱하는 일 없이 합친다.
 *
 * [DocumentRepositoryImpl.appendMeasuredPageStarts]는 예전에 저장된 레이아웃을 확장할 때 기존
 * 오프셋과 새 섹션들의 오프셋을 모두 `List<Long>`로 바꾼 뒤 그 목록들을 이어붙이고
 * `toLongArray()`를 호출했다 — 양 끝 모두 `Long`인 데이터에 대해 세 번의 박싱 패스를 거친 것이다.
 * 큰 책을 배치 단위로 확장할 때, 그 박싱 비용은 임포트 스레드에서 배치마다 치러졌다. 이 함수는
 * 최종 길이를 한 번만 합산하고, 목적지 배열을 한 번만 할당하고, 각 소스 배열을 그 자리에
 * `copyInto`하므로, 유일한 할당은 코덱이 곧 인코딩할 그 배열뿐이다.
 *
 * 조각들은 주어진 순서대로 복사된다: [existing]이 먼저, 그다음 [appended]의 각 배열이 목록 순서대로.
 * 읽기 순서는 예전과 마찬가지로 온전히 호출자의 책임이다 — 이 헬퍼는 이어붙일 뿐 정렬하지 않는다 —
 * 따라서 호출자는 섹션들의 배열을 이미 읽기 순서로 넘겨야 하며, 이는
 * [DocumentRepositoryImpl.appendMeasuredPageStarts]가 섹션 순서로 측정함으로써 하는 일이다.
 *
 * @param existing 레이아웃에 이미 저장되어 있던 페이지 시작 위치들. 그 blob에서 디코딩된 것.
 * @param appended 새로 임포트된 각 섹션의 측정된 페이지 시작 위치들. 레이아웃을 확장하는 읽기
 *   순서대로.
 * @return [existing] 다음에 [appended]의 모든 배열이 순서대로 이어붙은 배열 하나.
 */
internal fun concatPageStarts(existing: LongArray, appended: List<LongArray>): LongArray {
    val total = existing.size + appended.sumOf { it.size }
    val combined = LongArray(total)
    existing.copyInto(combined, destinationOffset = 0)
    var writeOffset = existing.size
    for (array in appended) {
        array.copyInto(combined, destinationOffset = writeOffset)
        writeOffset += array.size
    }
    return combined
}
