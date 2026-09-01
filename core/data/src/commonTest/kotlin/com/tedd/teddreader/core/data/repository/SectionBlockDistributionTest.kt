package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.rebasedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [distributeBlocksIntoSections]를 구역별 `blocksIn`/`rebasedBy` 방식(대체 이전)으로부터 보호한다.
 * 각 케이스가 비교하는 기준 구현은 이전 [DocumentRepositoryImpl.persistParsedDocument]가 실행하던
 * 정확한 표현식이므로, 경계 교차·영폭·빈 입력·순서 동작에서 어떤 차이가 생기면 조용히 저장된
 * 섹션의 내용을 바꾸는 대신 이 테스트에서 실패한다.
 */
class SectionBlockDistributionTest {
    /**
     * 스윕이 블록 단위로 일치해야 하는 기준: 리팩터 이전 표현식을 섹션마다 실행한 것이다.
     * 헬퍼의 회귀가 비교 기준도 조용히 바꾸는 일이 없도록, 프로덕션 코드와 공유하지 않고 여기에 둔다.
     *
     * @param sections 분배할 섹션 범위 목록.
     * @param blocks 문서의 평탄한 블록 목록.
     * @return 이전 코드가 구축하던 방식 그대로, 각 섹션의 겹치는 블록을 섹션 상대 좌표로 리베이스한 결과.
     */
    private fun referenceDistribution(
        sections: List<TextRange>,
        blocks: List<ReaderBlock>,
    ): List<List<ReaderBlock>> = sections.map { section ->
        blocks.blocksIn(section.start, section.end).rebasedBy(section.start)
    }

    /**
     * `[start, end)` 범위의 단락 블록이다. 실제 텍스트 오프셋을 갖는 가장 단순한 종류이므로,
     * 케이스는 테스트하려는 오프셋만 지정하면 된다.
     *
     * @param start 블록의 절대 시작 오프셋.
     * @param end 블록의 절대 끝 오프셋; [start]와 같으면 영폭(zero-width) 블록이 된다.
     * @return 단락 블록.
     */
    private fun block(start: Long, end: Long): ReaderBlock =
        ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(start, end))

    /**
     * 빈 섹션 목록은 빈 결과를, 빈 블록은 섹션마다 빈 목록 하나를 반환함을 확인한다 —
     * 스윕이 정렬 전에 단락 처리하는 두 가지 퇴화 입력이다.
     */
    @Test
    fun emptyInputsMatchReference() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 20))

        assertEquals(emptyList(), distributeBlocksIntoSections(emptyList(), listOf(block(0, 5))))
        assertEquals(
            referenceDistribution(sections, emptyList()),
            distributeBlocksIntoSections(sections, emptyList()),
        )
        assertEquals(List(2) { emptyList<ReaderBlock>() }, distributeBlocksIntoSections(sections, emptyList()))
    }

    /**
     * 한 섹션 안에 완전히 속하는 블록은 해당 섹션에만, 섹션 시작 기준으로 리베이스되어
     * 배치됨을 확인하며, 일반적인 비교차 케이스에서 기준과 일치한다.
     */
    @Test
    fun containedBlocksMatchReference() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 20), TextRange(20, 30))
        val blocks = listOf(block(0, 5), block(6, 10), block(12, 18), block(20, 25))

        assertEquals(
            referenceDistribution(sections, blocks),
            distributeBlocksIntoSections(sections, blocks),
        )
    }

    /**
     * 섹션 경계를 걸치는 영폭이 아닌 블록이 시작 섹션에만 배치되지 않고
     * 겹치는 모든 섹션에 배치됨을 확인한다 — [blocksIn]의 반개방 구간 겹침 규칙이 적용된다.
     */
    @Test
    fun boundaryCrossingBlockLandsInEveryOverlappingSection() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 20), TextRange(20, 30))
        val blocks = listOf(block(5, 25))

        val result = distributeBlocksIntoSections(sections, blocks)

        assertEquals(referenceDistribution(sections, blocks), result)
        assertTrue(result[0].isNotEmpty(), "crossing block must reach the first section")
        assertTrue(result[1].isNotEmpty(), "crossing block must reach the middle section")
        assertTrue(result[2].isNotEmpty(), "crossing block must reach the last section")
    }

    /**
     * 영폭 블록이 [blocksIn]의 점 규칙을 따름을 확인한다: 점이 섹션의 반개방 범위 안에 있으면
     * 유지되고, 점이 공유 오프셋과 같은 퇴화된 빈 섹션에서도 유지되며, 일반 섹션과 빈 섹션
     * 모두에서 기준과 정확히 일치한다.
     */
    @Test
    fun zeroWidthBlocksMatchReference() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 10), TextRange(10, 20))
        val blocks = listOf(block(0, 0), block(10, 10), block(15, 15), block(20, 20))

        assertEquals(
            referenceDistribution(sections, blocks),
            distributeBlocksIntoSections(sections, blocks),
        )
    }

    /**
     * 오름차순 파티션을 이루지 않는 입력에서 두 가지 출력 순서를 모두 확인하여,
     * 섹션별 [blocksIn] 폴백이 실행되게 한다: 블록은 각 섹션 목록 안에서 문서 순서를 유지하고,
     * 섹션 목록은 섹션이 오름차순이 아니더라도 [sections]의 입력 순서를 유지한다.
     * 기준과의 일치를 확인함으로써 비정상 폴백이 두 순서를 정확히 보존하는지 보호한다.
     */
    @Test
    fun preservesBlockAndSectionOrderUnderUnsortedInput() {
        val sections = listOf(TextRange(20, 30), TextRange(0, 10), TextRange(10, 20))
        val blocks = listOf(block(22, 24), block(2, 4), block(12, 14), block(20, 21), block(0, 1))

        assertEquals(
            referenceDistribution(sections, blocks),
            distributeBlocksIntoSections(sections, blocks),
        )
    }

    /**
     * 일반 파티션 경계 위에 정확히 놓인 영폭 블록이 [blocksIn]의 반개방 규칙에 따라
     * 앞 섹션이 아닌 뒤 섹션에만 속함을 확인한다. [zeroWidthBlocksMatchReference]와 달리,
     * 이 입력은 오름차순 비어있지 않은 파티션 형태를 유지하므로 최적화된 스윕 자체를 실행한다.
     */
    @Test
    fun zeroWidthBlockAtPartitionBoundaryMatchesReferenceOnFastPath() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 20))
        val blocks = listOf(block(10, 10))

        val result = distributeBlocksIntoSections(sections, blocks)

        assertEquals(referenceDistribution(sections, blocks), result)
        assertTrue(result[0].isEmpty(), "boundary point must not belong to the preceding half-open section")
        assertTrue(result[1].isNotEmpty(), "boundary point must belong to the following section")
    }

    /**
     * 스윕이 전체 블록 목록을 섹션마다 다시 읽지 않음을 확인한다. [CountingBlockList]가
     * 각 블록 인덱스의 읽힌 횟수를 기록하며, 섹션이 많고 블록이 적을 때 이전 섹션별 `blocksIn`은
     * 모든 블록을 섹션마다 한 번씩 읽었으므로 (`reads >= sections * blocks`), 가장 바쁜 블록의
     * 읽기 횟수가 섹션 수보다 훨씬 적다는 단언이 O(S * B) 재탐색이 돌아올 경우 정확히 실패한다.
     */
    @Test
    fun doesNotRescanEveryBlockPerSection() {
        val sectionCount = 200
        val sections = (0 until sectionCount).map { TextRange(it * 10L, it * 10L + 10L) }
        val rawBlocks = listOf(block(5, 6), block(1005, 1006), block(1990, 1991))
        val counting = CountingBlockList(rawBlocks)

        val result = distributeBlocksIntoSections(sections, counting)

        assertEquals(
            referenceDistribution(sections, rawBlocks),
            result,
        )
        val busiest = counting.maxReadsPerIndex()
        assertTrue(
            busiest < sectionCount,
            "a block was read $busiest times across $sectionCount sections; the per-section rescan is back",
        )
    }
}

/**
 * 각 인덱스가 읽힌 횟수를 세는 [ReaderBlock] 목록이다. 테스트가
 * [distributeBlocksIntoSections]가 섹션마다 모든 블록을 접근하지 않음을 증명하는 데 사용한다.
 *
 * 단순 목록을 기반으로 [size]를 위임하며, 유일한 추가 동작은 인덱스별 집계다;
 * 래핑하는 블록을 변경하거나 재정렬하지 않는 읽기 전용 뷰다.
 *
 * @property backing 이 뷰가 제공하고 읽기를 집계하는 블록 목록.
 */
private class CountingBlockList(private val backing: List<ReaderBlock>) : AbstractList<ReaderBlock>() {
    /** 인덱스별 읽기 집계; 한 번도 읽히지 않은 인덱스는 그냥 부재한다. */
    private val readsByIndex = HashMap<Int, Int>()

    /** 래핑된 목록의 크기, 변경 없음. */
    override val size: Int get() = backing.size

    /**
     * [index]번째 블록을 반환하고 읽기를 기록한다.
     *
     * @param index 읽는 위치.
     * @return [backing]에서 [index]번째 블록.
     */
    override fun get(index: Int): ReaderBlock {
        readsByIndex[index] = (readsByIndex[index] ?: 0) + 1
        return backing[index]
    }

    /**
     * 단일 인덱스가 누적한 가장 큰 읽기 횟수, 아무것도 읽히지 않았으면 0이다.
     *
     * @return 가장 바쁜 인덱스의 읽기 횟수 — 섹션별 재탐색이 있으면 최소 섹션 수만큼 늘어날 값.
     */
    fun maxReadsPerIndex(): Int = readsByIndex.values.maxOrNull() ?: 0
}

/**
 * [concatPageStarts]를 `existing.toList() + appended.flatMap { it.toList() }`의 박싱 방식(대체 이전)으로부터
 * 보호한다: 결합된 배열은 조각들을 순서대로 담아야 하며, append 경로가 실제로 생성하는
 * 빈·단일·다수·대형 오프셋 형태를 모두 검증한다.
 */
class ConcatPageStartsTest {
    /**
     * 비어있는 existing 배열과 빈 appended 목록을 결합하면 빈 배열이 됨을 확인한다.
     * 아직 측정된 것이 없는 append의 기본 케이스다.
     */
    @Test
    fun joinsEmptyPiecesIntoEmptyArray() {
        assertEquals(emptyList(), concatPageStarts(LongArray(0), emptyList()).toList())
    }

    /**
     * 단일 appended 배열이 기존 오프셋 뒤에 순서대로 배치됨을 확인한다.
     * 일반적인 단일 섹션 배치 형태다.
     */
    @Test
    fun joinsExistingThenOneAppendedArrayInOrder() {
        val existing = longArrayOf(0L, 100L, 200L)
        val appended = listOf(longArrayOf(300L, 400L))

        assertEquals(listOf(0L, 100L, 200L, 300L, 400L), concatPageStarts(existing, appended).toList())
    }

    /**
     * 여러 appended 배열이 목록 순서대로, 각각 이전 배열 뒤에 이어붙여짐을 확인한다.
     * 다중 섹션 배치가 레이아웃을 읽기 순서대로 확장하며 인터리빙되지 않는다.
     */
    @Test
    fun joinsMultipleAppendedArraysInListOrder() {
        val existing = longArrayOf(0L, 10L)
        val appended = listOf(longArrayOf(20L, 30L), longArrayOf(), longArrayOf(40L, 50L, 60L))

        assertEquals(
            listOf(0L, 10L, 20L, 30L, 40L, 50L, 60L),
            concatPageStarts(existing, appended).toList(),
        )
    }

    /**
     * 실제 책 크기의 대형 오프셋들이 결합 후 순서를 유지하고 올바른 길이를 가짐을 확인한다.
     * [encodePageStartsBlob]의 자체 테스트가 명시하는 규모다(350만 자 분량 책, 수만 페이지).
     */
    @Test
    fun joinsLargeAscendingOffsetsInOrder() {
        val existing = LongArray(10_000) { it * 210L }
        val appendedFirst = LongArray(5_000) { (10_000 + it) * 210L }
        val appendedSecond = LongArray(5_000) { (15_000 + it) * 210L }

        val combined = concatPageStarts(existing, listOf(appendedFirst, appendedSecond))

        assertEquals(20_000, combined.size)
        assertEquals(LongArray(20_000) { it * 210L }.toList(), combined.toList())
    }
}
