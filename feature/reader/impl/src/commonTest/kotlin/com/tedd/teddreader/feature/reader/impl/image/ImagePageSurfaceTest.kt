package com.tedd.teddreader.feature.reader.impl.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Verifies visual-page bitmap cache identities stay stable without crossing document or page boundaries. */
class ImagePageSurfaceTest {
    /** Duplicate compositions of one CBZ page must address the same decoded bitmap. */
    @Test
    fun visualPageMemoryCacheKeyIsStableForOneDocumentPage() {
        assertEquals(
            visualPageMemoryCacheKey("file:///library/comic.cbz", 3),
            visualPageMemoryCacheKey("file:///library/comic.cbz", 3),
        )
    }

    /** Identically numbered pages from different CBZ documents must never share a decoded bitmap. */
    @Test
    fun visualPageMemoryCacheKeySeparatesDocuments() {
        assertNotEquals(
            visualPageMemoryCacheKey("file:///library/first.cbz", 3),
            visualPageMemoryCacheKey("file:///library/second.cbz", 3),
        )
    }

    /** Adjacent pages in one CBZ must never share a decoded bitmap. */
    @Test
    fun visualPageMemoryCacheKeySeparatesPages() {
        assertNotEquals(
            visualPageMemoryCacheKey("file:///library/comic.cbz", 3),
            visualPageMemoryCacheKey("file:///library/comic.cbz", 4),
        )
    }

    /** A missing document identity keeps ByteArray data outside Coil's memory cache. */
    @Test
    fun visualPageMemoryCacheKeyRequiresDocument() {
        assertEquals(null, visualPageMemoryCacheKey(null, 3))
        assertEquals(null, visualPageMemoryCacheKey("", 3))
    }
}
