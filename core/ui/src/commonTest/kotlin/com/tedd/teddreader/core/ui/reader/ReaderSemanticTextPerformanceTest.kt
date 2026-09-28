package com.tedd.teddreader.core.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.isSpecified
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderBorder
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderFloat
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * semantic text의 선형·인덱스 기반 경로가 기존 렌더링 결과와 문서 오프셋 계약을 그대로 유지하는지
 * 검증한다.
 */
class ReaderSemanticTextPerformanceTest {
    /**
     * 중첩 컨테이너, 여러 간격, 인라인 그림, 그림을 가로지르는 span이 함께 있어도 렌더링 문자열과
     * 스타일·오프셋이 기존 의미를 유지한다.
     */
    @Test
    fun representativeNestedDocumentKeepsItsSemanticOutput() {
        val text = "One.\n\nTint ￼ tail\n\nLast."
        val secondStart = text.indexOf("Tint").toLong()
        val imageStart = text.indexOf('￼').toLong()
        val secondEnd = text.indexOf("\n\nLast").toLong()
        val lastStart = text.indexOf("Last").toLong()
        val inheritedColor = ReaderColor(0xFF225577)
        val semantic = buildReaderSemanticText(
            text = text,
            blocks = listOf(
                ReaderBlock(
                    kind = ReaderBlockKind.CONTAINER,
                    range = TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(foregroundColor = ReaderColor(0xFF111111)),
                ),
                ReaderBlock(
                    kind = ReaderBlockKind.CONTAINER,
                    range = TextRange(0, 4),
                    style = ReaderBlockStyle(
                        paddingBottomEm = 0.25f,
                        marginBottomEm = 0.1f,
                        boxStyle = ReaderBoxStyle(borderBottom = ReaderBorder(widthPx = 2f)),
                    ),
                ),
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(0, 4),
                    style = ReaderBlockStyle(marginBottomEm = 0.5f),
                ),
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(secondStart, secondEnd),
                    spans = listOf(
                        ReaderSpan(
                            range = TextRange(secondStart, secondEnd),
                            style = ReaderInlineStyle.UNDERLINE,
                        ),
                    ),
                    style = ReaderBlockStyle(marginTopEm = 0.75f, foregroundColor = inheritedColor),
                ),
                ReaderBlock(
                    kind = ReaderBlockKind.IMAGE,
                    range = TextRange(imageStart, imageStart + 1),
                    imageHref = "images/inline.png",
                ),
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(lastStart, text.length.toLong()),
                ),
            ),
            emInPx = 16f,
        )

        assertEquals("One.​Tint ￼ tail​Last.", semantic.annotatedString.text)
        assertEquals(listOf(1.225f, 1f), semantic.annotatedString.paragraphStyles
            .filter { it.end == it.start + 1 && it.item.lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified }
            .map { it.item.lineHeight.value })
        val placeholder = semantic.placeholders.single()
        assertEquals(inheritedColor, placeholder.foregroundColor)
        assertEquals(imageStart.toInt(), semantic.sourceOffsetFor(placeholder.start))
        assertEquals(text.length, semantic.sourceOffsetFor(semantic.annotatedString.length))
        assertTrue(
            semantic.annotatedString.spanStyles
                .filter { it.item.textDecoration == TextDecoration.Underline }
                .none { it.start <= placeholder.start && placeholder.start < it.end },
        )
    }

    /**
     * semantic text의 `Int` 오프셋 맵이 표현할 수 없는 절대 범위는 음수로 축소되지 않고 즉시
     * 거부된다.
     */
    @Test
    fun absoluteOffsetsOutsideIntRangeAreRejected() {
        val start = Int.MAX_VALUE.toLong()

        assertFailsWith<IllegalArgumentException> {
            buildReaderSemanticText(
                text = "ab",
                blocks = emptyList(),
                range = TextRange(start, start + 2),
            )
        }
    }

    /**
     * 컨테이너로 감싸인 float 그림의 본문 구간 끝이 이후 문단 간격의 끝, 시작, 중간에 떨어져도
     * 남은 문단 간격이 모두 간격 문자와 줄 높이 문단 스타일로 렌더링된다.
     */
    @Test
    fun floatSkippingPastGapStillEmitsLaterGaps() {
        val text = "\uFFFCAAAA\n\nBBBB\n\nCCCC"
        val firstGap = text.indexOf('\n')
        val secondGap = text.indexOf('\n', firstGap + 2)
        val blocks = listOf(
            ReaderBlock(ReaderBlockKind.CONTAINER, TextRange(0, text.length.toLong())),
            ReaderBlock(
                ReaderBlockKind.IMAGE,
                TextRange(0, 1),
                imageHref = "images/float.png",
                float = ReaderFloat.START,
            ),
            ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(1, firstGap.toLong())),
            ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(firstGap + 2L, secondGap.toLong())),
            ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(secondGap + 2L, text.length.toLong())),
        )
        listOf(firstGap + 2 to 1, secondGap to 1, firstGap + 1 to 2).forEach { (landing, expectedGaps) ->
            val semantic = buildReaderSemanticText(
                text = text,
                blocks = blocks,
                emInPx = 16f,
                floatTextFitter = {
                    ReaderFloatPlacement(
                        nestedRange = TextRange(1, landing.toLong()),
                        nestedText = ReaderSemanticText(AnnotatedString(""), intArrayOf(0), emptyList()),
                    )
                },
            )

            assertEquals(expectedGaps, semantic.annotatedString.text.count { it == '\u200B' }, "landing=$landing")
            assertEquals(
                expectedGaps,
                semantic.annotatedString.paragraphStyles.count { it.end - it.start == 1 && it.item.lineHeight.isSpecified },
                "landing=$landing",
            )
        }
    }
}
