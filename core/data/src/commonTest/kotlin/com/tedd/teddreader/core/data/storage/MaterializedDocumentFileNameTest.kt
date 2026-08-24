package com.tedd.teddreader.core.data.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins [materializedDocumentFileName]'s contract: the name a document is materialized under must be
 * derived from where it came from, not invented per copy. This is what lets the same source resolve
 * to the same on-disk file across repeated imports (no duplicate books piling up), while different
 * sources never collide even under an identical display name, the original extension survives (later
 * format detection reads it), and nothing in an attacker- or filesystem-hostile display name — a path
 * separator, a `..` traversal, non-ASCII text — can ever reach the produced name, since the name is
 * always a hash plus a sanitized extension rather than a transform of the display name itself.
 */
class MaterializedDocumentFileNameTest {
    /**
     * Guards the fix this file exists for: importing the same source a second time — as happens when
     * another app hands the same book over again via "open with" — must resolve to the copy the first
     * import already wrote, or the app writes the book again and imports it a second time.
     */
    @Test
    fun theSameSourceAlwaysGetsTheSameName() {
        val first = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "dragon-raja.epub")
        val second = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "dragon-raja.epub")

        assertEquals(first, second)
    }

    /** Guards against two unrelated documents sharing a display name overwriting each other's copy. */
    @Test
    fun differentSourcesGetDifferentNamesEvenUnderTheSameDisplayName() {
        val first = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book.epub")
        val second = materializedDocumentFileName(sourceKey = "content://downloads/43", displayName = "book.epub")

        assertNotEquals(first, second)
    }

    /**
     * Guards that the produced name still ends in the original extension, since format detection's
     * fallback path sniffs a document's format from its file name's extension.
     */
    @Test
    fun theExtensionIsKeptBecauseFormatDetectionReadsIt() {
        assertTrue(
            materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book.epub").endsWith(".epub"),
        )
        assertTrue(
            materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "comic.cbz").endsWith(".cbz"),
        )
    }

    /**
     * Guards against a hostile or merely foreign display name — a path traversal, a path separator, a
     * non-ASCII name — ever reaching the filesystem: the produced name is always a hex SHA-1 hash plus
     * a sanitized extension, never a derivative of the display name's own characters.
     */
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

    /**
     * Guards that a display name with no dot, or an "extension" too long or non-alphanumeric to be
     * real, still produces a valid, non-blank name with no trailing dot rather than crashing.
     */
    @Test
    fun aDisplayNameWithNoUsableExtensionStillYieldsAName() {
        val name = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book")

        assertTrue(name.isNotBlank())
        assertTrue(!name.contains('.'))
    }
}
