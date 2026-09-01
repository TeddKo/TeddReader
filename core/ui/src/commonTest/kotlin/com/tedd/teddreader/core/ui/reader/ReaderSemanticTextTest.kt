package com.tedd.teddreader.core.ui.reader

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import androidx.compose.ui.text.AnnotatedString
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 페이지의 렌더링된 텍스트가 무엇을 담는지, 그리고 더해진 모든 문자가 여전히 문서로 되돌아 매핑됨을
 * 고정한다.
 *
 * 여기 케이스들은 깨졌을 때 사용자가 알아챌 렌더링의 결정들이다: 무엇이 블록을 표시하는지, 두 블록
 * 사이에 얼마의 공간이 남는지, 판형 그림이 자기만의 문단을 갖는지, 문장 안의 그림이 그 안에
 * 머무는지, 그리고 제목이 어디에 정렬되는지.
 */
class ReaderSemanticTextTest {
    /**
     * 책이 명시한 margin으로 구분되는 두 문단은 정확히 그만큼, 그리고 아무것도 그리지 않는 문자 하나로
     * 구분된다.
     *
     * 저장된 텍스트는 두 블록 사이에 빈 줄을 두는데, 그것을 그리면 책이 요구한 것과 무관하게 리더 자체
     * 줄 높이만큼의 온전한 한 줄을 비용으로 치른다 — `margin-bottom: 10px`(0.625em)를 명시한 책은 그
     * 문단들이 그것의 거의 세 배만큼 떨어져 밀려났었다. 대신 이 간격은 합쳐진 margin으로 설정된 한
     * 줄이다.
     */
    @Test
    fun aStatedMarginBecomesAGapOfExactlyThatHeight() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 0.625f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 0f),
                ),
            ),
        )

        assertEquals("First.​Second.", semantic.annotatedString.text)
        val gap = semantic.annotatedString.paragraphStyles.single { it.start == 6 && it.end == 7 }
        assertEquals(0.625.em, gap.item.lineHeight)
        assertEquals(text.indexOf("Second"), semantic.sourceOffsetFor(semantic.annotatedString.text.indexOf("Second")))
    }

    /**
     * margin을 명시하지 않는 책은 그 자체의 들여쓰기가 이미 문단을 구분하는 곳에서는 간격을 얻지 않는다.
     *
     * 첫 줄 들여쓰기가 있는 `margin: 0`은 이어지는 산문의 전형적인 설정이며, 그런데도 빈 줄을 주면 그런
     * 모든 책이 원래 길이의 약 두 배로 늘어났다.
     */
    @Test
    fun aMarginOfZeroLeavesNoGapWhereAnIndentAlreadySeparates() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 0f, textIndentEm = 1f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 0f, textIndentEm = 1f),
                ),
            ),
        )

        assertEquals("First.Second.", semantic.annotatedString.text)
        assertEquals(2, semantic.annotatedString.paragraphStyles.size)
    }

    /**
     * 책이 간격도 들여쓰기도 두지 않고 구분한 두 문단도 여전히 그것들을 떼어 놓는 가장 작은 간격을
     * 얻는다.
     *
     * 그런 책이 조판된 넓은 페이지에서는 줄 길이만으로도 문단 나눔이 읽을 만했다; 폰의 컬럼에서는 같은
     * 설정이 한 문단이 끝나고 다음 문단이 줄 중간에서 시작되는 활자 벽이 된다.
     */
    @Test
    fun paragraphsWithNeitherGapNorIndentAreStillToldApart() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 6), style = ReaderBlockStyle(marginBottomEm = 0f)),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 0f, textIndentEm = 0f),
                ),
            ),
        )

        val gap = semantic.annotatedString.paragraphStyles.single { it.start == 6 && it.end == 7 }
        assertEquals(0.35.em, gap.item.lineHeight)
    }

    /** 인접한 margin은 둘 중 더 큰 값으로 합쳐지고, 양쪽의 padding은 거기에 더해진다. */
    @Test
    fun adjacentMarginsCollapseAndPaddingAddsToThem() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 0.5f, paddingBottomEm = 0.25f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 1.5f),
                ),
            ),
        )

        val gap = semantic.annotatedString.paragraphStyles.single { it.start == 6 && it.end == 7 }
        assertEquals(1.75.em, gap.item.lineHeight)
    }

    /**
     * 인용문은 모든 줄에서 책이 준 공간만큼 들여지고, `text-indent`는 첫 줄에만 더해진다 — CSS가 둘을
     * 합성하는 방식 그대로다.
     */
    @Test
    fun aBlockIsInsetByTheSpaceTheBookGivesIt() {
        val text = "Quoted words."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.QUOTE,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(marginStartEm = 0.5f, paddingStartEm = 1f, textIndentEm = 1f),
                ),
            ),
        )

        val indent = semantic.annotatedString.paragraphStyles.single().item.textIndent
        assertEquals(2.5.em, indent?.firstLine)
        assertEquals(1.5.em, indent?.restLine)
    }
    /**
     * 더 긴 텍스트의 오프셋 8..30 구간 — 문서의 한 조각 — 을 모든 종류의 블록 하나씩과 함께 렌더링한다.
     *
     * 전체가 아니라 한 조각을 렌더링하는 것이 핵심이다: 블록들은 범위에 clamp되고, 그 앞에서 시작하는
     * 제목은 아무것도 기여하지 않으며, 그림의 placeholder는 조각 안의 위치가 아니라 여전히 문서 오프셋
     * 29..30을 보고한다.
     */
    @Test
    fun helperAddsVisibleSemanticsAndClampsInlineStyles() {
        val text = "Heading\nquote\nitem\ncode\ncell\n\n"
        val semantic = buildReaderSemanticText(
            text = text.substring(8, 30),
            range = TextRange(8, 30),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 7), level = 2),
                ReaderBlock(ReaderBlockKind.QUOTE, TextRange(8, 13)),
                ReaderBlock(ReaderBlockKind.LIST_ITEM, TextRange(14, 18), level = 2, label = "3."),
                ReaderBlock(
                    ReaderBlockKind.PREFORMATTED,
                    TextRange(19, 23),
                    spans = listOf(ReaderSpan(TextRange(19, 23), ReaderInlineStyle.MONOSPACE)),
                ),
                ReaderBlock(ReaderBlockKind.TABLE_HEADER_CELL, TextRange(24, 28)),
                ReaderBlock(ReaderBlockKind.IMAGE, TextRange(29, 30), imageHref = "images/pic.png", label = "Alt"),
            ),
        )

        assertTrue(!semantic.annotatedString.text.contains("H2 "))
        assertTrue(semantic.annotatedString.text.startsWith("quote"))
        assertTrue(semantic.annotatedString.text.contains("  3. item"))
        assertTrue(semantic.annotatedString.text.contains("cell"))
        assertEquals(1, semantic.placeholders.size)
        assertEquals("images/pic.png", semantic.placeholders.single().href)
        val placeholder = semantic.placeholders.single()
        assertEquals(
            1,
            semantic.annotatedString
                .getStringAnnotations(placeholder.start, placeholder.end)
                .count { it.item == placeholder.id },
        )
        assertEquals(29, semantic.sourceOffsetFor(placeholder.start))
        assertEquals(30, semantic.sourceOffsetFor(placeholder.end))
    }

    /**
     * 자기 블록에 홀로 있는 그림은 책이 요구한 정렬을 지닌, 자기만의 문단을 갖는다.
     *
     * 이것이 없으면 그림은 주위 산문과 줄을 공유했다 — 이것이 텍스트가 판형 그림을 가로질러 흐르게
     * 만든 원인이다 — 그리고 책이 요구한 가운데 정렬은 사라져 버렸다.
     */
    @Test
    fun aStandaloneImageIsItsOwnCentredParagraph() {
        val text = "before\n\n\nafter"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 6)),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(8, 9),
                    imageHref = "images/plate.jpg",
                    align = ReaderTextAlign.CENTER,
                ),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(9, 14)),
            ),
        )

        val placeholder = semantic.placeholders.single()
        val imageParagraph = semantic.annotatedString.paragraphStyles.single { range ->
            range.start == placeholder.start && range.end == placeholder.end
        }
        assertEquals(TextAlign.Center, imageParagraph.item.textAlign)
    }

    /**
     * 위 케이스의 거울상: `<p>앞 문장이 있고 <img/> 뒤 문장이 이어진다.</p>`.
     *
     * 그림은 문장에 속하므로 자기만의 문단을 갖지 않는다 — 하나를 주면 문장을 끊고 감싸는 문단
     * 스타일과 겹칠 것이며, 이는 `AnnotatedString`이 곧바로 거부하는 것이다. 남는 하나의 문단은 그림을
     * 함께 아우르는, 문장 자체의 것이다.
     */
    @Test
    fun aPictureWrittenInsideASentenceStaysInThatParagraph() {
        val text = "앞 문장이 있고 ￼ 뒤 문장이 이어진다."
        val imageStart = text.indexOf('￼').toLong()
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, text.length.toLong()), align = ReaderTextAlign.JUSTIFY),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(imageStart, imageStart + 1),
                    imageHref = "images/gaiji.png",
                    align = ReaderTextAlign.CENTER,
                ),
            ),
        )

        val placeholder = semantic.placeholders.single()
        assertEquals(imageStart.toInt(), placeholder.start)
        val paragraph = semantic.annotatedString.paragraphStyles.single()
        assertEquals(0, paragraph.start)
        assertEquals(text.length, paragraph.end)
        assertEquals(TextAlign.Start, paragraph.item.textAlign)
    }

    /**
     * 책의 `text-align: justify`는 양쪽 정렬이 실제로 설정될 수 있는 곳에서는 존중되고, 그럴 수 없는
     * 곳에서는 들쭉날쭉한 가장자리로 대체된다.
     *
     * 여기서 양쪽 정렬은 단어 사이 공백만 넓힐 수 있다. 라틴 문자 컬럼은 그것을 흡수하지만, CJK 컬럼은
     * 공백이 적고 대신 문자 사이에서 끊기므로, 같은 설정이 한글 산문의 모든 줄에 걸쳐 두 번째 컬럼처럼
     * 읽힐 만큼 넓은 구멍을 뚫었다.
     */
    @Test
    fun justificationIsKeptWhereTheColumnCanCarryIt() {
        fun alignmentOf(text: String) = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, text.length.toLong()), align = ReaderTextAlign.JUSTIFY),
            ),
        ).annotatedString.paragraphStyles.single().item.textAlign

        assertEquals(TextAlign.Justify, alignmentOf("Prose set in a language whose lines break at spaces."))
        assertEquals(TextAlign.Start, alignmentOf("공백이 드물고 글자 사이에서 줄이 나뉘는 본문이다."))
    }

    @Test
    fun neutralInlineCssStyleIsApplied() {
        val text = "plain soft"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    spans = listOf(
                        ReaderSpan(
                            range = TextRange(6, 10),
                            style = null,
                            styleDelta = ReaderSpanStyle(fontScale = 0.8f, italic = true),
                        ),
                    ),
                ),
            ),
        )

        val style = semantic.annotatedString.spanStyles.single { it.start == 6 && it.end == 10 }.item
        assertEquals(0.8.em, style.fontSize)
        assertEquals(FontStyle.Italic, style.fontStyle)
    }

    /**
     * 책이 스스로 정렬을 지정하지 않은 제목은 주위 산문의 정렬을 유지하며, 렌더링되는 것은 오직 그
     * 단어들뿐이다.
     *
     * 책이 결코 명시하지 않은 정렬을 결정하는 것은 책 자체의 레이아웃과 충돌할 것이고, 마커를
     * 접두하는 것은 문서에는 없는 문자를 모든 제목 앞에 두게 될 것이다.
     */
    @Test
    fun aChapterHeadingTheBookDoesNotAlignIsLeftAsTheBookWroteIt() {
        val text = "2화 기회"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, text.length.toLong()), level = 1)),
        )

        assertEquals(TextAlign.Unspecified, semantic.annotatedString.paragraphStyles.single().item.textAlign)
        assertEquals("2화 기회", semantic.annotatedString.text)
    }

    @Test
    fun floatedImageKeepsOnlyPostImageSliceInsideItsNestedPlaceholder() {
        val text = "앞 ￼Body tail"
        val imageStart = text.indexOf('￼').toLong()
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, text.length.toLong())),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(imageStart, imageStart + 1),
                    imageHref = "images/float.png",
                    float = com.tedd.teddreader.core.common.model.ReaderFloat.START,
                ),
            ),
            floatTextFitter = { request ->
                ReaderFloatPlacement(
                    nestedRange = TextRange(request.imageBlock.range.end, request.imageBlock.range.end + 4),
                    nestedText = ReaderSemanticText(
                        annotatedString = AnnotatedString("Body"),
                        offsetMap = intArrayOf(3, 4, 5, 6, 7),
                        placeholders = emptyList(),
                    ),
                )
            },
        )

        val placeholder = semantic.placeholders.single()
        assertEquals("Body", placeholder.floatContent?.text?.annotatedString?.text)
        assertTrue(semantic.annotatedString.text.startsWith("앞 ￼"))
        assertTrue(semantic.annotatedString.text.endsWith(" tail"))
        assertEquals(2, semantic.sourceOffsetFor(placeholder.start))
        assertEquals(7, semantic.sourceOffsetFor(placeholder.end))
    }

    @Test
    fun containerDecorationsAreOrderedOuterToInnerByLevel() {
        val text = "body"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    level = 2,
                    style = ReaderBlockStyle(boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF222222))),
                ),
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    level = 1,
                    style = ReaderBlockStyle(boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF111111))),
                ),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, text.length.toLong())),
            ),
            publisherColorsEnabled = true,
        )

        assertEquals(listOf(0xFF111111, 0xFF222222), semantic.containerDecorations.map { it.boxStyle.backgroundColor?.argb })
    }

    @Test
    fun containerBackgroundIsNotDuplicatedIntoBlockSpanBackground() {
        val text = "body"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF112233))),
                ),
            ),
            publisherColorsEnabled = true,
        )

        assertTrue(semantic.annotatedString.spanStyles.none { it.item.background != androidx.compose.ui.graphics.Color(0xFF112233) })
        assertEquals(1, semantic.containerDecorations.size)
    }

    @Test
    fun publisherFontAndColorStylingCanBeGatedOffForUserFontOverrides() {
        val text = "styled"
        val href = "OPS/fonts/book.otf"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(
                        fontHref = href,
                        fontFamily = com.tedd.teddreader.core.common.model.ReaderFontFamily.SERIF,
                        foregroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF112233),
                    ),
                ),
            ),
            embeddedFontFamiliesByHref = mapOf(href to androidx.compose.ui.text.font.FontFamily.Cursive),
            publisherColorsEnabled = false,
            publisherFontsEnabled = false,
        )

        assertTrue(semantic.annotatedString.spanStyles.isEmpty())
    }

    /**
     * 컨테이너는 장식만 기여할 뿐 — 결코 span은 기여하지 않는다. 그 상속된 스타일링은 파서에 의해 리프
     * 블록에 구워져 있고, 컨테이너 span은 문단 사이 폭 없는 간격 문자까지 덮었는데, 이것이 밑줄 조각과
     * 잘못된 크기의 간격이 빈 공간에 나타났던 이유다.
     */
    @Test
    fun aContainerContributesNoSpanOfItsOwn() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(
                        fontScale = 0.9f,
                        underline = true,
                        boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(
                            backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF112233),
                        ),
                    ),
                ),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 6), style = ReaderBlockStyle(marginBottomEm = 1f)),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(8, text.length.toLong())),
            ),
            publisherColorsEnabled = true,
        )

        val gapIndex = semantic.annotatedString.text.indexOf('​')
        assertTrue(gapIndex >= 0)
        // 간격 문자는 오직 자기 크기를 정의하는 span만 지닐 뿐, 어떤 블록의 스타일링도 지니지 않는다.
        assertTrue(
            semantic.annotatedString.spanStyles
                .filter { it.start <= gapIndex && gapIndex < it.end }
                .all { it.start == gapIndex && it.end == gapIndex + 1 },
        )
        // 그리고 컨테이너 자체는 text-decoration이나 font-scale span을 전혀 만들지 않았다.
        assertTrue(semantic.annotatedString.spanStyles.none { it.item.textDecoration != null })
        assertEquals(1, semantic.containerDecorations.size)
    }

    /**
     * 책의 줄 높이는 슬라이더의 중립점에 고정되어 사용자의 슬라이더에 올라탄다: 기본값에서는 블록이
     * 책이 명시한 그대로를 그리고, 슬라이더를 두 배로 하면 그것도 두 배가 된다. 슬라이더 값으로 통째로
     * 대체하면 스타일이 적용된 책에서는 그것이 죽어 버렸고; 원시 값을 그대로 곱하면 사용자가 아무것도
     * 건드리기 전부터 스타일이 적용된 모든 책이 요구한 것보다 45% 더 헐겁게 그려졌다.
     */
    @Test
    fun publisherLineHeightIsExactAtTheDefaultSliderAndScalesWithIt() {
        fun lineHeightAt(multiplier: Float) = buildReaderSemanticText(
            text = "Prose.",
            range = TextRange(0, 6),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(lineHeightScale = 1.5f),
                ),
            ),
            lineHeightMultiplier = multiplier,
        ).annotatedString.paragraphStyles.single().item.lineHeight

        assertEquals(1.5.em, lineHeightAt(com.tedd.teddreader.core.common.model.ReaderDefaultLineHeightMultiplier))
        assertEquals(
            3.em,
            lineHeightAt(com.tedd.teddreader.core.common.model.ReaderDefaultLineHeightMultiplier * 2f),
        )
    }

    /**
     * 그 범위가 우연히 유일한 자식의 범위와 일치하는 진짜 래퍼 — 제목 하나를 담은 챕터 제목 박스 — 는
     * 그 뒤 간격에 자기 padding을 유지한다. 범위가 일치하는 모든 컨테이너를 리프의 쌍둥이로 취급하면
     * 그 예약이 사라지는 반면 페인터는 여전히 그 padding만큼 박스를 키워, 박스의 아래쪽 테두리가 그
     * 아래 산문을 그대로 관통해 그려졌다.
     */
    @Test
    fun aWrapperBoxAroundASingleHeadingReservesItsOwnPadding() {
        val text = "Title\n\nProse."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, 5),
                    style = ReaderBlockStyle(
                        paddingBottomEm = 3f,
                        boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(
                            borderBottom = com.tedd.teddreader.core.common.model.ReaderBorder(widthPx = 2f),
                        ),
                    ),
                ),
                ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 5), level = 1),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(7, text.length.toLong())),
            ),
            emInPx = 16f,
        )

        val gap = semantic.annotatedString.paragraphStyles.single { it.item.lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified && it.end == it.start + 1 }
        // 제목 기본 margin(0.67)이 문단의 것(1)과 합쳐져 1이 되고, 여기에 래퍼 자체의 3em padding과
        // 2px 테두리(2/16 em)가 더해진다.
        assertEquals(1f + 3f + 2f / 16f, gap.item.lineHeight.value, 0.001f)
    }

    /**
     * 래퍼 CONTAINER 안에 박스로 감싸진 판형 그림도 여전히 자기만의 가운데 정렬된 문단을 갖는다. 예전에는
     * 래퍼가 이미지를 감싸는 텍스트 블록으로 취급되어, 판형 그림이 그 독립된 줄을 잃고 캡션이 옆에서
     * 가운데 정렬되는 동안 그것은 왼쪽으로 붙여졌다.
     */
    @Test
    fun aPlateInsideAWrapperContainerStillGetsItsCentredParagraph() {
        val text = "before\n\n￼\ncaption"
        val imageOffset = text.indexOf('￼').toLong()
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 6)),
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(imageOffset, text.length.toLong()),
                    level = 1,
                    style = ReaderBlockStyle(marginTopEm = 7f),
                ),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(imageOffset, imageOffset + 1),
                    imageHref = "logo.jpg",
                    align = ReaderTextAlign.CENTER,
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(imageOffset + 2, text.length.toLong()),
                    align = ReaderTextAlign.CENTER,
                ),
            ),
        )

        val placeholder = semantic.placeholders.single()
        val paragraph = semantic.annotatedString.paragraphStyles
            .single { it.start <= placeholder.start && placeholder.start < it.end }
        assertEquals(TextAlign.Center, paragraph.item.textAlign)
    }

    /**
     * 자체 박스 스타일링을 가진 리프 블록은 자기 장식을 스스로 그린다 — 예전에 스타일이 적용된 문단이
     * 파싱 시점의 CONTAINER 쌍둥이로부터 받던 박스가 이제는 리프에서 곧바로 나온다 — 그리고 진짜
     * 래퍼는 여전히 그 아래에 그려진다.
     */
    @Test
    fun aStyledLeafBlockPaintsItsOwnBoxDecoration() {
        val text = "Boxed."
        val leafBox = com.tedd.teddreader.core.common.model.ReaderBoxStyle(
            borderTop = com.tedd.teddreader.core.common.model.ReaderBorder(widthPx = 1f),
        )
        val wrapperBox = com.tedd.teddreader.core.common.model.ReaderBoxStyle(
            backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF112233),
        )
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    level = 1,
                    style = ReaderBlockStyle(paddingStartEm = 1f, boxStyle = wrapperBox),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(boxStyle = leafBox),
                ),
            ),
        )

        assertEquals(listOf(wrapperBox, leafBox), semantic.containerDecorations.map { it.boxStyle })
    }

    /**
     * 스타일이 적용된 문단 자체의 margin은 그 뒤 간격의 크기를 정확히 한 번만 정한다. 파서는 더 이상
     * 스타일이 적용된 리프 옆에 CONTAINER 쌍둥이를 기록하지 않으므로, 그것을 이중으로 계산할 대상이
     * 남아 있지 않다.
     */
    @Test
    fun aStyledParagraphsGapCountsItsMarginExactlyOnce() {
        // 파서는 더 이상 스타일이 적용된 리프 옆에 같은 범위·같은 스타일의 CONTAINER 쌍둥이를
        // 기록하지 않는다 — CONTAINER는 이제 항상 진짜 래퍼다 — 그래서 리프 자체의 margin은
        // blockGapEm을 통해 정확히 한 번만 간격에 반영되고, 그것을 다시 더할 대상이 없다.
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 1f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 0f),
                ),
            ),
        )

        val gap = semantic.annotatedString.paragraphStyles.single { it.start == 6 && it.end == 7 }
        assertEquals(1.em, gap.item.lineHeight)
    }

    /**
     * 어떤 span도 placeholder 문자를 덮지 않는다. placeholder의 예약된 박스는 em 단위로 명시되고,
     * Compose는 그 em을 그 위치에서 적용 중인 글꼴을 기준으로 해석한다 — `0.85em` 블록 span 안에서
     * 그림은 다른 모든 소비자가 기준 em으로 계산한 크기보다 15% 더 작게 예약되었고, 이는 float 옆에
     * 맞춰진 텍스트를 잘라 버렸다.
     */
    @Test
    fun noSpanCoversAPlaceholderCharacter() {
        val text = "before ￼ after"
        val imageOffset = text.indexOf('￼').toLong()
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(fontScale = 0.85f),
                ),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(imageOffset, imageOffset + 1),
                    imageHref = "img.png",
                ),
            ),
        )

        val placeholderIndex = semantic.placeholders.single().start
        assertTrue(
            semantic.annotatedString.spanStyles.none { it.start <= placeholderIndex && placeholderIndex < it.end },
        )
    }

    /** 파서가 누적한 인셋이 래퍼를 포함해 문단을 들여쓰는 것이다. */
    @Test
    fun theAccumulatedInsetIndentsTheParagraph() {
        val text = "Quoted."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(insetStartEm = 3f),
                ),
            ),
        )

        val paragraph = semantic.annotatedString.paragraphStyles.single()
        assertEquals(3.em, paragraph.item.textIndent?.restLine)
    }

    /**
     * 사용자가 선택한 글꼴이, 이 렌더러가 문서를 대신해 monospace로 설정하는 두 런에도 미친다.
     *
     * `<pre>` 블록과 `<code>` 런은 책의 CSS가 명시한 무언가가 아니라 이 렌더러가 대신하는 브라우저
     * 기본값에서 monospace를 가져오므로, 예전에는 출판사 글꼴 게이트가 그것들을 그냥 지나쳤다: Serif를
     * 고르면 산문은 세리프로 설정되었지만 모든 preformatted 블록과 인라인 코드 런은 mono로 남아, 여전히
     * 그 선택을 무시하는 페이지 위 유일한 텍스트가 되었다. 이제 둘 다 게이트된다. 둘 다 그 브라우저
     * 기본값이 정확히 사용자가 보기를 요청한 것인 문서 글꼴 아래에서 자신의 monospace를 유지한다.
     */
    @Test
    fun aReaderChosenFontReplacesTheMonospaceOfPreformattedAndCodeRuns() {
        val text = "prose\ncode"
        val blocks = listOf(
            ReaderBlock(ReaderBlockKind.PREFORMATTED, TextRange(0, 5)),
            ReaderBlock(
                ReaderBlockKind.PARAGRAPH,
                TextRange(6, text.length.toLong()),
                spans = listOf(ReaderSpan(TextRange(6, text.length.toLong()), ReaderInlineStyle.MONOSPACE)),
            ),
        )

        val underDocumentFont = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = blocks,
            publisherFontsEnabled = true,
        )
        assertEquals(
            2,
            underDocumentFont.annotatedString.spanStyles.count { it.item.fontFamily == FontFamily.Monospace },
        )

        val underReaderFont = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = blocks,
            publisherFontsEnabled = false,
        )
        assertTrue(underReaderFont.annotatedString.spanStyles.none { it.item.fontFamily != null })
    }

    /**
     * 사용자가 기본 기준 굵기에 있을 때 제목, 표 헤더 셀, 인라인 굵은 텍스트 런은 각각 정확히 오늘날의
     * 고정된 700/600/700로 그려진다 — 이 변경은 강조를 고정값이 아니라 그 기준에 대해 상대적으로
     * 만드는데, 이것이 픽셀 단위로 동일하게 유지되어야 하는 케이스를 고정한다: 건드리지 않은
     * font-weight 설정은 이 변경이 존재하기 전과 다르지 않게 그려져야 한다.
     *
     * 반증: 기준 위로 그려지는 강조의 단계가 강한 강조에 대해 +300에서 +200으로 약해지면 이것이
     * 실패한다 — 그러면 제목과 인라인 굵은 텍스트 런이 실제 FontWeight(600)에 대해 FontWeight(700)를
     * 단언하게 되며, 이는 정확히 그 변경을 만들고 이 테스트를 실행해 확인되었다(포착된 실패 텍스트는
     * 작업의 build-gate 리포트 참고).
     */
    @Test
    fun emphasisAtTheDefaultBaseWeightMatchesTodaysFixedWeights() {
        val text = "Heading\nCell text"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 7), level = 1),
                ReaderBlock(ReaderBlockKind.TABLE_HEADER_CELL, TextRange(8, text.length.toLong())),
            ),
        )

        val headingWeight = semantic.annotatedString.spanStyles.single { it.start == 0 }.item.fontWeight
        assertEquals(FontWeight(700), headingWeight)
        val tableHeaderWeight = semantic.annotatedString.spanStyles.single { it.start == 8 }.item.fontWeight
        assertEquals(FontWeight(600), tableHeaderWeight)

        val inlineBoldText = "bold"
        val inlineBoldSemantic = buildReaderSemanticText(
            text = inlineBoldText,
            range = TextRange(0, inlineBoldText.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, inlineBoldText.length.toLong()),
                    spans = listOf(ReaderSpan(TextRange(0, inlineBoldText.length.toLong()), ReaderInlineStyle.BOLD)),
                ),
            ),
        )
        val inlineBoldWeight = inlineBoldSemantic.annotatedString.spanStyles
            .single { it.item.fontWeight != null }.item.fontWeight
        assertEquals(FontWeight(700), inlineBoldWeight)
    }

    /**
     * font-weight 설정의 300..600 범위 양 끝에서, 강조는 사용자가 선택한 어떤 기준에 대해서도 일정한
     * 300/200 굵기 차이를 유지한다 — 더 무겁거나 가벼운 본문도 여전히 본문으로 읽히고, 제목, 표 헤더
     * 셀, 굵은 텍스트 런도 여전히 그것보다 같은 양만큼 무겁게 읽힌다.
     *
     * 반증: 헬퍼를 예전에 그렸던 고정된 [FontWeight.Bold] / [FontWeight.SemiBold]로 강조를 그리도록
     * 되돌리면 이것이 실패한다 — 그러면 기준 600이 실제 FontWeight(700)에 대해 FontWeight(900)을
     * 단언하게 되며, 이는 정확히 그 되돌림을 만들고 이 테스트를 실행해 확인되었다(포착된 실패 텍스트는
     * 작업의 build-gate 리포트 참고).
     */
    @Test
    fun emphasisContrastAgainstTheBaseStaysConstantAcrossTheWeightRange() {
        fun weightsAt(baseFontWeight: Int): Triple<FontWeight?, FontWeight?, FontWeight?> {
            val text = "Heading\nCell text"
            val semantic = buildReaderSemanticText(
                text = text,
                range = TextRange(0, text.length.toLong()),
                blocks = listOf(
                    ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 7), level = 1),
                    ReaderBlock(ReaderBlockKind.TABLE_HEADER_CELL, TextRange(8, text.length.toLong())),
                ),
                baseFontWeight = baseFontWeight,
            )
            val headingWeight = semantic.annotatedString.spanStyles.single { it.start == 0 }.item.fontWeight
            val tableHeaderWeight = semantic.annotatedString.spanStyles.single { it.start == 8 }.item.fontWeight

            val inlineBoldText = "bold"
            val inlineBoldSemantic = buildReaderSemanticText(
                text = inlineBoldText,
                range = TextRange(0, inlineBoldText.length.toLong()),
                blocks = listOf(
                    ReaderBlock(
                        ReaderBlockKind.PARAGRAPH,
                        TextRange(0, inlineBoldText.length.toLong()),
                        spans = listOf(ReaderSpan(TextRange(0, inlineBoldText.length.toLong()), ReaderInlineStyle.BOLD)),
                    ),
                ),
                baseFontWeight = baseFontWeight,
            )
            val inlineBoldWeight = inlineBoldSemantic.annotatedString.spanStyles
                .single { it.item.fontWeight != null }.item.fontWeight
            return Triple(headingWeight, tableHeaderWeight, inlineBoldWeight)
        }

        assertEquals(Triple(FontWeight(900), FontWeight(800), FontWeight(900)), weightsAt(600))
        assertEquals(Triple(FontWeight(600), FontWeight(500), FontWeight(600)), weightsAt(300))
    }

    /**
     * 상속된 굵게를 취소하기 위해 `font-weight: normal`을 명시하는 책 — 그렇지 않으면 그 자체로 강조로
     * 그려질 제목 안에서 — 은, 그 기준이 [com.tedd.teddreader.core.common.model.ReaderDefaultFontWeight]와
     * 다를 때 고정된 [FontWeight.Normal](400)이 아니라 리더 자체 기준 굵기로 귀결된다.
     *
     * 반증: 명시적 비굵게를 기준 굵기가 아니라 고정된 [FontWeight.Normal]로 귀결시키면 이것이 실패한다
     * — 그러면 기준 600이 실제 FontWeight(600)에 대해 FontWeight(400)을 단언하게 되며, 이는 정확히 그
     * 변경을 만들고 이 테스트를 실행해 확인되었다(포착된 실패 텍스트는 작업의 build-gate 리포트 참고).
     */
    @Test
    fun explicitNonBoldInsideABoldContextResolvesToTheBaseWeight() {
        val text = "Title"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.HEADING,
                    TextRange(0, text.length.toLong()),
                    level = 1,
                    style = ReaderBlockStyle(bold = false),
                ),
            ),
            baseFontWeight = 600,
        )

        assertEquals(FontWeight(600), semantic.annotatedString.spanStyles.single().item.fontWeight)
    }
}
