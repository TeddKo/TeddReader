package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
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
 * Pins [parseXhtmlContent]'s markup-to-text-and-blocks contract: paragraph/heading/list/table
 * structure, inline spans and links, anchors, image placement (inline vs. standalone) and sizing,
 * entity decoding, and tolerance for malformed markup (unclosed tags, script/style bodies, an
 * `<svg>`-wrapped picture). This is the file every EPUB rendering regression in this reader traces
 * back to.
 */
class EpubXhtmlParserTest {
    /**
     * Two paragraphs stay two separate lines of text, rather than collapsing into one run-on line the way a
     * naive tag-strip would.
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

    /** Every block's range indexes exactly the substring of the flattened text it describes. */
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

    /** A heading's numeric level (`h2` -> 2, `h5` -> 5) is carried onto its block. */
    @Test
    fun headingsCarryTheirLevel() {
        val content = parseXhtmlContent("<h2>Chapter</h2><h5>Aside</h5>")

        assertEquals(listOf(2, 5), content.blocks.map { it.level })
        assertTrue(content.blocks.all { it.kind == ReaderBlockKind.HEADING })
    }

    /** Bold and italic inline markup become spans over exactly the characters they wrap. */
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

    /** An anchor with an `href` becomes a link span carrying that target. */
    @Test
    fun anchorBecomesALinkSpanCarryingItsTarget() {
        val content = parseXhtmlContent("""<p>see <a href="ch2.xhtml">chapter two</a></p>""")

        val span = content.blocks.single().spans.single()
        assertEquals(ReaderInlineStyle.LINK, span.style)
        assertEquals("ch2.xhtml", span.href)
        assertEquals("chapter two", content.text.substring(span.range.start.toInt(), span.range.end.toInt()))
    }

    /** An anchor with no `href` (a named anchor point, not a link) adds no span at all. */
    @Test
    fun anchorWithoutTargetAddsNoSpan() {
        val content = parseXhtmlContent("""<p>anchor <a id="p12"></a>only</p>""")

        assertEquals(emptyList(), content.blocks.single().spans)
        assertEquals("anchor only", content.text)
    }

    /**
     * Ordered and unordered list items each carry the right depth, and only ordered items carry a numeric
     * marker that increments per item.
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

    /** A list nested inside another list item raises the item's depth by one over its parent. */
    @Test
    fun nestedListsRaiseTheDepth() {
        val content = parseXhtmlContent("<ul><li>outer<ul><li>inner</li></ul></li></ul>")

        assertEquals(listOf(1, 2), content.blocks.map { it.level })
    }

    /**
     * Named anchors (`id`) are captured at their absolute offset, shifted correctly by a non-zero base
     * offset.
     */
    @Test
    fun anchorsCaptureNamedIdsAtAbsoluteOffsets() {
        val content = parseXhtmlContent("""<h1 id="top">Title</h1><p><a id="scene"></a>Body</p>""", baseOffset = 10)

        assertEquals(10L, content.anchors["top"])
        assertEquals(17L, content.anchors["scene"])
    }

    /**
     * An image becomes a standalone block whose `src` resolves against its chapter's own container
     * path, with a one-character range that keeps the block addressable at a page boundary.
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
     * Regression guard: a standalone block's range stays inside the text range [parseXhtmlContent] returns,
     * even when the image is the very last thing in the chapter.
     */
    @Test
    fun trailingStandaloneBlockStaysInsideReturnedTextRange() {
        val content = parseXhtmlContent("""<p>before</p><img src="plate.jpg" alt="Plate 1"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertTrue(image.range.end <= content.text.length.toLong())
        assertTrue(image in content.blocks.blocksIn(0, content.text.length.toLong()))
    }

    /**
     * An image's `width`/`height` attributes, both given as plain pixel numbers, produce the correct aspect
     * ratio.
     */
    @Test
    fun imageCarriesTheAspectRatioDeclaredInWidthAndHeightAttributes() {
        val content = parseXhtmlContent("""<img src="plate.jpg" width="800" height="400"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals(2f, image.imageAspectRatio)
    }

    /**
     * An image's aspect ratio can also be declared through an inline `style` attribute's pixel dimensions.
     */
    @Test
    fun imageCarriesTheAspectRatioDeclaredInAnInlineStyle() {
        val content = parseXhtmlContent("""<img src="plate.jpg" style="width:300px;height:600px"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals(0.5f, image.imageAspectRatio)
    }

    /**
     * No aspect ratio is guessed when dimensions are absent entirely, or given as a percentage rather than
     * a fixed pixel size.
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
            styleSheet = parseEpubStyleSheet(stylesheetCss),
            css = EpubCss.parse(listOf(stylesheetCss)),
        )

        assertEquals(
            5.4f,
            requireNotNull(content.blocks.single { it.kind == ReaderBlockKind.IMAGE }.imageWidthEm),
            absoluteTolerance = 0.0001f,
        )
    }

    /**
     * Regression guard: `<svg><image xlink:href="..."/></svg>` — how Sigil/Calibre commonly wrap a
     * full-page illustration or cover so it scales to the viewport — must still be captured as a
     * picture rather than discarded the way script/style bodies are.
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
     * An image whose reference cannot be resolved (a remote URL this reader cannot fetch) is dropped
     * entirely, leaving the surrounding text intact.
     */
    @Test
    fun imageIsDroppedWhenItCannotBeResolved() {
        val content = parseXhtmlContent(
            xhtml = """<img src="https://example.com/remote.png"/><p>text</p>""",
            resolveImageHref = { source -> resolveContainerHref("ch1.xhtml", source) },
        )

        assertTrue(content.blocks.none { it.kind == ReaderBlockKind.IMAGE })
    }

    /** Table cells carry the row/column position built up as `<tr>`/`<td>`/`<th>` tags are opened. */
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
     * `<pre>` content keeps its own whitespace and line breaks verbatim, unlike ordinary collapsed text.
     */
    @Test
    fun preformattedTextKeepsItsOwnWhitespace() {
        val content = parseXhtmlContent("<pre>line one\n    indented</pre>")

        val block = content.blocks.single()
        assertEquals(ReaderBlockKind.PREFORMATTED, block.kind)
        assertEquals("line one\n    indented", content.text)
    }

    /**
     * Runs of whitespace inside a paragraph collapse to a single space, the way markup whitespace normally
     * reads.
     */
    @Test
    fun runsOfWhitespaceInsideAParagraphCollapseLikeMarkupSays() {
        val content = parseXhtmlContent("<p>spaced   out\n   words</p>")

        assertEquals("spaced out words", content.text)
    }

    /**
     * A `<br/>` inside a paragraph becomes a literal line break in the text, without splitting the
     * paragraph into two blocks.
     */
    @Test
    fun lineBreakSurvivesInsideAParagraph() {
        val content = parseXhtmlContent("<p>first<br/>second</p>")

        assertEquals("first\nsecond", content.text)
        assertEquals(1, content.blocks.size)
    }

    /** Text inside `<script>` and `<style>` never reaches the flattened output. */
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

    /** A self-closing `<head/>` does not consume the `<body>` that follows it. */
    @Test
    fun selfClosingHeadDoesNotDiscardBody() {
        val content = parseXhtmlContent("<html><head/><body><p>Body</p></body></html>")

        assertEquals("Body", content.text)
        assertEquals(listOf(ReaderBlockKind.PARAGRAPH), content.blocks.map { it.kind })
    }

    /**
     * Named (`&ldquo;`) and numeric (`&#160;`, `&#x1F600;`) entity references are decoded, including a
     * surrogate-pair emoji.
     */
    @Test
    fun namedAndNumericEntitiesAreDecoded() {
        assertEquals(
            "“quoted” — a b & 'c' ½ 😀",
            decodeXmlEntities("&ldquo;quoted&rdquo; &mdash; a&#160;b &amp; &apos;c&apos; &frac12; &#x1F600;"),
        )
    }

    /**
     * An unrecognized entity name is left as literal text (`&`, name, and `;` intact) instead of eating the
     * text around it.
     */
    @Test
    fun unknownEntityIsLeftAloneInsteadOfEatingText() {
        assertEquals("a &notanentity; b", decodeXmlEntities("a &notanentity; b"))
    }

    /**
     * A non-zero base offset shifts every block's range by the same amount, letting chapters be
     * concatenated without recomputing offsets.
     */
    @Test
    fun baseOffsetShiftsEveryRangeSoChaptersCanBeConcatenated() {
        val first = parseXhtmlContent("<p>one</p>")
        val second = parseXhtmlContent("<p>two</p>", baseOffset = first.text.length + 1L)

        assertEquals(0L, first.blocks.single().range.start)
        assertEquals(first.text.length + 1L, second.blocks.single().range.start)
    }

    /** Markup with nothing but whitespace inside inline elements produces no text and no blocks at all. */
    @Test
    fun markupWithNoReadableTextProducesNoBlocks() {
        val content = parseXhtmlContent("<div><span> </span></div>")

        assertEquals("", content.text)
        assertEquals(emptyList(), content.blocks)
    }

    /**
     * An inline element left unclosed at the end of a block is still recorded as a span up to that block's
     * own end, rather than leaking into the next block.
     */
    @Test
    fun unclosedInlineMarkupEndsWithItsBlock() {
        val content = parseXhtmlContent("<p>start <b>bold to the end</p><p>next</p>")

        val span = content.blocks.first().spans.single()
        assertEquals("bold to the end", content.text.substring(span.range.start.toInt(), span.range.end.toInt()))
        assertEquals(emptyList(), content.blocks[1].spans)
    }

    /** An inline `style="text-align: center"` is picked up as the block's own alignment. */
    @Test
    fun inlineStyleAlignmentIsPickedUp() {
        val content = parseXhtmlContent("""<p style="text-align: center">middle</p><p>plain</p>""")

        assertEquals(ReaderTextAlign.CENTER, content.blocks.first().align)
        assertNull(content.blocks[1].align)
    }

    /**
     * Regression guard: part and chapter headings are routinely set as a picture, with the readable
     * name only in the heading's `title` attribute (`<h1 title="..."><img/></h1>`). The picture is the
     * heading, so the title survives as [XhtmlContent.headingTitle] and no separate, empty heading
     * block is recorded alongside it.
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
        assertEquals(ReaderBlockStyle(fontScale = 0.8f, italic = true), span.cssStyle)
        assertEquals("soft", content.text.substring(span.range.start.toInt(), span.range.end.toInt()))
    }

    /**
     * Regression guard: two pictures written inline within one sentence both stay inside that sentence's
     * paragraph rather than being torn out onto their own lines.
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
        // The span differs from its paragraph in nothing, so it carries nothing: inherited styling lives
        // on the block once, not re-emitted on every nested element — a span repeating it made the
        // renderer apply relative values twice.
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
        // `inherit` resolves to the parent's own values, which the paragraph already carries — so the
        // link's delta against it is empty and the styling reaches the link through the block.
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
        assertEquals(null, link.cssStyle)
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
     * A stylesheet's spacing and decoration reach the blocks that are drawn from it.
     *
     * These are the declarations a reflowable book leans on hardest — the paragraph gap, the quotation's
     * indent, and links that carry no underline — and every one of them used to be dropped between the
     * stylesheet and the page.
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
        assertEquals(false, link.cssStyle?.underline)
    }

    /**
     * `body { margin: 2em }` is recorded as a page container, which is what gives the page its margins.
     *
     * A reflowable book states its page margins on `body`, and the container used to be recorded only when it
     * had a background or a border to paint — so the margins were dropped and the text was set edge to edge in
     * a column far wider than the book was typeset for.
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
