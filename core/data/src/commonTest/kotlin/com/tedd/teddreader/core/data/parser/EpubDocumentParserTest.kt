package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals

class EpubDocumentParserTest {
    private val parser = EpubDocumentParser()

    @Test
    fun parsesChaptersIntoReadableSections() {
        val document = parser.parseChapters(
            id = DocumentId("epub-1"),
            title = "Book",
            chapters = listOf(
                EpubChapter("Intro", "<html><body><h1>Intro</h1><p>Hello&nbsp;reader</p></body></html>"),
                EpubChapter("Next", "<p>Second &amp; chapter</p>"),
            ),
        )

        assertEquals(DocumentFormat.EPUB, document.format)
        assertEquals(2, document.sections.size)
        assertEquals("Intro Hello reader", document.sections.first().text)
        assertEquals("Second & chapter", document.sections[1].text)
    }
}
