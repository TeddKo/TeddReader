package com.tedd.teddreader.core.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.layoutKey
import kotlin.math.roundToInt

/**
 * 리더 페이지가 그리는 것과 같은 텍스트 레이아웃을 기반으로 하는 페이지 breaker.
 *
 * [widthPx]와 [heightPx]는 반드시 그려지는 텍스트 영역이어야 하며, 그래야 페이지가 정확히 그 안에
 * 맞는 줄들을 담는다: 이 분할은 `height / lineHeight` 계산이 아니라 측정된 줄 상자를 따르며, 이것이
 * 리더의 글자 크기나 줄 높이가 바뀔 때도 마지막 줄이 온전하게 유지되는 이유다.
 *
 * ponytail: 스타일/크기 변경마다 전체 문서를 한 번에 레이아웃한다. 이 리더가 여는 문서들에는
 * 문제없다; 어떤 책이 그 레이아웃을 너무 느리게 만들면 청크 단위 측정으로 전환한다.
 *
 * `remember` 키는 전체 스타일이 아니라 스타일의 layout key다. 색상은 `TextStyle`에 함께 실려 오므로,
 * 스타일 자체로 키를 잡으면 테마를 전환할 때마다 다른 breaker를 돌려주어 줄을 옮길 수 없는 변경에
 * 대해서도 책의 모든 페이지를 다시 측정하게 되었을 것이다. 캡처된 스타일은 만들어질 때의 색상을
 * 그대로 유지하는데, 여기서는 아무것도 그리지 않으므로 해가 없다.
 *
 * 너비나 높이가 0인 pane은 breaker가 아니라 null을 낳는다. pane이 측정되기 전에 만들어진 breaker는
 * "아무것도 측정하지 못했다"라고만 답할 수 있고, 그것을 리더에 넘기면 책 전체의 측정 기반 페이지
 * 분할을 조용히 비활성화해 버린다 — 그러면 모든 페이지가 책의 스타일시트가 요구하는 줄 높이를 알 수
 * 없는 추정값에서 나오게 된다. EPUB은 또한 참조된 모든 내장 글꼴이 해석되거나 실패할 때까지
 * 기다리므로, 첫 측정은 페이지 서피스가 그릴 것과 같은 글꼴 패밀리를 사용한다.
 *
 * 내부에는 의도적으로 다른 두 em 변환이 등장한다. 텍스트 쪽은 [LocalDensity]를 거쳐, 페이지가
 * 그려질 것과 같은 픽셀로 측정된다(EpubPageSurface 참고). 이미지 쪽은 이미지의 고유 크기가
 * density와 무관한 CSS 픽셀 단위이기 때문에, 접근성 글꼴 배율만으로 스케일된 글자 크기를 사용한다.
 *
 * 페이지는 사용 가능한 높이에서 한 줄을 남겨 둔다. 챕터는 한 번에 전체가 레이아웃되고 줄 위치로
 * 나뉜다; 각 페이지는 그 뒤 독립적으로 그려지는데, 두 레이아웃은 결코 픽셀 단위까지 일치하지
 * 않는다 — 양쪽 정렬된 텍스트, 그리고 여기서는 중간 줄이지만 저기서는 첫 줄인 시작 줄이 조금씩
 * 어긋나게 만든다 — 그래서 마지막 머리카락 굵기까지 채운 페이지는 마지막 줄의 아래쪽을 잃게 된다.
 * 한 줄은 그 대부분이 아니라 그것을 흡수하는 가장 작은 여유분이다: 딱 맞아떨어지는 자리에서는
 * 페이지가 한 줄을 손해 보지만, 잘린 줄은 독자에게 책의 한 줄을 잃게 만든다. 챕터는 제목, 인용,
 * 그림 줄이 섞여 있고 경계에 걸리는 줄이 더 클 수도 있으므로 첫 줄의 높이는 표본일 뿐이며, 그래서
 * [PageSlack]은 우연히 측정된 줄을 신뢰하는 대신 여유분을 페이지의 일정 비율로 하한을 둔다.
 *
 * 그러면 페이지는 *측정된 상자의 아래쪽*이 그 사용 가능한 높이를 넘는 첫 줄에서 나뉘며, 이는 줄
 * 상자가 균일하지 않을 때도 올바르게 유지된다.
 *
 * @param style 읽기 스타일. 페이지가 나뉘는 위치에는 그 layout key만 영향을 준다.
 * @param widthPx 픽셀 단위의 그려지는 텍스트 영역 너비 — pane이 아니라 그 여백을 뺀 pane.
 * @param heightPx 같은 기준의, 픽셀 단위 그려지는 텍스트 영역 높이.
 * @param embeddedFontFamiliesByHref href로 키가 매겨진, 페이지 서피스와 공유되는 해석된 내장 글꼴
 * 패밀리.
 * @param canMeasure 호출자가 아직 첫 측정을 신뢰할 만큼 충분한 뷰포트/글꼴 상태를 가지고 있는지
 * 여부.
 * @return 리더 자체 텍스트 레이아웃으로 측정하는 breaker, 또는 pane이 아직 실제 크기를 갖지 않거나
 * 호출자가 필요한 글꼴 해석을 여전히 기다리는 동안에는 null — 이 경우 호출자는 그 부재를 "페이지
 * 없음"으로 취급해서는 안 된다.
 */
@Composable
fun rememberReaderPageBreaker(
    style: ReaderStyle,
    widthPx: Int,
    heightPx: Int,
    embeddedFontFamiliesByHref: Map<String, androidx.compose.ui.text.font.FontFamily> = emptyMap(),
    canMeasure: Boolean = true,
): ReaderPageBreaker? {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    return remember(measurer, style.layoutKey(), widthPx, heightPx, density, embeddedFontFamiliesByHref, canMeasure) {
        if (!canMeasure || widthPx <= 0 || heightPx <= 0) return@remember null
        val inputs = readerLayoutInputs(
            style = style,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            embeddedFontFamiliesByHref = embeddedFontFamiliesByHref,
        )
        val floatFitter = readerFloatTextFitter(
            measurer = measurer,
            inputs = inputs,
            publisherColorsEnabled = false,
        )
        ReaderPageBreaker { text, blocks ->
            if (text.isEmpty()) {
                IntArray(0)
            } else {
                val semanticText = buildReaderSemanticText(
                    text = text,
                    blocks = blocks,
                    lineWidthEm = inputs.lineWidthEm,
                    maxHeightEm = inputs.maxHeightEm,
                    emInPx = inputs.emInPx,
                    embeddedFontFamiliesByHref = inputs.embeddedFontFamiliesByHref,
                    publisherColorsEnabled = false,
                    publisherFontsEnabled = inputs.publisherFontsEnabled,
                    floatTextFitter = floatFitter,
                    lineHeightMultiplier = inputs.lineHeightMultiplier,
                    baseFontWeight = inputs.fontWeight,
                )
                val layout = measurer.measure(
                    text = semanticText.annotatedString,
                    style = inputs.textStyle,
                    constraints = Constraints(maxWidth = widthPx),
                    placeholders = semanticText.placeholders.map { placeholder ->
                        AnnotatedString.Range(
                            item = placeholder.placeholder,
                            start = placeholder.start,
                            end = placeholder.end,
                        )
                    },
                )
                val starts = mutableListOf(0)
                var pageTop = layout.getLineTop(0)
                val firstLineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
                val usableHeight = heightPx - maxOf(firstLineHeight * LineSlack, heightPx * PageSlack)
                for (line in 1 until layout.lineCount) {
                    if (layout.getLineBottom(line) - pageTop > usableHeight) {
                        starts += semanticText.sourceOffsetFor(layout.getLineStart(line))
                        pageTop = layout.getLineTop(line)
                    }
                }
                starts.toIntArray()
            }
        }
    }
}

/**
 * 페이지 분할과 렌더링 양쪽에서 쓰이는 공유 float fitter를 만든다.
 *
 * 이 fitter는 플로팅된 이미지 옆에 남은 문단을 한 번 측정하여, 이미지 높이 안에 들어가는 마지막
 * 완전한 줄을 찾은 뒤, 그 소비된 접두부만을 semantic text로 다시 만든다. 콜백을 공유하는 것이
 * placeholder의 소비된 소스 범위를 breaker와 페이지 서피스에서 동일하게 유지하는 방법이다.
 */
fun readerFloatTextFitter(
    measurer: TextMeasurer,
    inputs: ReaderLayoutInputs,
    publisherColorsEnabled: Boolean,
): ReaderFloatTextFitter = { request ->
    val paragraphStart = maxOf(request.paragraphRange.start, request.imageBlock.range.end)
    val paragraphEnd = request.paragraphRange.end
    if (paragraphEnd <= paragraphStart) {
        emptyFloatPlacement(paragraphStart)
    } else {
        val availableWidthPx = (inputs.widthPx - request.imageSize.widthEm * inputs.fontPx).roundToInt().coerceAtLeast(0)
        if (availableWidthPx <= 0) {
            emptyFloatPlacement(paragraphStart)
        } else {
            val paragraphStartLocal = (paragraphStart - request.range.start).toInt()
            val paragraphEndLocal = (paragraphEnd - request.range.start).toInt()
            val paragraphText = request.text.substring(paragraphStartLocal, paragraphEndLocal)
            val paragraphRange = TextRange(paragraphStart, paragraphEnd)
            val paragraphSemantic = buildReaderSemanticText(
                text = paragraphText,
                blocks = request.blocks,
                range = paragraphRange,
                lineWidthEm = inputs.lineWidthEm,
                maxHeightEm = inputs.maxHeightEm,
                emInPx = inputs.emInPx,
                embeddedFontFamiliesByHref = inputs.embeddedFontFamiliesByHref,
                publisherColorsEnabled = publisherColorsEnabled,
                publisherFontsEnabled = inputs.publisherFontsEnabled,
                floatTextFitter = null,
                lineHeightMultiplier = inputs.lineHeightMultiplier,
                baseFontWeight = inputs.fontWeight,
            )
            if (paragraphSemantic.annotatedString.text.isEmpty()) {
                emptyFloatPlacement(paragraphStart)
            } else {
                val paragraphLayout = measurer.measure(
                    text = paragraphSemantic.annotatedString,
                    style = inputs.textStyle,
                    constraints = Constraints(maxWidth = availableWidthPx),
                    placeholders = paragraphSemantic.placeholders.map { placeholder ->
                        AnnotatedString.Range(placeholder.placeholder, placeholder.start, placeholder.end)
                    },
                )
                var lastLine = -1
                for (line in 0 until paragraphLayout.lineCount) {
                    if (paragraphLayout.getLineBottom(line) <= request.imageSize.heightEm * inputs.fontPx) lastLine = line else break
                }
                if (lastLine < 0) {
                    emptyFloatPlacement(paragraphStart)
                } else {
                    val displayEnd = paragraphLayout.getLineEnd(lastLine, visibleEnd = false)
                    val sourceEnd = paragraphSemantic.sourceOffsetFor(displayEnd)
                    val fittedRange = TextRange(paragraphStart, sourceEnd.toLong())
                    val fittedLength = (sourceEnd - paragraphStart.toInt()).coerceAtLeast(0)
                    val fittedText = buildReaderSemanticText(
                        text = paragraphText.substring(0, fittedLength.coerceAtMost(paragraphText.length)),
                        blocks = request.blocks,
                        range = fittedRange,
                        lineWidthEm = inputs.lineWidthEm,
                        maxHeightEm = inputs.maxHeightEm,
                        emInPx = inputs.emInPx,
                        embeddedFontFamiliesByHref = inputs.embeddedFontFamiliesByHref,
                        publisherColorsEnabled = publisherColorsEnabled,
                        publisherFontsEnabled = inputs.publisherFontsEnabled,
                        floatTextFitter = null,
                        lineHeightMultiplier = inputs.lineHeightMultiplier,
                        baseFontWeight = inputs.fontWeight,
                    )
                    ReaderFloatPlacement(fittedRange, fittedText)
                }
            }
        }
    }
}

/** 각 페이지에서 남겨 두는 줄 수로, 그려진 페이지가 측정된 페이지와 그만큼 달라질 수 있다. */
private const val LineSlack = 1.0f

/** 줄 높이가 제각각인 페이지를 위해, 그 여유분을 페이지 비율로 두는 하한. */
private const val PageSlack = 0.04f

private fun emptyFloatPlacement(sourceStart: Long): ReaderFloatPlacement =
    ReaderFloatPlacement(
        nestedRange = TextRange(sourceStart, sourceStart),
        nestedText = ReaderSemanticText(AnnotatedString(""), intArrayOf(sourceStart.toInt()), emptyList()),
    )
