package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.ReaderBorder
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderFontFamily
import com.tedd.teddreader.core.common.model.ReaderFloat
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.standaloneBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [parseXhtmlContent]의 마크업-텍스트-블록 변환 계약을 고정한다: 문단/헤딩/목록/테이블 구조,
 * 인라인 스팬과 링크, 앵커, 이미지 배치(인라인 vs. 독립)와 크기, 엔티티 디코딩, 잘못된
 * 마크업(닫히지 않은 태그, script/style 본문, `<svg>`로 감싼 그림)에 대한 관용성. 이 리더의
 * EPUB 렌더링 회귀는 모두 이 파일로 거슬러 올라간다.
 */
class EpubXhtmlParserTest {
    /**
     * 순진하게 태그만 벗겨내면 이어붙은 한 줄이 되어버리는 대신, 두 문단은 텍스트의 별개인 두
     * 줄로 남는다.
     */
    @Test
    fun paragraphsStayApartInsteadOfCollapsingIntoOneLine() {
        val content = parseXhtmlContent(
            """
            <html><body>
              <p>First paragraph.</p>
              <p>Second paragraph.</p>
            </body></html>
            """.trimIndent(),
        )

        assertEquals("First paragraph.\n\nSecond paragraph.", content.text)
        assertEquals(
            listOf(ReaderBlockKind.PARAGRAPH, ReaderBlockKind.PARAGRAPH),
            content.blocks.map { it.kind },
        )
    }

    /** 모든 블록의 범위는 그것이 설명하는 평탄화된 텍스트의 부분 문자열을 정확히 가리킨다. */
    @Test
    fun everyBlockRangeIndexesTheFlattenedTextItDescribes() {
        val content = parseXhtmlContent(
            "<h1>Title</h1><p>Body text here.</p><blockquote>Quoted line.</blockquote>",
        )

        val texts = content.blocks.map { block ->
            content.text.substring(block.range.start.toInt(), block.range.end.toInt())
        }
        assertEquals(listOf("Title", "Body text here.", "Quoted line."), texts)
    }

    /** 헤딩의 숫자 레벨(`h2` -> 2, `h5` -> 5)이 그 블록에 실린다. */
    @Test
    fun headingsCarryTheirLevel() {
        val content = parseXhtmlContent("<h2>Chapter</h2><h5>Aside</h5>")

        assertEquals(listOf(2, 5), content.blocks.map { it.level })
        assertTrue(content.blocks.all { it.kind == ReaderBlockKind.HEADING })
    }

    /** 볼드와 이탤릭 인라인 마크업은 정확히 그것이 감싼 문자들 위의 스팬이 된다. */
    @Test
    fun inlineMarkupBecomesSpansOverTheRightCharacters() {
        val content = parseXhtmlContent("<p>plain <b>bold</b> and <i>italic</i></p>")

        val spanned = content.blocks.single().spans.map { span ->
            span.style to content.text.substring(span.range.start.toInt(), span.range.end.toInt())
        }
        assertEquals(
            listOf(ReaderInlineStyle.BOLD to "bold", ReaderInlineStyle.ITALIC to "italic"),
            spanned.sortedBy { it.second },
        )
    }

    /** `href`를 가진 앵커는 그 대상을 실은 링크 스팬이 된다. */
    @Test
    fun anchorBecomesALinkSpanCarryingItsTarget() {
        val content = parseXhtmlContent("""<p>see <a href="ch2.xhtml">chapter two</a></p>""")

        val span = content.blocks.single().spans.single()
        assertEquals(ReaderInlineStyle.LINK, span.style)
        assertEquals("ch2.xhtml", span.href)
        assertEquals("chapter two", content.text.substring(span.range.start.toInt(), span.range.end.toInt()))
    }

    /** `href`가 없는 앵커(링크가 아니라 이름 붙은 앵커 지점)는 스팬을 전혀 추가하지 않는다. */
    @Test
    fun anchorWithoutTargetAddsNoSpan() {
        val content = parseXhtmlContent("""<p>anchor <a id="p12"></a>only</p>""")

        assertEquals(emptyList(), content.blocks.single().spans)
        assertEquals("anchor only", content.text)
    }

    /**
     * 순서 있는 목록과 순서 없는 목록 항목은 각각 올바른 깊이를 가지며, 순서 있는 항목만 항목마다
     * 증가하는 숫자 마커를 가진다.
     */
    @Test
    fun listItemsKeepDepthAndOrderedMarkers() {
        val content = parseXhtmlContent("<ol start=\"3\"><li>third</li><li>fourth</li></ol><ul><li>bullet</li></ul>")

        assertEquals(
            listOf(ReaderBlockKind.LIST_ITEM, ReaderBlockKind.LIST_ITEM, ReaderBlockKind.LIST_ITEM),
            content.blocks.map { it.kind },
        )
        assertEquals(listOf("3.", "4.", null), content.blocks.map { it.label })
        assertTrue(content.blocks.all { it.level == 1 })
    }

    /** 다른 목록 항목 안에 중첩된 목록은 항목의 깊이를 부모보다 하나 더 올린다. */
    @Test
    fun nestedListsRaiseTheDepth() {
        val content = parseXhtmlContent("<ul><li>outer<ul><li>inner</li></ul></li></ul>")

        assertEquals(listOf(1, 2), content.blocks.map { it.level })
    }

    /**
     * 이름 있는 앵커(`id`)는 절대 오프셋에서 포착되며, 0이 아닌 기준 오프셋만큼 올바르게
     * 이동된다.
     */
    @Test
    fun anchorsCaptureNamedIdsAtAbsoluteOffsets() {
        val content = parseXhtmlContent("""<h1 id="top">Title</h1><p><a id="scene"></a>Body</p>""", baseOffset = 10)

        assertEquals(10L, content.anchors["top"])
        assertEquals(17L, content.anchors["scene"])
    }

    /**
     * 이미지는 `src`가 자기 챕터 자체의 컨테이너 경로를 기준으로 해석되는 독립 블록이 되며,
     * 페이지 경계에서도 블록을 참조할 수 있도록 한 글자 범위를 갖는다.
     */
    @Test
    fun imageBecomesAStandaloneBlockWithAResolvedPath() {
        val content = parseXhtmlContent(
            xhtml = """<p>before</p><img src="../Images/plate.jpg" alt="Plate 1"/><p>after</p>""",
            resolveImageHref = { source -> resolveContainerHref("OEBPS/Text/ch1.xhtml", source) },
        )

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals("OEBPS/Images/plate.jpg", image.imageHref)
        assertEquals("Plate 1", image.label)
        assertEquals(ReaderTextAlign.CENTER, image.align)
        assertEquals(1L, image.range.end - image.range.start)
    }

    /**
     * 회귀 방지: 독립 블록의 범위는 이미지가 챕터의 맨 마지막 요소일 때도 [parseXhtmlContent]가
     * 반환하는 텍스트 범위 안에 머문다.
     */
    @Test
    fun trailingStandaloneBlockStaysInsideReturnedTextRange() {
        val content = parseXhtmlContent("""<p>before</p><img src="plate.jpg" alt="Plate 1"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertTrue(image.range.end <= content.text.length.toLong())
        assertTrue(image in content.blocks.blocksIn(0, content.text.length.toLong()))
    }

    /**
     * 이미지의 `width`/`height` 속성이 둘 다 순수 픽셀 숫자로 주어지면 올바른 종횡비를 만든다.
     */
    @Test
    fun imageCarriesTheAspectRatioDeclaredInWidthAndHeightAttributes() {
        val content = parseXhtmlContent("""<img src="plate.jpg" width="800" height="400"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals(2f, image.imageAspectRatio)
    }

    /**
     * 이미지의 종횡비는 인라인 `style` 속성의 픽셀 치수를 통해서도 선언될 수 있다.
     */
    @Test
    fun imageCarriesTheAspectRatioDeclaredInAnInlineStyle() {
        val content = parseXhtmlContent("""<img src="plate.jpg" style="width:300px;height:600px"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals(0.5f, image.imageAspectRatio)
    }

    /**
     * 치수가 아예 없거나 고정 픽셀 크기가 아니라 퍼센트로 주어지면 종횡비를 추측하지 않는다.
     */
    @Test
    fun imageHasNoAspectRatioWhenDimensionsAreUnspecifiedOrPercentages() {
        val undeclared = parseXhtmlContent("""<img src="plate.jpg"/>""")
        val percentage = parseXhtmlContent("""<img src="plate.jpg" width="100%" height="200"/>""")

        assertEquals(null, undeclared.blocks.single { it.kind == ReaderBlockKind.IMAGE }.imageAspectRatio)
        assertEquals(null, percentage.blocks.single { it.kind == ReaderBlockKind.IMAGE }.imageAspectRatio)
    }

    @Test
    fun floatingInlineImageStaysInlineWithPublisherFloatAndResolvedNestedWidth() {
        val content = parseXhtmlContent(
            """
            <p class="body"><span style="float:left;display:inline-block;width:8.000em"><img src="plate.jpg" style="width:90%;border:3px solid #011689;border-radius:50%" alt="Plate 1"/></span>Body text</p>
            """.trimIndent(),
            css = EpubCss.parse(listOf(".body{font-size:.85em;font-style:italic;}")),
        )

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        val paragraph = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }

        assertEquals(ReaderTextAlign.START, image.align)
        assertEquals(ReaderFloat.START, image.float)
        assertEquals(7.2f, requireNotNull(image.imageWidthEm), absoluteTolerance = 0.0001f)
        assertEquals(
            ReaderBlockStyle(
                boxStyle = ReaderBoxStyle(
                    borderTop = ReaderBorder(widthPx = 3f, color = ReaderColor(0xFF011689)),
                    borderRight = ReaderBorder(widthPx = 3f, color = ReaderColor(0xFF011689)),
                    borderBottom = ReaderBorder(widthPx = 3f, color = ReaderColor(0xFF011689)),
                    borderLeft = ReaderBorder(widthPx = 3f, color = ReaderColor(0xFF011689)),
                    borderRadiusPercent = 50f,
                ),
            ),
            image.style,
        )
        assertEquals("￼Body text", content.text.substring(paragraph.range.start.toInt(), paragraph.range.end.toInt()))
        assertTrue(image.range.start >= paragraph.range.start && image.range.end <= paragraph.range.end)
        assertEquals(ReaderBlockStyle(fontScale = 0.85f, italic = true), paragraph.style)
        assertEquals(emptyList(), content.blocks.standaloneBlocks())
    }

    @Test
    fun floatingInlineImageCanCarryRightFloatToo() {
        val content = parseXhtmlContent("""<p><span style="float:right;width:8em"><img src="plate.jpg" style="width:90%"/></span>Body</p>""")

        assertEquals(ReaderTextAlign.END, content.blocks.single { it.kind == ReaderBlockKind.IMAGE }.align)
        assertEquals(ReaderFloat.END, content.blocks.single { it.kind == ReaderBlockKind.IMAGE }.float)
    }

    @Test
    fun inlineWidthOverridesLinkedCssWidthWhileKeepingAncestorClassWidthAsItsBase() {
        val stylesheetCss = """
            .frame{width:6em;}
            .frame img{width:50%;}
            """.trimIndent()
        val content = parseXhtmlContent(
            xhtml = """<p><span class="frame"><img src="plate.jpg" style="width:90%"/></span></p>""",
            css = EpubCss.parse(listOf(stylesheetCss)),
        )

        assertEquals(
            5.4f,
            requireNotNull(content.blocks.single { it.kind == ReaderBlockKind.IMAGE }.imageWidthEm),
            absoluteTolerance = 0.0001f,
        )
    }

    /**
     * 회귀 방지: `<svg><image xlink:href="..."/></svg>` — Sigil/Calibre가 전면 삽화나 커버를
     * 뷰포트에 맞게 스케일되도록 흔히 감싸는 방식 — 은 script/style 본문처럼 버려지지 않고
     * 여전히 그림으로 포착되어야 한다.
     */
    @Test
    fun svgWrappedImageIsStillCapturedInsteadOfBeingDropped() {
        val content = parseXhtmlContent(
            xhtml = """<body><svg viewBox="0 0 600 800"><image width="600" height="800" xlink:href="../Images/plate.jpg"/></svg></body>""",
            resolveImageHref = { source -> resolveContainerHref("OEBPS/Text/ch1.xhtml", source) },
        )

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals("OEBPS/Images/plate.jpg", image.imageHref)
    }

    /**
     * 참조를 해석할 수 없는 이미지(이 리더가 가져올 수 없는 원격 URL)는 완전히 버려지고,
     * 주변 텍스트는 온전히 남는다.
     */
    @Test
    fun imageIsDroppedWhenItCannotBeResolved() {
        val content = parseXhtmlContent(
            xhtml = """<img src="https://example.com/remote.png"/><p>text</p>""",
            resolveImageHref = { source -> resolveContainerHref("ch1.xhtml", source) },
        )

        assertTrue(content.blocks.none { it.kind == ReaderBlockKind.IMAGE })
    }

    /** 테이블 셀은 `<tr>`/`<td>`/`<th>` 태그가 열리며 쌓인 행/열 위치를 싣는다. */
    @Test
    fun tableCellsCarryTheirGridPosition() {
        val content = parseXhtmlContent(
            "<table><tr><th>Name</th><th>Value</th></tr><tr><td>a</td><td>1</td></tr></table>",
        )

        assertEquals(
            listOf(
                Triple(ReaderBlockKind.TABLE_HEADER_CELL, 0, 0),
                Triple(ReaderBlockKind.TABLE_HEADER_CELL, 0, 1),
                Triple(ReaderBlockKind.TABLE_CELL, 1, 0),
                Triple(ReaderBlockKind.TABLE_CELL, 1, 1),
            ),
            content.blocks.map { Triple(it.kind, it.tableRow, it.tableColumn) },
        )
    }

    /**
     * `<pre>` 콘텐츠는 보통의 붕괴된 텍스트와 달리 자기 자신의 공백과 줄바꿈을 그대로 유지한다.
     */
    @Test
    fun preformattedTextKeepsItsOwnWhitespace() {
        val content = parseXhtmlContent("<pre>line one\n    indented</pre>")

        val block = content.blocks.single()
        assertEquals(ReaderBlockKind.PREFORMATTED, block.kind)
        assertEquals("line one\n    indented", content.text)
    }

    /**
     * 문단 안에서 이어지는 공백은 마크업 공백이 보통 읽히는 방식대로 하나의 공백으로 붕괴된다.
     */
    @Test
    fun runsOfWhitespaceInsideAParagraphCollapseLikeMarkupSays() {
        val content = parseXhtmlContent("<p>spaced   out\n   words</p>")

        assertEquals("spaced out words", content.text)
    }

    /**
     * 문단 안의 `<br/>`는 문단을 두 블록으로 나누지 않고 텍스트 안의 실제 줄바꿈이 된다.
     */
    @Test
    fun lineBreakSurvivesInsideAParagraph() {
        val content = parseXhtmlContent("<p>first<br/>second</p>")

        assertEquals("first\nsecond", content.text)
        assertEquals(1, content.blocks.size)
    }

    /** `<script>`와 `<style>` 안의 텍스트는 평탄화된 출력에 절대 도달하지 않는다. */
    @Test
    fun scriptAndStyleBodiesNeverReachTheText() {
        val content = parseXhtmlContent(
            """
            <html><head><style>p { color: red }</style></head>
            <body><script>var hidden = 1;</script><p>visible</p></body></html>
            """.trimIndent(),
        )

        assertEquals("visible", content.text)
    }

    /** 자체 종료된 `<head/>`는 그 뒤에 오는 `<body>`를 삼키지 않는다. */
    @Test
    fun selfClosingHeadDoesNotDiscardBody() {
        val content = parseXhtmlContent("<html><head/><body><p>Body</p></body></html>")

        assertEquals("Body", content.text)
        assertEquals(listOf(ReaderBlockKind.PARAGRAPH), content.blocks.map { it.kind })
    }

    /**
     * 이름 있는(`&ldquo;`) 엔티티 참조와 숫자로 된(`&#160;`, `&#x1F600;`) 엔티티 참조가
     * 디코딩된다, 서로게이트 쌍 이모지를 포함해서.
     */
    @Test
    fun namedAndNumericEntitiesAreDecoded() {
        assertEquals(
            "“quoted” — a b & 'c' ½ 😀",
            decodeXmlEntities("&ldquo;quoted&rdquo; &mdash; a&#160;b &amp; &apos;c&apos; &frac12; &#x1F600;"),
        )
    }

    /**
     * 인식되지 않는 엔티티 이름은 주변 텍스트를 먹어치우는 대신 문자 그대로의 텍스트로
     * 남는다(`&`, 이름, `;`가 그대로 유지됨).
     */
    @Test
    fun unknownEntityIsLeftAloneInsteadOfEatingText() {
        assertEquals("a &notanentity; b", decodeXmlEntities("a &notanentity; b"))
    }

    /**
     * 0이 아닌 기준 오프셋은 모든 블록의 범위를 같은 양만큼 이동시켜, 오프셋을 다시 계산하지
     * 않고도 챕터들이 이어붙여지게 한다.
     */
    @Test
    fun baseOffsetShiftsEveryRangeSoChaptersCanBeConcatenated() {
        val first = parseXhtmlContent("<p>one</p>")
        val second = parseXhtmlContent("<p>two</p>", baseOffset = first.text.length + 1L)

        assertEquals(0L, first.blocks.single().range.start)
        assertEquals(first.text.length + 1L, second.blocks.single().range.start)
    }

    /** 인라인 요소 안에 공백만 있는 마크업은 텍스트도 블록도 전혀 만들어내지 않는다. */
    @Test
    fun markupWithNoReadableTextProducesNoBlocks() {
        val content = parseXhtmlContent("<div><span> </span></div>")

        assertEquals("", content.text)
        assertEquals(emptyList(), content.blocks)
    }

    /**
     * 블록 끝에서 닫히지 않고 남은 인라인 요소는 다음 블록으로 새어나가지 않고 그 블록 자체
     * 끝까지의 스팬으로 기록된다.
     */
    @Test
    fun unclosedInlineMarkupEndsWithItsBlock() {
        val content = parseXhtmlContent("<p>start <b>bold to the end</p><p>next</p>")

        val span = content.blocks.first().spans.single()
        assertEquals("bold to the end", content.text.substring(span.range.start.toInt(), span.range.end.toInt()))
        assertEquals(emptyList(), content.blocks[1].spans)
    }

    /** 인라인 `style="text-align: center"`는 블록 자체의 정렬로 인식된다. */
    @Test
    fun inlineStyleAlignmentIsPickedUp() {
        val content = parseXhtmlContent("""<p style="text-align: center">middle</p><p>plain</p>""")

        assertEquals(ReaderTextAlign.CENTER, content.blocks.first().align)
        assertNull(content.blocks[1].align)
    }

    /**
     * 회귀 방지: 파트와 챕터 헤딩은 흔히 그림으로 설정되며, 읽을 수 있는 이름은 오직 헤딩의
     * `title` 속성에만 있다(`<h1 title="..."><img/></h1>`). 그림이 곧 헤딩이므로 제목은
     * [XhtmlContent.headingTitle]로 남고, 그 곁에 별도의 빈 헤딩 블록이 기록되지 않는다.
     */
    @Test
    fun aHeadingThatIsOnlyAPictureKeepsItsNameAndDropsTheEmptyHeadingBlock() {
        val content = parseXhtmlContent(
            xhtml = """<h1 title="1화 기회"><img src="../Images/title.png"/></h1><p>본문</p>""",
            resolveImageHref = { source -> resolveContainerHref("OEBPS/Text/ch1.xhtml", source) },
        )

        assertEquals("1화 기회", content.headingTitle)
        assertTrue(content.blocks.none { it.kind == ReaderBlockKind.HEADING })
        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals("OEBPS/Images/title.png", image.imageHref)
        assertTrue(image in content.blocks.standaloneBlocks())
    }

    @Test
    fun neutralInlineSpanCarriesClassAndInlineCssIntoReaderSpan() {
        val content = parseXhtmlContent(
            xhtml = """<p>plain <span class="soft" style="font-size:.8em">soft</span></p>""",
            css = EpubCss.parse(listOf(".soft{font-style:italic;}")),
        )

        val span = content.blocks.single().spans.single()
        assertEquals(null, span.style)
        assertEquals(ReaderSpanStyle(fontScale = 0.8f, italic = true), span.styleDelta)
        assertEquals("soft", content.text.substring(span.range.start.toInt(), span.range.end.toInt()))
    }

    /**
     * 회귀 방지: 한 문장 안에 인라인으로 쓰인 두 그림 모두, 자기만의 줄로 뜯겨나가지 않고 그
     * 문장의 문단 안에 머문다.
     */
    @Test
    fun twoPicturesInOneSentenceBothStayInIt() {
        val content = parseXhtmlContent("""<p>가<img src="a.png"/>나<img src="b.png"/>다</p>""")

        val paragraph = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        val images = content.blocks.filter { it.kind == ReaderBlockKind.IMAGE }
        assertEquals(2, images.size)
        assertTrue(images.all { it.range.start > paragraph.range.start && it.range.end < paragraph.range.end })
        assertEquals(emptyList(), content.blocks.standaloneBlocks())
    }

    @Test
    fun hiddenSubtreeAndPublisherContainerStylesArePreserved() {
        val content = parseXhtmlContent(
            xhtml = """
            <html class="page"><body class="page"><div class="box_content">
              <p class="chap">숨김</p>
              <p class="title">보임</p>
            </div></body></html>
            """.trimIndent(),
            css = EpubCss.parseSources(
                listOf(
                    CssStyleSheetSource(
                        path = "OPS/css/book.css",
                        css = """
                        @font-face { font-family: 'KoPub'; src: url('../fonts/KoPub.otf'); }
                        .chap { display: none; }
                        .title { color: #011689; font-family: 'KoPub', serif; }
                        html.page, body.page { background-color: rgba(255,255,255,0); }
                        .box_content { border-top: 2px solid #011689; border-bottom: 2px solid #011689; background-color: transparent; }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        assertEquals("보임", content.text)
        val paragraph = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(
            ReaderBlockStyle(
                fontFamily = ReaderFontFamily.SERIF,
                fontFamilyName = "KoPub",
                fontHref = "OPS/fonts/KoPub.otf",
                foregroundColor = ReaderColor(0xFF011689),
            ),
            paragraph.style,
        )
        val containers = content.blocks.filter { it.kind == ReaderBlockKind.CONTAINER }
        assertEquals(3, containers.size)
        val pageContainers = containers.filter { it.isPageContainer }.sortedBy { it.level }
        val boxContainer = containers.single { !it.isPageContainer }
        assertEquals(listOf(1, 2), pageContainers.map { it.level })
        assertEquals(3, boxContainer.level)
        assertEquals(
            ReaderBlockStyle(
                boxStyle = ReaderBoxStyle(backgroundColor = ReaderColor(0x00FFFFFF)),
            ),
            pageContainers.first().style,
        )
        assertEquals(
            ReaderBlockStyle(
                boxStyle = ReaderBoxStyle(
                    backgroundColor = ReaderColor(0x00000000),
                    borderTop = ReaderBorder(widthPx = 2f, color = ReaderColor(0xFF011689)),
                    borderBottom = ReaderBorder(widthPx = 2f, color = ReaderColor(0xFF011689)),
                ),
            ),
            boxContainer.style,
        )
    }

    @Test
    fun styledParagraphDoesNotCreateAnExtraContainerBlock() {
        val content = parseXhtmlContent(
            xhtml = """<p class="title">보임</p>""",
            css = EpubCss.parseSources(
                listOf(
                    CssStyleSheetSource(
                        path = "OPS/css/book.css",
                        css = """
                        @font-face { font-family: 'KoPub'; src: url('../fonts/KoPub.otf'); }
                        .title { color: red; font-family: 'KoPub', serif; }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        assertEquals(listOf(ReaderBlockKind.PARAGRAPH), content.blocks.map { it.kind })
    }

    @Test
    fun nestedStyledHtmlAndBodyEachStayAsPageContainersInDepthOrder() {
        val content = parseXhtmlContent(
            xhtml = """<html class="page"><body class="page"><p>보임</p></body></html>""",
            css = EpubCss.parse(listOf(".page { background-color: transparent; }")),
        )

        val containers = content.blocks.filter { it.kind == ReaderBlockKind.CONTAINER }
        assertEquals(2, containers.size)
        assertEquals(listOf(1, 2), containers.map { it.level }.sorted())
        assertEquals(listOf(true, true), containers.map { it.isPageContainer })
        assertEquals(
            containers.map { it.range },
            List(2) { content.blocks.single { block -> block.kind == ReaderBlockKind.PARAGRAPH }.range },
        )
    }

    @Test
    fun linkedBodyStylesInheritIntoParagraphAndSpan() {
        val content = parseXhtmlContent(
            xhtml = """<html><body class="page"><p><span>보임</span></p></body></html>""",
            css = EpubCss.parseSources(
                listOf(
                    CssStyleSheetSource(
                        path = "OPS/css/book.css",
                        css = """
                        @font-face { font-family: 'KoPub'; src: url('../fonts/KoPub.otf'); }
                        body.page { color: #011689; font-family: 'KoPub', serif; }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val paragraph = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        val expectedStyle = ReaderBlockStyle(
            fontFamily = ReaderFontFamily.SERIF,
            fontFamilyName = "KoPub",
            fontHref = "OPS/fonts/KoPub.otf",
            foregroundColor = ReaderColor(0xFF011689),
        )
        assertEquals(expectedStyle, paragraph.style)
        // 스팬은 자기 문단과 다른 점이 전혀 없으므로 아무것도 싣지 않는다: 상속된 스타일링은
        // 모든 중첩 요소마다 다시 나오지 않고 블록에 한 번만 있다 — 스팬이 그것을 반복하면
        // 렌더러가 상대값을 두 번 적용해버렸다.
        assertEquals(emptyList(), paragraph.spans)
    }

    @Test
    fun linkCanExplicitlyInheritParentEmbeddedFontAndColor() {
        val content = parseXhtmlContent(
            xhtml = """<html><body class="page"><p><a href="next.xhtml">보임</a></p></body></html>""",
            css = EpubCss.parseSources(
                listOf(
                    CssStyleSheetSource(
                        path = "OPS/css/book.css",
                        css = """
                        @font-face { font-family: 'KoPub'; src: url('../fonts/KoPub.otf'); }
                        body.page { color: #011689; font-family: 'KoPub', serif; }
                        a { font-family: inherit !important; color: inherit !important; }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val block = content.blocks.single()
        val link = block.spans.single()
        // `inherit`은 부모 자체의 값으로 해석되며, 문단이 이미 그것을 싣고 있다 — 그래서 그것에
        // 대한 링크의 델타는 비어 있고 스타일링은 블록을 통해 링크에 도달한다.
        assertEquals(
            ReaderBlockStyle(
                fontFamily = ReaderFontFamily.SERIF,
                fontFamilyName = "KoPub",
                fontHref = "OPS/fonts/KoPub.otf",
                foregroundColor = ReaderColor(0xFF011689),
            ),
            block.style,
        )
        assertEquals(ReaderInlineStyle.LINK, link.style)
        assertEquals(null, link.styleDelta)
    }

    @Test
    fun rgbaAndCommonNamedColorsDecodeToReaderColors() {
        val content = parseXhtmlContent(
            xhtml = """<p class="title">보임</p>""",
            css = EpubCss.parse(
                listOf(
                    ".title { color: #011689cc; background-color: blue; border-bottom: 2px solid gray; }",
                ),
            ),
        )

        assertEquals(
            ReaderBlockStyle(
                foregroundColor = ReaderColor(0xCC011689),
                boxStyle = ReaderBoxStyle(
                    backgroundColor = ReaderColor(0xFF0000FF),
                    borderBottom = ReaderBorder(widthPx = 2f, color = ReaderColor(0xFF808080)),
                ),
            ),
            content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style,
        )
    }

    /**
     * 스타일시트의 간격과 장식이 그것으로부터 그려지는 블록에 도달한다.
     *
     * 이것들은 리플로우 가능한 책이 가장 크게 의존하는 선언들이다 — 문단 간격, 인용문의 들여쓰기,
     * 밑줄 없는 링크 — 그리고 이 모두가 예전에는 스타일시트와 페이지 사이에서 누락되었다.
     */
    @Test
    fun spacingAndDecorationReachTheBlocksTheyStyle() {
        val content = parseXhtmlContent(
            """
            <html><body>
              <p class="txt">Prose.</p>
              <blockquote>Quoted.</blockquote>
              <p><a href="target.xhtml">Link</a></p>
            </body></html>
            """.trimIndent(),
            css = EpubCss.parse(
                listOf(
                    """
                    .txt { margin: 0 0 10px 0; text-indent: 1em }
                    blockquote { padding: 1em 0 1em 1.5em; margin: 0 }
                    a:link { text-decoration: none }
                    """.trimIndent(),
                ),
            ),
        )

        val prose = content.blocks.first { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(0f, prose.style?.marginTopEm)
        assertEquals(0.625f, prose.style?.marginBottomEm)
        assertEquals(1f, prose.style?.textIndentEm)

        val quote = content.blocks.first { it.kind == ReaderBlockKind.QUOTE }
        assertEquals(1.5f, quote.style?.paddingStartEm)
        assertEquals(1f, quote.style?.paddingTopEm)
        assertEquals(0f, quote.style?.marginBottomEm)

        val link = content.blocks.flatMap { it.spans }.first { it.style == ReaderInlineStyle.LINK }
        assertEquals(false, link.styleDelta?.underline)
    }

    /**
     * `body { margin: 2em }`은 페이지 컨테이너로 기록되며, 이것이 페이지에 여백을 준다.
     *
     * 리플로우 가능한 책은 페이지 여백을 `body`에 명시하는데, 예전에는 그릴 배경이나 테두리가
     * 있을 때만 컨테이너가 기록되었다 — 그래서 여백이 누락되고 텍스트가 책이 조판된 것보다
     * 훨씬 넓은 칼럼에서 가장자리부터 가장자리까지 채워졌다.
     */
    @Test
    fun bodyMarginsAreRecordedAsThePagesOwnMargins() {
        val content = parseXhtmlContent(
            """
            <html><body><p>Prose.</p></body></html>
            """.trimIndent(),
            css = EpubCss.parse(listOf("body { margin: 2em }")),
        )

        val page = content.blocks.single { it.isPageContainer }
        assertEquals(2f, page.style?.marginStartEm)
        assertEquals(2f, page.style?.marginEndEm)
        assertEquals(2f, page.style?.marginTopEm)
    }
}
