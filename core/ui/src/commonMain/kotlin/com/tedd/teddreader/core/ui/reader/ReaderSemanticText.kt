package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import com.tedd.teddreader.core.designsystem.toColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderFloat
import com.tedd.teddreader.core.common.model.ReaderFontFamily
import com.tedd.teddreader.core.common.model.ReaderDefaultFontWeight
import com.tedd.teddreader.core.common.model.ReaderDefaultLineHeightMultiplier
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.isStandalone
import com.tedd.teddreader.core.common.model.readerImageSize
import com.tedd.teddreader.core.common.model.standaloneBlocks
import com.tedd.teddreader.core.common.model.TextRange

/** U+FFFC OBJECT REPLACEMENT CHARACTER: 그림이 렌더링된 텍스트에서 차지하는 단 하나의 문자. */
private const val ObjectReplacementChar = '\uFFFC'
/** 링크가 지니는 annotation 태그로, 렌더링된 텍스트를 탭했을 때 그 뒤의 href를 찾을 수 있게 한다. */
private const val ReaderLinkTag = "link"

/**
 * Compose가 그리는 대로의 페이지 텍스트와, 그것이 유래한 문서로 되돌아가는 맵.
 *
 * 렌더링은 문서에는 없는 문자들 — 인용 막대, 목록 불릿, 제목 룰, 그림 자리를 대신하는 placeholder —
 * 을 더하므로, 그런 접두부가 처음 나타나는 지점부터 표시 오프셋과 문서 오프셋은 더 이상 일치하지
 * 않는다. 리더가 위치를 다루는 모든 작업(진행률 저장, 검색 결과로 이동, 페이지가 끝나는 지점 보고)은
 * 문서 오프셋으로 말하므로, 이 맵은 필요한 쪽이 다시 계산하는 대신 문자열과 함께 이동한다.
 *
 * @property annotatedString 접두부와 placeholder를 포함해, 그릴 텍스트.
 * @property offsetMap 렌더링된 문자마다의 문서 오프셋. 끝 바로 다음 위치도 가리킬 수 있도록 문자열보다
 * 항목이 하나 더 많다; 값을 clamp하는 [sourceOffsetFor]를 통해 읽는다.
 * @property placeholders 그림과 룰을 위해 예약된 박스들로, 렌더링 순서대로다.
 * @property containerDecorations 컨테이너 블록에서 해석된, 전체 너비 블록 장식들로, 그리는 순서대로다.
 */
data class ReaderSemanticText(
    val annotatedString: AnnotatedString,
    val offsetMap: IntArray,
    val placeholders: List<ReaderPlaceholder>,
    val containerDecorations: List<ReaderContainerDecoration> = emptyList(),
)

/**
 * 렌더링된 텍스트 안 하나의 전체 너비 블록 장식 구간과, 렌더러가 이를 그리는 데 필요한 모든 것.
 *
 * 컨테이너 배경과 테두리는 그것들이 감싸는 글리프와 같은 텍스트 레이아웃에서 측정되므로, 페이지를
 * 그리는 데 필요한 기하 정보는 나중에 다시 알아내는 대신 텍스트와 함께 이동한다.
 */
data class ReaderContainerDecoration(
    val start: Int,
    val end: Int,
    val boxStyle: ReaderBoxStyle,
    val foregroundColor: ReaderColor? = null,
    val startsHere: Boolean = true,
    val endsHere: Boolean = true,
    val isPageContainer: Boolean = false,
    /** 박스가 자신의 위쪽 가장자리와 그 안 첫 줄 사이에 두는 간격, em 단위. */
    val paddingTopEm: Float = 0f,
    /** 박스가 그 안 마지막 줄과 자신의 아래쪽 가장자리 사이에 두는 간격, em 단위. */
    val paddingBottomEm: Float = 0f,
)

/**
 * 하나의 placeholder 안에서 플로팅된 이미지 옆에 놓이도록 측정된, 문단 콘텐츠의 선행 조각.
 *
 * 페이지 분할과 그리기 양쪽 모두 같은 맞춰진 조각을 소비하므로, float는 측정과 렌더링에서 같은 소스
 * 오프셋을 갖는다.
 */
data class ReaderFloatContent(
    val side: ReaderFloat,
    val text: ReaderSemanticText,
    val imageWidthEm: Float,
    val columnWidthEm: Float,
    val sourceEnd: Int,
)

/**
 * 렌더링된 텍스트 안 예약된 박스 하나와, 렌더러가 그것을 채우는 데 필요한 모든 것.
 *
 * 박스는 레이아웃 중에 예약되고 그리기 중에 채워지며, 이것이 바로 크기가 그리는 시점에 정해지는
 * 대신 여기에 있는 이유다: 페이지 분할은 누군가 그림을 로드하기 전에 그것이 페이지의 얼마를
 * 차지하는지 알아야 하고, 그려진 그림은 측정된 것과 일치해야 한다.
 *
 * @property id Compose가 박스를 그 콘텐츠와 매칭하는 inline-content 키. 페이지마다 고유하다.
 * @property kind 박스 안에 무엇이 서 있는지 — 그림, 표지, 룰, 또는 플로팅된 이미지와 그에 딸린
 * 중첩 텍스트.
 * @property href 바이트를 가져와야 하는 호출자를 위한, 컨테이너 안 이미지의 경로.
 * @property label 접근성과 로드 실패를 위한, 이미지의 대체 텍스트.
 * @property placeholder 박스 자체: 예약된 크기와 그것이 줄에 어떻게 정렬되는지.
 * @property start [ReaderSemanticText.annotatedString] 안에서 박스가 놓인 위치.
 * @property end [start] 바로 다음 위치 — 박스는 항상 정확히 한 문자를 차지한다.
 * @property floatContent 이 placeholder가 플로팅된 이미지일 때, 그 옆에 놓이도록 측정된 중첩 텍스트
 * 콘텐츠.
 * @property boxStyle 존재할 경우, 이미지 박스 자체에 속하는 출판사 박스 스타일링.
 * @property foregroundColor currentColor 스타일의 테두리를 위해 placeholder가 상속해야 하는 색상.
 */
data class ReaderPlaceholder(
    val id: String,
    val kind: ReaderBlockKind,
    val href: String? = null,
    val label: String? = null,
    val placeholder: Placeholder,
    val start: Int,
    val end: Int,
    val floatContent: ReaderFloatContent? = null,
    val boxStyle: ReaderBoxStyle? = null,
    val foregroundColor: ReaderColor? = null,
)

/**
 * 페이지 분할과 페이지 렌더링이 공유하는 float fitter의 입력.
 *
 * 이 요청은 float를 포함한 문단을 절대 문서 오프셋으로 담아, fitter가 이미지 이후 남은 조각만
 * 측정하면서도 호출자가 신뢰할 수 있는 소스 오프셋을 반환할 수 있게 한다.
 */
data class ReaderFloatPlacementRequest(
    val text: String,
    val blocks: List<ReaderBlock>,
    val range: TextRange,
    val paragraphRange: TextRange,
    val imageBlock: ReaderBlock,
    val imageSize: com.tedd.teddreader.core.common.model.ReaderImageSize,
)

/**
 * float 옆에 놓일 수 있도록 맞춰진, 문단의 선행 조각.
 *
 * [nestedRange]는 소스 오프셋을 유지하는 반면 [nestedText]는 이미지 옆 플로팅된 컬럼에 맞는 렌더링된
 * 부분 문자열을 담는다.
 */
data class ReaderFloatPlacement(
    val nestedRange: TextRange,
    val nestedText: ReaderSemanticText,
)

/**
 * float 옆에 들어맞는 가장 큰 선행 문단 조각을 반환하는 공유 콜백.
 *
 * 페이지 분할과 렌더링이 정확히 같은 맞춤 로직과 측정 입력을 공유할 수 있도록 주입된다.
 */
typealias ReaderFloatTextFitter = (ReaderFloatPlacementRequest) -> ReaderFloatPlacement?

/**
 * 블록과 span 스타일이 참조하는 내장 글꼴 href들을 모은다.
 *
 * EPUB 페이지 분할은 참조된 모든 글꼴이 해석되거나 실패할 때까지 기다리므로, 이 스캔은 첫 측정에
 * 어떤 href가 중요한지 호출자에게 알려주는 저렴한 계약이다.
 */
fun readerReferencedFontHrefs(blocks: List<ReaderBlock>): Set<String> = buildSet {
    blocks.forEach { block ->
        block.style?.fontHref?.let(::add)
        block.spans.forEach { span -> span.styleDelta?.fontHref?.let(::add) }
    }
}

/**
 * 저장된 텍스트와 그 블록 구조를 페이지가 그리는 문자열과, 그로부터 되돌아가는 오프셋 맵으로
 * 바꾼다.
 *
 * 이곳은 문서의 평평한 텍스트가 시각적인 무언가가 되는 유일한 장소이며, 리더의 양쪽에서 실행된다:
 * 페이지 breaker가 이것으로 측정하고, 페이지 서피스가 이것으로 그린다. 둘은 반드시 같은 문자열을
 * 봐야 한다. 그렇지 않으면 페이지가 한 문자 집합으로 측정되고 다른 문자 집합으로 그려지게 되며,
 * 이것이 페이지의 마지막 줄이 잘리는 원인이다.
 *
 * 그림은 블록 사이로 들어올려지는 대신 텍스트 *안에* 하나의 placeholder 문자로 유지된다. HTML이
 * 그것을 두는 방식이 그렇기 때문이다: 외자 글리프나 아이콘은 그것이 쓰인 문장 안에 속한다. 자기
 * 블록의 유일한 내용인 그림은 자기만의 문단을 갖는다 — 이것이 책이 요구하는 대로 그것을 가운데
 * 정렬하고 산문이 그 옆에 놓이지 않게 막는다 — 반면 문장 안에 쓰인 그림은 그중 아무것도 갖지 않는데,
 * 그곳의 문단은 문장을 끊고 감싸는 스타일과 충돌하기 때문이다. 플로팅된 이미지는 여전히 그 단일
 * placeholder 위치를 유지하지만, 그 옆에 들어맞는 감싸는 문단의 가장 큰 선행 조각도 함께 소비할 수
 * 있다; 소비된 소스 범위는 placeholder에 매핑되어, 측정과 그리기가 남은 텍스트가 재개되는 지점에
 * 합의하게 한다.
 *
 * 여기의 두 할당이 책을 여는 작업의 대부분을 차지했었고, 이제 둘 다 의도적이다: 오프셋 맵은
 * 렌더링된 문자마다 `Integer`를 박싱했던 `ArrayList<Int>` 대신 늘어날 수 있는 [IntBuffer]이고, 각
 * 블록의 표시 시작 위치는 동일한 블록을 찾아 목록을 검색하는 대신 — 그 방식은 블록마다 전체 블록을
 * span까지 포함해 비교하여 이차 시간이 걸렸다 — 맵 안의 그 *위치*로 기록된다.
 *
 * @param text 렌더링할 문서 텍스트 구간.
 * @param blocks 절대 문서 오프셋 기준으로 그것을 덮는 블록들; 블록들은 [range]로 clamp되므로, 한
 * 페이지 텍스트에 대해 섹션 전체의 블록들을 전달하는 것도 예상된 사용이다.
 * @param range [text]가 문서 안에 놓인 위치로, 반환되는 오프셋들을 절대적으로 만드는 값이다.
 * 기본값은 [text]를 문서 전체로 취급한다.
 * @param lineWidthEm em 단위의 텍스트 컬럼 너비로, `max-width: 100%`처럼 이미지를 제한한다; 0이면
 * 알 수 없음을 뜻하며 기본 컬럼으로 대체된다.
 * @param maxHeightEm em 단위의, 한 페이지에 사용 가능한 높이로, `max-height`처럼 이미지를 제한한다;
 * 0이면 기본 페이지로 대체된다.
 * @param emInPx em당 CSS 픽셀로, 이미지의 고유 픽셀 너비를 em으로 바꾼다; 0이면 고유 너비를 사용할
 * 수 없다는 뜻이다.
 * @param embeddedFontFamiliesByHref EPUB href로 키가 매겨진, 해석된 내장 글꼴 패밀리로, 페이지
 * 분할과 그리기가 재사용하여 둘 다 같은 글꼴로 측정한다.
 * @param publisherColorsEnabled 출판사 전경/배경 색상을 적용할지 여부.
 * @param publisherFontsEnabled 출판사가 요청한 일반/커스텀 글꼴 패밀리를 적용할지 여부.
 * @param floatTextFitter 공유 float 맞춤 콜백; null이면 float 중첩을 비활성화하고 이미지를 일반
 * placeholder로 남긴다.
 * @param baseFontWeight 리더가 선택한 기본 본문 굵기 — 리더 스타일이 자체 `fontWeight`로 저장하는
 * 것과 같은 300..600 범위의 값이다. 출판사 강조(제목, 굵은 텍스트, 표 헤더 셀)는 고정된 굵기가
 * 아니라 이 값을 기준으로 상대적으로 그려지므로, 이 값을 올리거나 내려도 본문 텍스트와 강조 사이의
 * 간격이 좁혀지지 않는다. 기본값은 [ReaderDefaultFontWeight]로, 테스트를 포함한 기존 호출자들이 이
 * 파라미터가 존재하기 전과 정확히 같은 것을 계속 그리게 한다.
 * @return 그릴 수 있는 문자열, 그 오프셋 맵, 그림을 위해 예약된 박스들. 빈 [text]는 유효하지 않은
 * 것이 아니라 항목이 하나뿐인 맵을 가진 빈 문자열을 낳는다.
 */
fun buildReaderSemanticText(
    text: String,
    blocks: List<ReaderBlock>,
    range: TextRange = TextRange(0, text.length.toLong()),
    lineWidthEm: Float = 0f,
    maxHeightEm: Float = 0f,
    emInPx: Float = 0f,
    embeddedFontFamiliesByHref: Map<String, FontFamily> = emptyMap(),
    publisherColorsEnabled: Boolean = false,
    publisherFontsEnabled: Boolean = true,
    floatTextFitter: ReaderFloatTextFitter? = null,
    lineHeightMultiplier: Float = 1f,
    baseFontWeight: Int = ReaderDefaultFontWeight,
): ReaderSemanticText {
    if (text.isEmpty()) {
        return ReaderSemanticText(AnnotatedString(""), intArrayOf(0), emptyList())
    }

    val absoluteStart = range.start.toInt()
    val absoluteEnd = range.end.toInt().coerceAtMost(absoluteStart + text.length)
    val localLength = (absoluteEnd - absoluteStart).coerceAtLeast(0)
    val sourceToDisplay = IntArray(localLength + 1)
    val displayToSource = IntBuffer(text.length + blocks.size * 4 + 1)
    val display = StringBuilder(text.length + blocks.size * 4)
    val placeholderSpecs = mutableListOf<PlaceholderSpec>()

    val clampedBlocks = blocks.mapIndexedNotNull { index, block ->
        val start = block.range.start.coerceAtLeast(range.start)
        val end = block.range.end.coerceAtMost(range.end)
        if (start >= end && block.kind != ReaderBlockKind.IMAGE && block.kind != ReaderBlockKind.COVER_IMAGE && block.kind != ReaderBlockKind.SEPARATOR) {
            null
        } else {
            val localStart = (start - range.start).toInt().coerceAtLeast(0)
            val localEnd = (end - range.start).toInt().coerceAtLeast(localStart)
            ClampedBlock(index, block, localStart, localEnd, includesStart = block.range.start in range.start until range.end)
        }
    }
    val blockDisplayStart = HashMap<Int, Int>(clampedBlocks.size)
    val blocksByStart = clampedBlocks.groupBy { it.localStart }
    val placeholderBlocksByStart = clampedBlocks
        .filter { it.block.kind == ReaderBlockKind.IMAGE || it.block.kind == ReaderBlockKind.COVER_IMAGE || it.block.kind == ReaderBlockKind.SEPARATOR }
        .associateBy { it.localStart }

    val leafBlocks = clampedBlocks.filter { it.block.kind != ReaderBlockKind.CONTAINER }
    val coverage = IntArray(localLength + 2)
    leafBlocks.forEach { block ->
        val start = block.localStart.coerceIn(0, localLength)
        val end = block.localEnd.coerceIn(start, localLength)
        if (end > start) {
            coverage[start] += 1
            coverage[end] -= 1
        }
    }
    var coverageRunning = 0
    val covered = BooleanArray(localLength + 1)
    for (index in 0..localLength) {
        coverageRunning += coverage[index]
        covered[index] = coverageRunning > 0
    }
    val leafStarts = leafBlocks.sortedBy { it.localStart }
    val leafEnds = leafBlocks.sortedBy { it.localEnd }
    val gapRanges = mutableListOf<Pair<Int, Float>>()

    var localIndex = 0
    while (localIndex < localLength) {
        val sourceAbsolute = absoluteStart + localIndex
        val sourceChar = text[localIndex]
        if (sourceChar == '\n' && !covered[localIndex]) {
            var runEnd = localIndex
            while (runEnd < localLength && text[runEnd] == '\n' && !covered[runEnd]) runEnd += 1
            val gapEm = blockGapEm(
                before = leafEnds.lastOrNull { it.localEnd <= localIndex }?.block,
                after = leafStarts.firstOrNull { it.localStart >= runEnd }?.block,
            ) + containerEdgeEm(clampedBlocks, localIndex, runEnd, emInPx)
            for (skippedIndex in localIndex until runEnd) sourceToDisplay[skippedIndex] = display.length
            if (gapEm > 0f) {
                gapRanges += display.length to gapEm
                display.append(BlockGapChar)
                displayToSource += sourceAbsolute
            }
            localIndex = runEnd
            continue
        }
        blocksByStart[localIndex].orEmpty()
            .filterNot { it.block.kind == ReaderBlockKind.IMAGE || it.block.kind == ReaderBlockKind.COVER_IMAGE || it.block.kind == ReaderBlockKind.SEPARATOR }
            .forEach { block ->
                val prefix = blockPrefix(block.block).takeIf { block.includesStart }.orEmpty()
                val blockDisplayStartIndex = display.length
                prefix.forEach { char ->
                    display.append(char)
                    displayToSource += sourceAbsolute
                }
                blockDisplayStart[block.index] = blockDisplayStartIndex
            }

        sourceToDisplay[localIndex] = display.length
        val placeholderBlock = placeholderBlocksByStart[localIndex]
        if (placeholderBlock != null && placeholderBlock.includesStart) {
            val placeholderId = "${placeholderBlock.block.kind.name.lowercase()}-${absoluteStart + localIndex}-${placeholderSpecs.size}"
            val floatContent = if (placeholderBlock.block.kind == ReaderBlockKind.IMAGE && placeholderBlock.block.float != null && floatTextFitter != null) {
                buildFloatContent(
                    imageBlock = placeholderBlock,
                    clampedBlocks = clampedBlocks,
                    text = text,
                    range = range,
                    lineWidthEm = lineWidthEm,
                    maxHeightEm = maxHeightEm,
                    emInPx = emInPx,
                    floatTextFitter = floatTextFitter,
                )
            } else {
                null
            }
            display.append(ObjectReplacementChar)
            displayToSource += sourceAbsolute
            placeholderSpecs += PlaceholderSpec(
                id = placeholderId,
                kind = placeholderBlock.block.kind,
                href = placeholderBlock.block.imageHref,
                label = placeholderBlock.block.label,
                block = placeholderBlock.block,
                start = display.length - 1,
                floatContent = floatContent,
            )
            val nextLocalIndex = maxOf(
                placeholderBlock.localEnd.coerceAtLeast(localIndex + 1),
                floatContent?.sourceEnd?.minus(absoluteStart) ?: Int.MIN_VALUE,
            ).coerceAtMost(localLength)
            for (skippedIndex in localIndex + 1..nextLocalIndex.coerceAtMost(localLength)) {
                sourceToDisplay[skippedIndex] = display.length
            }
            localIndex = nextLocalIndex
            continue
        }

        display.append(sourceChar)
        displayToSource += sourceAbsolute
        localIndex += 1
    }
    sourceToDisplay[localLength] = display.length
    displayToSource += absoluteEnd

    val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
    val annotations = mutableListOf<Triple<String, String, IntRange>>()
    val paragraphs = mutableListOf<Pair<IntRange, ParagraphStyle>>()
    val emphasisWeights = readerEmphasisWeights(baseFontWeight)

    clampedBlocks.forEach { block ->
        if (block.block.kind == ReaderBlockKind.IMAGE || block.block.kind == ReaderBlockKind.COVER_IMAGE || block.block.kind == ReaderBlockKind.SEPARATOR) return@forEach
        // 컨테이너는 장식과 간격 여백만 기여한다. 그 상속된 스타일링은 파서가 그 안에서 해석한 모든
        // 리프 블록에 이미 구워져 있고, 컨테이너 자체의 span은 그 리프들 사이 폭 없는 간격 문자까지
        // 덮게 되는데 — 이것이 밑줄과 배경 조각이 문단 사이 빈 공간에 나타나곤 했던 이유이고,
        // 컨테이너 글자 크기가 간격 줄에 겹겹이 곱해져 그 측정된 높이를 깨뜨렸던 이유다.
        if (block.block.kind == ReaderBlockKind.CONTAINER) return@forEach
        val blockStart = blockDisplayStart[block.index] ?: sourceToDisplay[block.localStart]
        val blockEnd = sourceToDisplay[block.localEnd]
        if (blockEnd <= blockStart) return@forEach

        blockSpanStyle(block.block, embeddedFontFamiliesByHref, publisherColorsEnabled, publisherFontsEnabled, emphasisWeights)?.let { spans += (blockStart until blockEnd) to it }
        run {
            val paragraphStyle = blockParagraphStyle(
                block = block.block,
                indentsFirstLine = block.includesStart,
                justifies = text.justifiesWell(block.localStart, block.localEnd),
                lineHeightMultiplier = lineHeightMultiplier,
            ) ?: EmptyParagraphStyle
            paragraphs += (blockStart until blockEnd) to paragraphStyle
        }

        block.block.spans.forEach { span ->
            val start = (span.range.start - range.start).toInt().coerceIn(0, localLength)
            val end = (span.range.end - range.start).toInt().coerceIn(start, localLength)
            if (end <= start) return@forEach
            val displayStart = sourceToDisplay[start]
            val displayEnd = sourceToDisplay[end]
            if (displayEnd <= displayStart) return@forEach
            inlineSpanStyle(span, embeddedFontFamiliesByHref, publisherColorsEnabled, publisherFontsEnabled, emphasisWeights)?.let { spans += (displayStart until displayEnd) to it }
            if (span.style == ReaderInlineStyle.LINK) {
                span.href?.let { href -> annotations += Triple(ReaderLinkTag, href, displayStart until displayEnd) }
            }
        }
    }

    gapRanges.forEach { (start, gapEm) ->
        paragraphs += (start until start + 1) to ParagraphStyle(lineHeight = gapEm.em)
        spans += (start until start + 1) to SpanStyle(fontSize = (gapEm / GapLineNaturalHeightRatio).em)
    }

    val standaloneBlocks = clampedBlocks.map { it.block }.standaloneBlocks().toSet()
    placeholderSpecs.forEach { spec ->
        if (spec.block !in standaloneBlocks || spec.floatContent != null) return@forEach
        blockParagraphStyle(spec.block)?.let { style ->
            paragraphs += (spec.start until spec.start + 1) to style
        }
    }

    // float placeholder는 십여 줄 높이의 전체 컬럼 박스다. 그것이 쓰인 문단 안에 그대로 두면, 그
    // 줄 상자가 그 문단의 뒤따르는 모든 줄의 줄 높이 해석을 오염시킨다 — 남은 산문이 그림 높이의
    // 줄 상자로 나오게 된다. placeholder 주위로 문단을 나누면 그 높이가 placeholder 자체의 줄에만
    // 국한되고, 주위 텍스트는 문단이 명시한 스타일을 유지한다.
    placeholderSpecs.filter { it.floatContent != null }.map(PlaceholderSpec::start).sorted().forEach { floatStart ->
        val containing = paragraphs.indexOfFirst { (rangeValue, _) -> floatStart in rangeValue && rangeValue.count() > 1 }
        val floatParagraph = (floatStart until floatStart + 1) to EmptyParagraphStyle
        if (containing < 0) {
            paragraphs += floatParagraph
            return@forEach
        }
        val (rangeValue, style) = paragraphs.removeAt(containing)
        if (rangeValue.first < floatStart) paragraphs += (rangeValue.first until floatStart) to style
        paragraphs += floatParagraph
        if (floatStart + 1 <= rangeValue.last) paragraphs += (floatStart + 1..rangeValue.last) to style
    }

    val placeholders = placeholderSpecs.map { spec ->
        val inheritedForeground = clampedBlocks
            .filter { it.block.kind != ReaderBlockKind.IMAGE && it.block.kind != ReaderBlockKind.COVER_IMAGE && it.block.kind != ReaderBlockKind.SEPARATOR }
            .filter { it.block.range.start <= spec.block.range.start && it.block.range.end >= spec.block.range.end }
            .minByOrNull { it.block.range.end - it.block.range.start }
            ?.block
            ?.style
            ?.foregroundColor
        ReaderPlaceholder(
            id = spec.id,
            kind = spec.kind,
            href = spec.href,
            label = spec.label,
            placeholder = placeholderFor(
                block = spec.block,
                isStandalone = spec.block in standaloneBlocks,
                lineWidthEm = lineWidthEm,
                maxHeightEm = maxHeightEm,
                emInPx = emInPx,
                isFloat = spec.floatContent != null,
            ),
            start = spec.start,
            end = spec.start + 1,
            floatContent = spec.floatContent,
            boxStyle = spec.block.style?.boxStyle,
            foregroundColor = spec.block.style?.foregroundColor ?: inheritedForeground,
        )
    }

    val annotatedString = buildAnnotatedString {
        val placeholdersByStart = placeholderSpecs.associateBy(PlaceholderSpec::start)
        var displayIndex = 0
        while (displayIndex < display.length) {
            val placeholder = placeholdersByStart[displayIndex]
            if (placeholder != null) {
                appendInlineContent(placeholder.id, ObjectReplacementChar.toString())
            } else {
                append(display[displayIndex])
            }
            displayIndex += 1
        }
        // placeholder의 예약된 박스는 em 단위로 명시되고, Compose는 그 em을 placeholder 위치에서
        // 적용 중인 글자 크기를 기준으로 해석한다 — 그래서 0.85em 블록 span 안의 placeholder는 이미지
        // 크기 조정, float 맞춤, 페이지 분할 연산 등 모든 소비자가 기준 em으로 계산한 것보다 15% 더
        // 작게 예약되었다. 각 placeholder 주위로 span을 나누면 그 em이 기준 em으로 유지된다.
        val placeholderStarts = placeholderSpecs.map(PlaceholderSpec::start).toSet()
        spans.flatMap { (rangeValue, style) -> rangeValue.splitAround(placeholderStarts).map { it to style } }
            .forEach { (rangeValue, style) -> addStyle(style, rangeValue.first, rangeValue.last + 1) }
        paragraphs.withoutOverlaps().forEach { (rangeValue, style) -> addStyle(style, rangeValue.first, rangeValue.last + 1) }
        annotations.forEach { (tag, value, rangeValue) -> addStringAnnotation(tag, value, rangeValue.first, rangeValue.last + 1) }
    }

    // 래퍼가 먼저 그려지고(가장 바깥쪽이 가장 아래), 그 위에 스타일이 적용된 리프 블록들이 자기
    // 박스를 그린다 — 예전에 리프가 파싱 시점의 컨테이너 쌍둥이로부터 받던 박스가 이제는 리프에서
    // 곧바로 나온다. 단독 이미지 종류는 제외된다: 이미지 박스는 자기 배경과 테두리를 스스로 그린다.
    val containerDecorations = clampedBlocks
        .asSequence()
        .filter { block ->
            block.block.kind == ReaderBlockKind.CONTAINER || !block.block.kind.isStandalone()
        }
        .mapNotNull { block ->
            block.block.style?.boxStyle
                ?.takeUnless(ReaderBoxStyle::isEmpty)
                ?.let { boxStyle -> block to boxStyle }
        }
        .sortedWith(
            compareBy<Pair<ClampedBlock, ReaderBoxStyle>> { if (it.first.block.kind == ReaderBlockKind.CONTAINER) 0 else 1 }
                .thenBy { it.first.block.level }
                .thenBy { it.first.index },
        )
        .mapNotNull { (block, boxStyle) ->
            val start = blockDisplayStart[block.index] ?: sourceToDisplay[block.localStart]
            val end = sourceToDisplay[block.localEnd]
            if (end > start) ReaderContainerDecoration(
                start = start,
                end = end,
                boxStyle = boxStyle,
                foregroundColor = block.block.style?.foregroundColor,
                startsHere = block.block.range.start >= range.start,
                endsHere = block.block.range.end <= range.end,
                isPageContainer = block.block.isPageContainer,
                paddingTopEm = block.block.style?.paddingTopEm ?: 0f,
                paddingBottomEm = block.block.style?.paddingBottomEm ?: 0f,
            ) else null
        }
        .toList()

    return ReaderSemanticText(
        annotatedString = annotatedString,
        offsetMap = displayToSource.toIntArray(),
        placeholders = placeholders,
        containerDecorations = containerDecorations,
    )
}

private fun buildFloatContent(
    imageBlock: ClampedBlock,
    clampedBlocks: List<ClampedBlock>,
    text: String,
    range: TextRange,
    lineWidthEm: Float,
    maxHeightEm: Float,
    emInPx: Float,
    floatTextFitter: ReaderFloatTextFitter,
): ReaderFloatContent? {
    val paragraph = clampedBlocks
        .filter { it.block.kind != ReaderBlockKind.IMAGE && it.block.kind != ReaderBlockKind.COVER_IMAGE && it.block.kind != ReaderBlockKind.SEPARATOR }
        .filter { it.localStart <= imageBlock.localStart && it.localEnd >= imageBlock.localEnd }
        .minByOrNull { it.localEnd - it.localStart }
        ?: return null
    val imageSize = imageBlock.block.readerImageSize(
        columnWidthEm = lineWidthEm.takeIf { it > 0f } ?: DefaultImageWidthEm,
        maxHeightEm = maxHeightEm.takeIf { it > 0f } ?: DefaultImageMaxHeightEm,
        emInPx = emInPx,
    )
    val placement = floatTextFitter(
        ReaderFloatPlacementRequest(
            text = text,
            blocks = clampedBlocks.map(ClampedBlock::block),
            range = range,
            paragraphRange = TextRange(
                range.start + paragraph.localStart,
                range.start + paragraph.localEnd,
            ),
            imageBlock = imageBlock.block,
            imageSize = imageSize,
        ),
    ) ?: return null
    return ReaderFloatContent(
        side = imageBlock.block.float ?: return null,
        text = placement.nestedText,
        imageWidthEm = imageSize.widthEm,
        columnWidthEm = lineWidthEm.takeIf { it > 0f } ?: DefaultImageWidthEm,
        sourceEnd = placement.nestedRange.end.toInt(),
    )
}

/** [holes]의 모든 위치를 제외한 하위 범위들로 잘린 이 범위; 빈 조각은 버려진다. */
private fun IntRange.splitAround(holes: Set<Int>): List<IntRange> {
    if (holes.none { it in this }) return listOf(this)
    val pieces = mutableListOf<IntRange>()
    var pieceStart = first
    for (position in this) {
        if (position in holes) {
            if (position > pieceStart) pieces += pieceStart until position
            pieceStart = position + 1
        }
    }
    if (pieceStart <= last) pieces += pieceStart..last
    return pieces
}

/** 오프셋 맵을 만들 때 문자마다 하나의 Integer를 박싱하지 않도록 쓰이는, 늘어날 수 있는 원시 int 버퍼. */
private class IntBuffer(initialCapacity: Int) {
    private var values = IntArray(initialCapacity.coerceAtLeast(16))
    private var size = 0

    operator fun plusAssign(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size] = value
        size += 1
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

/**
 * CSS가 해석하는 방식대로 해석된, 두 블록 사이 em 단위 간격: 각 쪽이 자신의 margin을 명시하고,
 * 인접한 수직 margin은 둘 중 더 큰 값으로 합쳐지며, 아무것도 명시하지 않은 쪽은 브라우저가 그
 * 요소에 주는 기본값으로 대체된다.
 *
 * 이것이 책의 문단 리듬이 살아남는 이유의 전부다. 저장된 텍스트는 두 블록을 개행으로 구분하는데,
 * 그 개행을 텍스트로 그리면 리더 자체 줄 높이만큼의 온전한 한 줄을 비용으로 치르게 된다 — 문단
 * 사이에 `margin-bottom: 10px`를 요구한 책은 자신이 쓴 것의 6배에 달하는 간격을 얻었고, 아무것도
 * 요구하지 않은 책조차 온전한 빈 줄 하나를 얻었다. 대신 이 간격은 합쳐진 margin과 정확히 같은
 * 높이의 단일 줄로 그려져, `margin: 0`인 산문은 그 들여쓰기가 가정하는 대로 이어지고 명시된
 * margin은 명시한 크기 그대로가 된다.
 *
 * 앞에 아무것도 없거나 뒤에 아무것도 없는 간격은 버려진다: 그것은 페이지의 위나 아래에 빈 공간이
 * 될 것이고, 어떤 리딩 시스템도 그곳에 그것을 남기지 않는다.
 *
 * @param before 간격이 뒤따르는 블록, 또는 간격이 렌더링된 구간을 여는 경우 null.
 * @param after 간격이 앞서는 블록, 또는 간격이 그것을 닫는 경우 null.
 * @return em 단위의 간격. 결코 음수가 되지 않는다.
 */
private fun blockGapEm(before: ReaderBlock?, after: ReaderBlock?): Float {
    if (before == null || after == null) return 0f
    val below = before.style?.marginBottomEm ?: defaultBlockMarginEm(before.kind)
    val above = after.style?.marginTopEm ?: defaultBlockMarginEm(after.kind)
    val padding = (before.style?.paddingBottomEm ?: 0f) + (after.style?.paddingTopEm ?: 0f)
    val stated = (maxOf(below, above) + padding).coerceAtLeast(0f)
    return if (stated > 0f || !runsOnUnreadably(before, after)) stated else MobileParagraphFloorEm
}

/**
 * 이 두 블록을 구분해 줄 것이 전혀 없어 서로 이어져 버릴지 여부.
 *
 * 책은 문단에 간격도 첫 줄 들여쓰기도 명시하지 않을 수 있다 — 많은 책이 그렇게 한다. 그들이
 * 조판된 넓은 페이지에서는 줄 길이만으로도 문단 나눔이 읽을 만하게 유지되기 때문이다. 폰에서는
 * 같은 설정이 한 문단이 끝나고 다음 문단이 줄 중간에서 시작되는 활자 벽이 되어 버린다. 여기서
 * 리더가 제공하는 간격은 그것들을 떼어 놓는 가장 작은 것이며, 책이 두 가지 구분 수단을 모두 빠뜨렸을
 * 때만 제공된다.
 *
 * @param before 간격이 뒤따르는 블록.
 * @param after 간격이 앞서는 블록.
 * @return 간격도 들여쓰기도 이들을 구분하지 못할 때 true.
 */
private fun runsOnUnreadably(before: ReaderBlock, after: ReaderBlock): Boolean {
    if (before.kind != after.kind) return false
    if (before.kind != ReaderBlockKind.PARAGRAPH && before.kind != ReaderBlockKind.QUOTE) return false
    return (after.style?.textIndentEm ?: 0f) <= 0f
}

/**
 * 책이 전혀 구분하지 않는 두 문단 사이에 리더가 두는 가장 작은 간격.
 *
 * 리더 자체의 간격이 아니라 책이 요구한 것과 같은 설정으로 읽힐 만큼 작다 — 빈 줄 하나가 들었을
 * 온전한 한 줄에 비해, 4분의 1 줄 정도다.
 */
private const val MobileParagraphFloorEm = 0.35f

/**
 * 앞선 것과 겹치는 것은 안쪽부터 우선으로 버려진, 이 문단 범위들.
 *
 * 문단은 텍스트의 확고한 구분이며, Compose는 겹치는 두 개를 거부한다 — 페이지를 그리는 대신
 * 예외를 던진다. 잘 만들어진 문서가 생성하는 블록은 결코 겹치지 않지만, 잘못 만들어진 문서는 그럴
 * 수 있고(셀 안의 셀, 문서가 결코 닫지 않은 제목), 그런 상태에 이른 책도 여전히 읽을 수 있어야
 * 한다. 더 좁은 범위가 이긴다. 그것이 자신이 설명하는 텍스트에 가장 가까운 것이기 때문이다.
 *
 * @receiver 렌더링 중에 수집된, 블록 순서의 문단 범위들.
 * @return 함께 적용할 수 있는 부분집합.
 */
private fun List<Pair<IntRange, ParagraphStyle>>.withoutOverlaps(): List<Pair<IntRange, ParagraphStyle>> {
    if (size <= 1) return this
    val ordered = sortedWith(compareBy({ it.first.first }, { it.first.last - it.first.first }))
    val kept = mutableListOf<Pair<IntRange, ParagraphStyle>>()
    var lastEnd = Int.MIN_VALUE
    ordered.forEach { candidate ->
        if (candidate.first.first >= lastEnd) {
            kept += candidate
            lastEnd = candidate.first.last + 1
        }
    }
    return kept
}

/**
 * 박스 자체의 가장자리가 이 간격 안에 떨어질 때 필요로 하는 공간, em 단위.
 *
 * 테두리와 `padding: 1em 0`을 가진 `<div>` — 책이 목차나 저자 노트를 두르는 모양 — 는 그 안 첫
 * 줄 위와 마지막 줄 아래에 룰을 그린다. 그 룰과 그것이 떨어져 있는 만큼의 padding을 위한 공간이
 * 예약되지 않으면, 룰은 단어들을 그대로 관통해 그려진다: 박스는 자기 공간이 없어서 텍스트의
 * 공간을 빌리게 된다.
 *
 * @param blocks 컨테이너를 포함해, 렌더링되는 구간의 모든 블록.
 * @param gapStart 렌더링되는 구간을 기준으로 한, 간격의 첫 오프셋.
 * @param gapEnd 그 마지막 오프셋 바로 다음.
 * @param emInPx 테두리 너비를 em으로 바꾸는, em당 CSS 픽셀; 0이면 테두리를 빼놓는다.
 * @return 여기서 열리고 닫히는 박스들이 필요로 하는 공간, em 단위.
 */
private fun containerEdgeEm(
    blocks: List<ClampedBlock>,
    gapStart: Int,
    gapEnd: Int,
    emInPx: Float,
): Float {
    // CONTAINER는 항상 진짜 래퍼다(파서가 소스 단계에서 같은 범위·같은 스타일의 쌍둥이를 억제한다),
    // 그래서 그 margin, padding, 테두리는 모두 여기서 자기만의 공간을 필요로 한다 — 어떤 리프도
    // 그것들을 대신 계산하지 않는다. 리프 블록 자체의 padding과 margin은 이미 blockGapEm을 통해
    // 간격에 반영되므로, 스타일이 적용된 리프는 blockGapEm이 알 수 없는 단 하나 — 그 테두리 획 —
    // 만을 예약한다.
    var extra = 0f
    blocks.forEach { block ->
        val style = block.block.style ?: return@forEach
        val isWrapper = block.block.kind == ReaderBlockKind.CONTAINER
        if (isWrapper && block.block.isPageContainer) return@forEach
        if (!isWrapper && block.block.kind.isStandalone()) return@forEach
        if (block.localStart in gapStart..gapEnd) {
            extra += style.boxStyle?.borderTop.widthEm(emInPx) +
                (if (isWrapper) (style.paddingTopEm ?: 0f) + (style.marginTopEm ?: 0f) else 0f)
        }
        if (block.localEnd in gapStart..gapEnd) {
            extra += style.boxStyle?.borderBottom.widthEm(emInPx) +
                (if (isWrapper) (style.paddingBottomEm ?: 0f) + (style.marginBottomEm ?: 0f) else 0f)
        }
    }
    return extra
}

/** 이 테두리의 em 단위 너비. 테두리가 없거나 1em의 픽셀 너비를 알 수 없으면 0. */
private fun com.tedd.teddreader.core.common.model.ReaderBorder?.widthEm(emInPx: Float): Float {
    val widthPx = this?.widthPx ?: return 0f
    return if (emInPx > 0f) widthPx / emInPx else 0f
}

/**
 * 브라우저 자체 스타일시트가 이 종류에 주는 margin으로, 책이 아무것도 명시하지 않을 때만 쓰인다.
 *
 * 이것들은 모든 엔진이 탑재하는 CSS2.1 예시 스타일시트 값이다: 문단, blockquote, `pre`의 위아래에는
 * `1em`, `h1` 둘레에는 `0.67em`, 목록 항목이나 표 셀 둘레에는 아무것도 없으며 이들은 대신 그
 * 목록이나 행에 의해 간격이 정해진다.
 *
 * @receiver 기본 margin을 알고 싶은 종류.
 * @return em 단위의 그 margin.
 */
private fun defaultBlockMarginEm(kind: ReaderBlockKind): Float = when (kind) {
    ReaderBlockKind.PARAGRAPH,
    ReaderBlockKind.QUOTE,
    ReaderBlockKind.PREFORMATTED,
    -> 1f
    ReaderBlockKind.HEADING -> 0.67f
    ReaderBlockKind.IMAGE,
    ReaderBlockKind.COVER_IMAGE,
    ReaderBlockKind.SEPARATOR,
    -> 0.5f
    ReaderBlockKind.LIST_ITEM,
    ReaderBlockKind.TABLE_CELL,
    ReaderBlockKind.TABLE_HEADER_CELL,
    ReaderBlockKind.CONTAINER,
    -> 0f
}

/** 블록 사이 간격이 차지하는 단 하나의 문자: 폭 없는 공백으로, 간격 줄이 아무것도 그리지 않게 한다. */
private const val BlockGapChar = '\u200B'

/**
 * 줄이 본래 자기 글자 크기보다 얼마나 더 큰지를 나타내며, 간격 줄이 되어야 할 간격을 그 자연스러운
 * 높이가 결코 넘지 않도록 간격 줄이 설정될 활자 크기를 고르는 데 쓰인다.
 *
 * 줄 상자는 최소한 글꼴이 요구하는 만큼 높으므로, 리더 자체 크기로 설정된 간격 줄은 margin이
 * 아무리 작아도 온전한 한 줄보다 짧아질 수 없었다. 이 비율만큼 축소된 활자로 설정하면 명시된 줄
 * 높이가 다시 구속력 있는 제약이 된다.
 */
private const val GapLineNaturalHeightRatio = 1.3f

/** 스타일링은 필요 없지만 여전히 자기만의 문단이어야 하는 블록에 붙는, 아무것도 요구하지 않는 문단
 *  — 이것이 없으면 그 사이 간격이 닫혔을 때 뒤의 블록으로 이어져 버릴 것이다. */
private val EmptyParagraphStyle = ParagraphStyle()

/** 렌더링된 문자 위치를 그것이 유래한 문서 오프셋으로 되돌려 매핑하며, 범위 밖 요청은 clamp한다. */
fun ReaderSemanticText.sourceOffsetFor(displayIndex: Int): Int =
    offsetMap[displayIndex.coerceIn(0, offsetMap.lastIndex)]

/**
 * 블록이 자기 시작 부분에 기여하는 눈에 보이는 접두부: 목록 항목의 마커, 그것뿐이다.
 *
 * 마커는 문서에 속한다 — 브라우저도 모든 `<li>`에 대해 하나를 그린다 — 그래서 렌더링된 텍스트
 * 안에 쓰인다. 제목이나 인용문은 아무것도 얻지 못한다. 예전에 이들 옆에 그려지던 막대는 책이 결코
 * 요구하지 않은 활자였다: 그것은 책 자체의 가운데 정렬, 들여쓰기, margin과 충돌했고, 어떤 리딩
 * 시스템도 그곳에 그것을 두지 않는다. 제목을 구별하는 것은 책이 명시하는 크기, 굵기, 간격이거나,
 * 그것이 없으면 리더가 대체하는 브라우저 기본값이다.
 */
private fun blockPrefix(block: ReaderBlock): String = when (block.kind) {
    ReaderBlockKind.LIST_ITEM -> "${"  ".repeat((block.level - 1).coerceAtLeast(0))}${block.label ?: "•"} "
    else -> ""
}

/**
 * 인라인 span들이 더 좁히기 전에 블록이 기여하는, 합성된 span 스타일.
 *
 * 출판사 색상과 출판사가 요청한 글꼴 패밀리는 별도로 게이트되어 있어, 리더가 선택한 글꼴이 제목
 * 굵기 같은 구조적 강조는 유지하면서도 모든 EPUB 글꼴 패밀리 스타일링은 억제할 수 있다.
 *
 * 그 억제는 책 자체 CSS가 지정한 패밀리뿐 아니라, `<pre>` 블록이 preformatted라서 얻는 monospace에도
 * 미쳐야 한다. 여기의 mono는 이 렌더러가 대신하는 브라우저 기본값이므로, 스타일시트 규칙이 그렇듯
 * 정확히 문서의 활자에 속한다: Serif를 고른 사용자는 페이지가 세리프로 설정되기를 요청한 것이고,
 * mono로 남아 있는 preformatted 블록은 여전히 그 선택을 무시하는 페이지 위 유일한 텍스트 런이다.
 * 이것은 문서 글꼴 아래에서 살아남으며, 그곳에서는 브라우저 기본값이 사용자가 보기를 요청한 것이다.
 * 굵기, 기울임, 제목 스케일은 활자체가 아니라 구조이므로 결코 게이트되지 않는다 — 제목은 어떤
 * 패밀리에서든 굵게 유지된다.
 *
 * @param block 스타일을 합성할 대상이 되는 종류와 CSS를 가진 블록.
 * @param embeddedFontFamiliesByHref 책이 함께 보낸, CSS가 참조하는 href로 키가 매겨진 서체들.
 * @param publisherColorsEnabled 책 자체의 전경 색상이 적용될지, 아니면 테마의 잉크가 이길지 여부.
 * @param publisherFontsEnabled 문서의 활자체 선택이 아예 적용될지 여부 — 사용자가 자신만의 글꼴을
 *   선택했을 때 정확히 false가 되며, 그러면 이 함수가 설정할 수 있는 모든 패밀리보다 그 선택이
 *   이겨야 한다.
 * @param emphasisWeights 제목, 표 헤더 셀, 그리고 책이 명시한 굵게 또는 명시적 비굵게가 그려지는
 *   굵기로, 리더 자체 기준 굵기에서 도출된다([readerEmphasisWeights] 참고).
 * @return 이 블록이 기여하는 스타일, 또는 아무것도 요구하지 않으면 null.
 */
private fun blockSpanStyle(
    block: ReaderBlock,
    embeddedFontFamiliesByHref: Map<String, FontFamily>,
    publisherColorsEnabled: Boolean,
    publisherFontsEnabled: Boolean,
    emphasisWeights: ReaderEmphasisWeights,
): SpanStyle? {
    val kindStyle = when (block.kind) {
        ReaderBlockKind.HEADING -> SpanStyle(fontWeight = emphasisWeights.strong, fontSize = headingScale(block.level).em)
        ReaderBlockKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
        ReaderBlockKind.PREFORMATTED -> SpanStyle(fontFamily = FontFamily.Monospace).takeIf { publisherFontsEnabled }
        ReaderBlockKind.TABLE_HEADER_CELL -> SpanStyle(fontWeight = emphasisWeights.subtle)
        else -> null
    }
    val bookStyle = block.style ?: return kindStyle
    val merged = SpanStyle(
        fontWeight = bookStyle.bold?.let { if (it) emphasisWeights.strong else emphasisWeights.base } ?: kindStyle?.fontWeight,
        fontStyle = bookStyle.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: kindStyle?.fontStyle,
        fontSize = bookStyle.fontScale?.em ?: kindStyle?.fontSize ?: TextUnit.Unspecified,
        fontFamily = bookStyle.toComposeFontFamily(embeddedFontFamiliesByHref).takeIf { publisherFontsEnabled } ?: kindStyle?.fontFamily,
        color = bookStyle.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: Color.Unspecified,
        textDecoration = bookStyle.toTextDecoration(),
    )
    return merged.takeIf { it != EmptySpanStyle }
}

/** 아무것도 요구하지 않는 스타일로, 전부 null인 병합이 아예 스타일 없음으로 보고되도록 비교 대상이 된다. */
private val EmptySpanStyle = SpanStyle()

/**
 * 출판사 강조가 리더 자체 기준 본문 굵기를 기준으로 상대적으로 그려지는 세 가지 굵기.
 *
 * 제목, 표 헤더 셀, 책이 명시한 굵은 텍스트는 절대적인 굵기가 아니라 항상 *페이지의 일반 텍스트가
 * 그려지는 굵기에 대비한* 강조다 — "굵게"를 고정된 [FontWeight.Bold]로 그리는 것이 바로 리더의
 * 기준 굵기를 600으로 올렸을 때 그 간격이 완전히 사라졌던(700 "굵게"에 대해 600 본문은 전혀 강조로
 * 읽히지 않는다) 이유이고, 300으로 내렸을 때 일반적인 강조가 불균형하게 무거워 보였던 이유다.
 * 결과 굵기가 아니라 본문에서 강조까지의 단계를 일정하게 유지하는 것이 사용자가 선택할 수 있는 모든
 * 기준에서 그 대비를 동일하게 유지하는 방법이다.
 *
 * @property strong 제목과 책이 명시한 굵은 텍스트가 그려지는 굵기.
 * @property subtle 표 헤더 셀이 그려지는 굵기 — [FontWeight.SemiBold]가 예전에 [FontWeight.Bold]
 *   아래에 있었던 것과 같은 방식으로, [strong]보다 가벼운 강조.
 * @property base 상속된 굵게를 명시적으로 취소하는 책(굵게 컨텍스트 안의 `font-weight: normal`)이
 *   귀결되는 굵기 — 더 무겁거나 가벼운 사용자 설정과 충돌할 고정된 [FontWeight.Normal]이 아니라,
 *   리더 자체 기준 굵기 그 자체다.
 */
private class ReaderEmphasisWeights(
    val strong: FontWeight,
    val subtle: FontWeight,
    val base: FontWeight,
)

/** [ReaderEmphasisWeights.strong]이 리더 자체 기준 굵기보다 얼마나 더 무겁게 그려지는지. */
private const val StrongEmphasisStep = 300

/** [ReaderEmphasisWeights.subtle]이 리더 자체 기준 굵기보다 얼마나 더 무겁게 그려지는지. */
private const val SubtleEmphasisStep = 200

/**
 * [baseFontWeight]에 대해 [ReaderEmphasisWeights]를 도출하여, 강조가 그려지는 모든 곳이 같은 두
 * 덧셈을 네 번 반복하는 대신 같은 기준으로부터 그 대비를 스케일한다.
 *
 * [ReaderDefaultFontWeight](400)에서는 오늘날의 고정된 700/600/400을 정확히 재현하므로,
 * font-weight 설정을 전혀 건드리지 않는 사용자는 이 대비가 절대값에서 상대값으로 바뀌어도 전혀
 * 변화를 보지 못한다. 리더 자체 기준 굵기는 저장되는 어디서나 300..600으로 제한되므로(`ReaderStyle`
 * 자체의 검증 참고), 셋 중 가장 무거운 [strong][ReaderEmphasisWeights.strong]을 안전하게 범위 안인
 * 600..900에 둔다; 아래의 coercion은 그 산술에 대한 방어가 아니라 `ReaderStyle` 자체의 검증을
 * 건너뛰는 호출자에 대한 방어다. [FontWeight] 자체가 1..1000만 받아들이고 그 밖의 값(coerce되지
 * 않은 [baseFontWeight]가 그대로 통과시켰을 0을 포함해)에 대해서는 예외를 던지기 때문이다.
 *
 * @param baseFontWeight 리더가 선택한 기준 본문 굵기.
 * @return 이 기준에 대해 제목, 표 헤더 셀, 책이 명시한 굵게 또는 명시적 비굵게가 각각 그려지는
 * 굵기.
 */
private fun readerEmphasisWeights(baseFontWeight: Int): ReaderEmphasisWeights {
    val validRange = 1..1000
    return ReaderEmphasisWeights(
        strong = FontWeight((baseFontWeight + StrongEmphasisStep).coerceIn(validRange)),
        subtle = FontWeight((baseFontWeight + SubtleEmphasisStep).coerceIn(validRange)),
        base = FontWeight(baseFontWeight.coerceIn(validRange)),
    )
}

/**
 * @receiver 책이 요청한 일반 패밀리.
 * @return Compose가 이름 붙인 같은 패밀리.
 */
private fun ReaderFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    ReaderFontFamily.SERIF -> FontFamily.Serif
    ReaderFontFamily.SANS_SERIF -> FontFamily.SansSerif
    ReaderFontFamily.MONOSPACE -> FontFamily.Monospace
}

/**
 * 블록이나 span의 CSS가 요청하는 글꼴 패밀리를 고르되, 로드된 것이 있으면 href로 지정된 내장
 * 서체를 우선한다.
 *
 * 출판사 글꼴을 아예 존중할지는 호출자가 게이트한다; 이 헬퍼는 사용 가능한 최선의 패밀리만
 * 해석한다.
 */
private fun ReaderBlockStyle.toComposeFontFamily(embeddedFontFamiliesByHref: Map<String, FontFamily>): FontFamily? =
    fontHref?.let(embeddedFontFamiliesByHref::get)
        ?: fontFamily?.toComposeFontFamily()
        ?: fontFamilyName.toComposeFontFamilyOrNull()

private fun String?.toComposeFontFamilyOrNull(): FontFamily? = when (this?.lowercase()) {
    null -> null
    "serif" -> FontFamily.Serif
    "sans", "sans-serif", "system-ui" -> FontFamily.SansSerif
    "mono", "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> null
}

/**
 * 블록이 설정되는 문단 수준 스타일: 들여쓰기, 정렬, 그리고 책이 요구하는 줄 높이.
 *
 * 컨테이너는 결코 이것을 갖지 않는다: 그것은 그 안 블록들에 걸쳐 있고, 겹치는 두 문단은 Compose가
 * 완전히 거부하는 것이기 때문이다. 컨테이너가 명시하는, 문단이 담을 수 있는 모든 것 — 줄 높이,
 * 정렬 — 은 상속되는 속성이므로 그 안의 블록들이 이미 스스로 그것을 담고 있다.
 *
 * 텍스트 앞의 인라인 공간은 책 자체의 것이다: `margin-left`에 `padding-left`를 더한 것으로, 이는
 * 스타일시트가 인용문, 목차, 중첩된 노트를 들여쓰는 방식이다. 책이 둘 다 명시하지 않을 때만 리더가
 * 하나를 공급하며, 그것도 오직 그렇지 않으면 읽을 수 없게 될 종류에 대해서만이다 — 목록 항목은
 * 마커를 위한 공간이, 표 셀은 이웃과 떨어질 공간이 필요하다. `text-indent`는 CSS가 둘을 합성하는
 * 방식 그대로, 첫 줄에 대해 그 인셋 위에 더해진다.
 *
 * @param block 스타일을 지정할 블록.
 * @param indentsFirstLine 이전 페이지에서 시작된 문단이면 false. 여기서 그 시작 줄은 문단의
 * 중간이므로 첫 줄 들여쓰기를 받지 않는다: 페이지 분할이 그것을 중간 줄로 측정했고, 페이지에서
 * 그것을 들여쓰면 페이지에 없는 한 줄만큼의 공간을 소모하여 마지막 줄이 아래로 밀려나게 된다.
 * @return 문단 스타일, 또는 이 블록에 대해 페이지 자체 기본값에서 벗어나는 것이 아무것도 없으면
 * null.
 */
private fun blockParagraphStyle(
    block: ReaderBlock,
    indentsFirstLine: Boolean = true,
    justifies: Boolean = true,
    lineHeightMultiplier: Float = 1f,
): ParagraphStyle? {
    val readerInset = when (block.kind) {
        ReaderBlockKind.LIST_ITEM -> block.level.coerceAtLeast(1) * 1.25f
        ReaderBlockKind.TABLE_HEADER_CELL,
        ReaderBlockKind.TABLE_CELL,
        -> 0.75f
        else -> 0f
    }
    val bookInset = block.style?.insetStartEm
        ?: ((block.style?.marginStartEm ?: 0f) + (block.style?.paddingStartEm ?: 0f))
    val inset = if (bookInset > 0f) bookInset else readerInset
    val align = when (block.align) {
        ReaderTextAlign.CENTER -> TextAlign.Center
        ReaderTextAlign.END -> TextAlign.End
        ReaderTextAlign.JUSTIFY -> if (justifies) TextAlign.Justify else TextAlign.Start
        ReaderTextAlign.START -> TextAlign.Start
        null -> null
    }
    val firstLineIndent = block.style?.textIndentEm?.takeIf { indentsFirstLine } ?: 0f
    // 책의 줄 높이는 사용자의 슬라이더를 대체하는 대신 그것에 올라타며, 슬라이더의 중립점에 고정된다:
    // 기본값에서는 블록이 책이 명시한 그대로를 그리고, 슬라이더를 움직이면 그것이 비례하여
    // 스케일된다. 원시 슬라이더 값을 그대로 곱하면 대신 스타일이 적용된 모든 책에 기본값 145%가
    // 겹겹이 곱해져 — 처음부터 책이 요구한 것보다 절반이나 더 헐거운 줄이 되었을 것이다.
    val lineHeight = block.style?.lineHeightScale
        ?.times(lineHeightMultiplier / ReaderDefaultLineHeightMultiplier)?.em
        ?: TextUnit.Unspecified
    if (inset == 0f && align == null && firstLineIndent == 0f && lineHeight == TextUnit.Unspecified) return null
    return ParagraphStyle(
        textAlign = align ?: TextAlign.Unspecified,
        textIndent = TextIndent(firstLine = (inset + firstLineIndent).em, restLine = inset.em),
        lineHeight = lineHeight,
    )
}

/**
 * 이 구간을 양쪽 정렬하면 고르게 맞춰질지, 아니면 구멍이 뚫릴지 여부.
 *
 * 여기서 양쪽 정렬은 단어 사이 공백만 늘릴 수 있는데, 이는 라틴 문자 컬럼이 흡수하도록 만들어진
 * 것이다. CJK 컬럼은 그렇지 않다: 그 줄은 문자 사이에서 끊기고, 공백은 적고 멀리 떨어져 있으며,
 * 그중 세 개를 넓혀 줄을 margin까지 밀어내면 별개의 컬럼처럼 읽힐 만큼 넓은 틈이 남는다. 책은
 * 그 늘어남이 감당할 수 있는 곳에서는 여전히 그 정렬을 얻고, 감당할 수 없는 곳에서는 — 어차피
 * 폰 너비 CJK 컬럼이 원하는 대로 — 들쭉날쭉한 가장자리로 대체된다.
 *
 * @receiver 렌더링되는 텍스트 구간.
 * @param start 그 구간을 기준으로 한, 블록의 첫 오프셋.
 * @param end 그 마지막 오프셋 바로 다음.
 * @return 블록이 단어 사이 공백을 갖지 않는 문자들에 지배되지 않을 때 true.
 */
private fun String.justifiesWell(start: Int, end: Int): Boolean {
    val from = start.coerceIn(0, length)
    val until = end.coerceIn(from, length)
    if (until <= from) return true
    var wide = 0
    var letters = 0
    for (index in from until until) {
        val char = this[index]
        if (char.isWhitespace()) continue
        letters += 1
        if (char.isWideScript()) wide += 1
    }
    if (letters == 0) return true
    return wide * 2 < letters
}

/** 이 문자가 공백이 아니라 문자 사이에서 줄이 끊기는 문자 체계에 속하는지 여부. */
private fun Char.isWideScript(): Boolean = code in 0x1100..0x11FF ||
    code in 0x2E80..0xA4CF ||
    code in 0xAC00..0xD7AF ||
    code in 0xF900..0xFAFF ||
    code in 0xFF00..0xFFEF

/**
 * 하나의 인라인 런이 그려지는 스타일로, 그것이 유래한 태그와 그 자체 CSS가 바꾸는 것으로부터
 * 나온다.
 *
 * `<code>`, `<kbd>`, `<samp>` 런이 얻는 monospace는 [blockSpanStyle]이 `<pre>` 블록의 것을 게이트하는
 * 것과 같은 이유로 [publisherFontsEnabled]에 게이트된다: 이는 이 렌더러가 문서를 대신해 공급하는
 * 브라우저 기본값이므로 구조가 아니라 문서가 요구한 활자체이며, 자신만의 글꼴을 고른 사용자는
 * 페이지의 모든 런에 대해 그것을 선택한 것이다. 여기의 그 밖의 모든 항목 — 굵기, 기울임, 장식,
 * baseline shift — 은 구조이며 어떤 패밀리에서든 적용된다.
 *
 * @param span 시맨틱 및 CSS 강조가 렌더링되어야 하는 인라인 런.
 * @param embeddedFontFamiliesByHref 책이 함께 보낸, CSS가 참조하는 href로 키가 매겨진 서체들.
 * @param publisherColorsEnabled 책 자체의 전경 색상이 이 런에 적용될지 여부.
 * @param publisherFontsEnabled 문서의 활자체 선택이 아예 적용될지 여부 — 사용자가 자신만의 글꼴을
 *   선택했을 때 정확히 false다.
 * @param emphasisWeights 굵은 텍스트 런과 책이 명시한 명시적 비굵게가 그려지는 굵기로, 리더 자체
 *   기준 굵기에서 도출된다([readerEmphasisWeights] 참고).
 * @return 이를 렌더링하는 Compose 스타일; 색상만으로는 테마 변경에서 살아남지 못하므로 링크는
 * 여기서 밑줄이 그어지고 별도의 annotation으로 href를 지닌다.
 */
private fun inlineSpanStyle(
    span: com.tedd.teddreader.core.common.model.ReaderSpan,
    embeddedFontFamiliesByHref: Map<String, FontFamily>,
    publisherColorsEnabled: Boolean,
    publisherFontsEnabled: Boolean,
    emphasisWeights: ReaderEmphasisWeights,
): SpanStyle? {
    val semanticStyle = when (span.style) {
        ReaderInlineStyle.BOLD -> SpanStyle(fontWeight = emphasisWeights.strong)
        ReaderInlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        ReaderInlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        ReaderInlineStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        ReaderInlineStyle.MONOSPACE -> SpanStyle(fontFamily = FontFamily.Monospace).takeIf { publisherFontsEnabled }
        ReaderInlineStyle.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript)
        ReaderInlineStyle.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript)
        ReaderInlineStyle.LINK -> SpanStyle(textDecoration = TextDecoration.Underline)
        null -> null
    }
    val deltaStyle = span.styleDelta?.toComposeSpanStyle(embeddedFontFamiliesByHref, publisherColorsEnabled, publisherFontsEnabled, emphasisWeights)
    return when {
        semanticStyle == null -> deltaStyle
        deltaStyle == null -> semanticStyle
        else -> semanticStyle.merge(deltaStyle)
    }
}

/**
 * [blockSpanStyle] 자체 병합과 같은 조건으로, 이 CSS delta가 요구하는 Compose span 스타일.
 *
 * @param embeddedFontFamiliesByHref 책이 함께 보낸, CSS가 참조하는 href로 키가 매겨진 서체들.
 * @param publisherColorsEnabled 책 자체의 전경 색상이 이 런에 적용될지 여부.
 * @param publisherFontsEnabled 문서의 활자체 선택이 아예 적용될지 여부.
 * @param emphasisWeights [bold]가 귀결되는 굵기 — true면 [ReaderEmphasisWeights.strong], 명시적으로
 *   false면 [ReaderEmphasisWeights.base]이며, 리더 자체 기준 굵기에서 도출된다.
 * @return 이 delta가 기술하는 Compose 스타일.
 */
private fun ReaderSpanStyle.toComposeSpanStyle(
    embeddedFontFamiliesByHref: Map<String, FontFamily>,
    publisherColorsEnabled: Boolean,
    publisherFontsEnabled: Boolean,
    emphasisWeights: ReaderEmphasisWeights,
): SpanStyle = SpanStyle(
    fontWeight = bold?.let { if (it) emphasisWeights.strong else emphasisWeights.base },
    fontStyle = italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
    // span의 em은 Compose에 의해 그 위치에서 이미 적용 중인 크기를 기준으로 해석되며, 이는 delta
    // 비율이 정확히 의미하는 것이다 — 여기서 리더의 기준으로 다시 고정하지 않는다.
    fontSize = fontScale?.em ?: TextUnit.Unspecified,
    fontFamily = toComposeFontFamily(embeddedFontFamiliesByHref).takeIf { publisherFontsEnabled },
    color = foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: Color.Unspecified,
    textDecoration = toTextDecoration(),
)

/** 블록 해석기와 같은 조건으로, 이 span delta가 요구하는 내장 또는 일반 패밀리. */
private fun ReaderSpanStyle.toComposeFontFamily(embeddedFontFamiliesByHref: Map<String, FontFamily>): FontFamily? =
    fontHref?.let(embeddedFontFamiliesByHref::get)
        ?: fontFamily?.toComposeFontFamily()
        ?: fontFamilyName.toComposeFontFamilyOrNull()

/** [ReaderBlockStyle.toTextDecoration]과 같은 조건으로, 이 span delta가 요구하는 장식. */
private fun ReaderSpanStyle.toTextDecoration(): TextDecoration? = when {
    underline == true && lineThrough == true ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    underline == true -> TextDecoration.Underline
    lineThrough == true -> TextDecoration.LineThrough
    underline == false || lineThrough == false -> TextDecoration.None
    else -> null
}

/**
 * 이 스타일이 요구하는 장식, 또는 책이 그것에 대해 아무 말도 하지 않았으면 null.
 *
 * [TextDecoration.None]은 장식을 끈 책이 얻는 것이며, `a { text-decoration: none }`이 링크가
 * 그렇지 않으면 그려졌을 밑줄을 이기게 만드는 것이 바로 이것이다.
 */
private fun ReaderBlockStyle.toTextDecoration(): TextDecoration? = when {
    underline == true && lineThrough == true ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    underline == true -> TextDecoration.Underline
    lineThrough == true -> TextDecoration.LineThrough
    underline == false || lineThrough == false -> TextDecoration.None
    else -> null
}

/**
 * [readerImageSize]가 그림이 차지한다고 말하는 박스를 정확히 예약하여, 텍스트가 그 주위에 배치하는
 * 줄이 이미지가 실제로 그려지는 줄이 되게 한다.
 *
 * @param block 공간을 예약할 그림, 표지, 또는 룰.
 * @param isStandalone 그것이 자기만의 줄을 갖는지 여부. 문장 안에 놓인 그림은 글리프처럼 텍스트
 * 자체의 중심에 놓이고, 판형 그림은 자기만의 줄 안에서 가운데 정렬된다.
 * @param lineWidthEm em 단위의 텍스트 컬럼, 또는 [DefaultImageWidthEm]으로 대체하려면 0.
 * @param maxHeightEm em 단위의 페이지 높이, 또는 [DefaultImageMaxHeightEm]으로 대체하려면 0.
 * @param emInPx 고유 픽셀 너비를 em으로 바꾸기 위한, em당 CSS 픽셀.
 * @param isFloat 이 이미지가 옆에 중첩 텍스트를 둔 플로팅된 전체 컬럼 placeholder로 렌더링되는지
 * 여부.
 * @return 예약할 박스; 어떤 호출자도 요구하지 않는 그 밖의 종류에 대해서는 1em 정사각형.
 */
private fun placeholderFor(
    block: ReaderBlock,
    isStandalone: Boolean,
    lineWidthEm: Float,
    maxHeightEm: Float,
    emInPx: Float,
    isFloat: Boolean,
): Placeholder = when (block.kind) {
    ReaderBlockKind.IMAGE,
    ReaderBlockKind.COVER_IMAGE,
    ReaderBlockKind.SEPARATOR,
    -> {
        val size = block.readerImageSize(
            columnWidthEm = lineWidthEm.takeIf { it > 0f } ?: DefaultImageWidthEm,
            maxHeightEm = maxHeightEm.takeIf { it > 0f } ?: DefaultImageMaxHeightEm,
            emInPx = emInPx,
        )
        Placeholder(
            width = (if (isFloat) lineWidthEm.takeIf { it > 0f } ?: DefaultImageWidthEm else size.widthEm).em,
            height = size.heightEm.em,
            placeholderVerticalAlign = if (isStandalone || isFloat) PlaceholderVerticalAlign.Center else PlaceholderVerticalAlign.TextCenter,
        )
    }
    else -> Placeholder(1.em, 1.em, PlaceholderVerticalAlign.Center)
}

/** 호출자가 측정된 것이 없을 때 쓰이는 컬럼 — 판형 그림이 썸네일로 줄어들지 않을 만큼 넓다. */
private const val DefaultImageWidthEm = 20f
/** 같은 조건으로, 호출자가 측정된 것이 없을 때 쓰이는 페이지 높이. */
private const val DefaultImageMaxHeightEm = 26f

/**
 * @param level 제목의 레벨. 잘못된 문서가 어떤 크기의 활자든 요구할 수 없도록 1..6으로 clamp된다.
 * @return 그 제목이 설정되는, 리더 글자 크기의 배수. 레벨이 깊어질수록 좁아진다.
 */
private fun headingScale(level: Int): Float = when (level.coerceIn(1, 6)) {
    1 -> 1.55f
    2 -> 1.4f
    3 -> 1.3f
    4 -> 1.2f
    5 -> 1.1f
    else -> 1.05f
}

/**
 * 렌더링되는 텍스트 구간에 맞춰 잘린 블록 하나.
 *
 * 의도적으로 data class가 아니다: [index]로 조회될 뿐, 필드별로 비교되는 일은 결코 없다 — span까지
 * 포함해 블록 전체를 비교하는 것이 페이지를 만드는 작업을 이차 시간으로 만든 원인이었다.
 *
 * @property index 호출자 자신의 목록 안에서 블록의 위치로, 여기서는 이것이 그 정체성이다.
 * @property block 블록 자체.
 * @property localStart 렌더링되는 구간을 기준으로, 그것이 시작하는 위치.
 * @property localEnd 같은 기준으로, 그것이 끝나는 위치.
 * @property includesStart 블록 자체의 시작이 이 구간 안에 떨어지는지 여부 — 이전 페이지에서 이어지는
 * 문단에는 false이며, 이것이 그 접두부와 첫 줄 들여쓰기를 억제하는 것이다.
 */
private class ClampedBlock(
    val index: Int,
    val block: ReaderBlock,
    val localStart: Int,
    val localEnd: Int,
    val includesStart: Boolean,
)

/**
 * 문자열이 아직 만들어지는 중이고 최종 크기가 알려지기 전, 예약된 박스.
 *
 * float 맞춤은 이 spec으로부터 최종 [ReaderPlaceholder]가 만들어지기 전에 중첩 콘텐츠를 붙이고
 * 추가 소스 오프셋을 소비할 수 있다.
 *
 * @property id 이 박스가 매칭될 inline-content 키.
 * @property kind 그 안에 무엇이 서 있는지.
 * @property href 컨테이너 안 이미지의 경로.
 * @property label 이미지의 대체 텍스트.
 * @property block 그것이 유래한 블록으로, 컬럼이 알려지면 크기를 계산할 수 있도록 보관된다.
 * @property start placeholder 문자가 렌더링된 텍스트 안에서 놓인 위치.
 */
private data class PlaceholderSpec(
    val id: String,
    val kind: ReaderBlockKind,
    val href: String?,
    val label: String?,
    val block: ReaderBlock,
    val start: Int,
    val floatContent: ReaderFloatContent? = null,
)
