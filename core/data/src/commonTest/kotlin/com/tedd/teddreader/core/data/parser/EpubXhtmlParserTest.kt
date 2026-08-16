package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.blocksIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubXhtmlParserTest {
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

    @Test
    fun headingsCarryTheirLevel() {
        val content = parseXhtmlContent("<h2>Chapter</h2><h5>Aside</h5>")

        assertEquals(listOf(2, 5), content.blocks.map { it.level })
        assertTrue(content.blocks.all { it.kind == ReaderBlockKind.HEADING })
    }

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

    @Test
    fun anchorBecomesALinkSpanCarryingItsTarget() {
        val content = parseXhtmlContent("""<p>see <a href="ch2.xhtml">chapter two</a></p>""")

        val span = content.blocks.single().spans.single()
        assertEquals(ReaderInlineStyle.LINK, span.style)
        assertEquals("ch2.xhtml", span.href)
        assertEquals("chapter two", content.text.substring(span.range.start.toInt(), span.range.end.toInt()))
    }

    @Test
    fun anchorWithoutTargetAddsNoSpan() {
        val content = parseXhtmlContent("""<p>anchor <a id="p12"></a>only</p>""")

        assertEquals(emptyList(), content.blocks.single().spans)
        assertEquals("anchor only", content.text)
    }

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

    @Test
    fun nestedListsRaiseTheDepth() {
        val content = parseXhtmlContent("<ul><li>outer<ul><li>inner</li></ul></li></ul>")

        assertEquals(listOf(1, 2), content.blocks.map { it.level })
    }

    @Test
    fun anchorsCaptureNamedIdsAtAbsoluteOffsets() {
        val content = parseXhtmlContent("""<h1 id="top">Title</h1><p><a id="scene"></a>Body</p>""", baseOffset = 10)

        assertEquals(10L, content.anchors["top"])
        assertEquals(17L, content.anchors["scene"])
    }

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
        // A one-character range keeps the block addressable at a page boundary.
        assertEquals(1L, image.range.end - image.range.start)
    }

    @Test
    fun trailingStandaloneBlockStaysInsideReturnedTextRange() {
        val content = parseXhtmlContent("""<p>before</p><img src="plate.jpg" alt="Plate 1"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertTrue(image.range.end <= content.text.length.toLong())
        assertTrue(image in content.blocks.blocksIn(0, content.text.length.toLong()))
    }

    @Test
    fun imageCarriesTheAspectRatioDeclaredInWidthAndHeightAttributes() {
        val content = parseXhtmlContent("""<img src="plate.jpg" width="800" height="400"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals(2f, image.imageAspectRatio)
    }

    @Test
    fun imageCarriesTheAspectRatioDeclaredInAnInlineStyle() {
        val content = parseXhtmlContent("""<img src="plate.jpg" style="width:300px;height:600px"/>""")

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals(0.5f, image.imageAspectRatio)
    }

    @Test
    fun imageHasNoAspectRatioWhenDimensionsAreUnspecifiedOrPercentages() {
        val undeclared = parseXhtmlContent("""<img src="plate.jpg"/>""")
        val percentage = parseXhtmlContent("""<img src="plate.jpg" width="100%" height="200"/>""")

        assertEquals(null, undeclared.blocks.single { it.kind == ReaderBlockKind.IMAGE }.imageAspectRatio)
        assertEquals(null, percentage.blocks.single { it.kind == ReaderBlockKind.IMAGE }.imageAspectRatio)
    }

    @Test
    fun imageIsDroppedWhenItCannotBeResolved() {
        val content = parseXhtmlContent(
            xhtml = """<img src="https://example.com/remote.png"/><p>text</p>""",
            resolveImageHref = { source -> resolveContainerHref("ch1.xhtml", source) },
        )

        assertTrue(content.blocks.none { it.kind == ReaderBlockKind.IMAGE })
    }

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

    @Test
    fun preformattedTextKeepsItsOwnWhitespace() {
        val content = parseXhtmlContent("<pre>line one\n    indented</pre>")

        val block = content.blocks.single()
        assertEquals(ReaderBlockKind.PREFORMATTED, block.kind)
        assertEquals("line one\n    indented", content.text)
    }

    @Test
    fun runsOfWhitespaceInsideAParagraphCollapseLikeMarkupSays() {
        val content = parseXhtmlContent("<p>spaced   out\n   words</p>")

        assertEquals("spaced out words", content.text)
    }

    @Test
    fun lineBreakSurvivesInsideAParagraph() {
        val content = parseXhtmlContent("<p>first<br/>second</p>")

        assertEquals("first\nsecond", content.text)
        assertEquals(1, content.blocks.size)
    }

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

    @Test
    fun selfClosingHeadDoesNotDiscardBody() {
        val content = parseXhtmlContent("<html><head/><body><p>Body</p></body></html>")

        assertEquals("Body", content.text)
        assertEquals(listOf(ReaderBlockKind.PARAGRAPH), content.blocks.map { it.kind })
    }

    @Test
    fun namedAndNumericEntitiesAreDecoded() {
        assertEquals(
            "“quoted” — a b & 'c' ½ 😀",
            decodeXmlEntities("&ldquo;quoted&rdquo; &mdash; a&#160;b &amp; &apos;c&apos; &frac12; &#x1F600;"),
        )
    }

    @Test
    fun unknownEntityIsLeftAloneInsteadOfEatingText() {
        assertEquals("a &notanentity; b", decodeXmlEntities("a &notanentity; b"))
    }

    @Test
    fun baseOffsetShiftsEveryRangeSoChaptersCanBeConcatenated() {
        val first = parseXhtmlContent("<p>one</p>")
        val second = parseXhtmlContent("<p>two</p>", baseOffset = first.text.length + 1L)

        assertEquals(0L, first.blocks.single().range.start)
        assertEquals(first.text.length + 1L, second.blocks.single().range.start)
    }

    @Test
    fun markupWithNoReadableTextProducesNoBlocks() {
        val content = parseXhtmlContent("<div><span> </span></div>")

        assertEquals("", content.text)
        assertEquals(emptyList(), content.blocks)
    }

    @Test
    fun unclosedInlineMarkupEndsWithItsBlock() {
        val content = parseXhtmlContent("<p>start <b>bold to the end</p><p>next</p>")

        val span = content.blocks.first().spans.single()
        assertEquals("bold to the end", content.text.substring(span.range.start.toInt(), span.range.end.toInt()))
        assertEquals(emptyList(), content.blocks[1].spans)
    }

    @Test
    fun inlineStyleAlignmentIsPickedUp() {
        val content = parseXhtmlContent("""<p style="text-align: center">middle</p><p>plain</p>""")

        assertEquals(ReaderTextAlign.CENTER, content.blocks.first().align)
        assertNull(content.blocks[1].align)
    }
}
