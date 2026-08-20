package com.tedd.teddreader.core.data.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaterializedDocumentFileNameTest {
    @Test
    fun theSameSourceAlwaysGetsTheSameName() {
        val first = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "dragon-raja.epub")
        val second = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "dragon-raja.epub")

        // What the whole fix rests on: another app handing the same book over a second time has to land
        // on the copy the first hand-over wrote, or it writes the book again and imports it again.
        assertEquals(first, second)
    }

    @Test
    fun differentSourcesGetDifferentNamesEvenUnderTheSameDisplayName() {
        val first = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book.epub")
        val second = materializedDocumentFileName(sourceKey = "content://downloads/43", displayName = "book.epub")

        assertNotEquals(first, second)
    }

    @Test
    fun theExtensionIsKeptBecauseFormatDetectionReadsIt() {
        assertTrue(
            materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book.epub").endsWith(".epub"),
        )
        assertTrue(
            materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "comic.cbz").endsWith(".cbz"),
        )
    }

    @Test
    fun aDisplayNameTheFilesystemWouldRefuseCannotReachTheName() {
        val name = materializedDocumentFileName(
            sourceKey = "content://downloads/42",
            displayName = "../../etc/passwd/한글 이름 (1).epub",
        )

        assertEquals("$name", name.substringAfterLast('/'), "a name must never carry a path separator")
        assertTrue(name.substringBefore('.').all { it in '0'..'9' || it in 'a'..'f' }, "the name is the hash: $name")
        assertTrue(name.endsWith(".epub"))
    }

    @Test
    fun aDisplayNameWithNoUsableExtensionStillYieldsAName() {
        val name = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book")

        assertTrue(name.isNotBlank())
        assertTrue(!name.contains('.'))
    }
}
