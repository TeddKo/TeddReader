package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentId
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ComicBookDocumentParserArchiveTest {
    @Test
    fun parsesAndReadsNaturallyOrderedImageEntries() {
        val cover = byteArrayOf(1)
        val page2 = byteArrayOf(2)
        val page10 = byteArrayOf(10)
        val bytes = comicZip(
            "page10.jpg" to page10,
            "cover.jpg" to cover,
            "page2.png" to page2,
            "notes.txt" to byteArrayOf(99),
        )
        val parser = ComicBookDocumentParser()

        val document = parser.parse(DocumentId("comic"), "Comic", bytes)
        val pages = parser.pageImageBytes(bytes, setOf(0, 1, 2))

        assertEquals(3, document.pageCount)
        assertContentEquals(cover, pages[0])
        assertContentEquals(page2, pages[1])
        assertContentEquals(page10, pages[2])
    }
}

private fun comicZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}
