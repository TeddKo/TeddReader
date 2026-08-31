package com.tedd.teddreader.core.ui.reader

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReaderPlatformFontsIosTest {
    @Test
    fun embeddedFontBytesAreLoadedOncePerFontFamily() {
        var loads = 0
        val getData = cachedReaderFontData {
            loads += 1
            byteArrayOf(1, 2, 3)
        }

        assertContentEquals(byteArrayOf(1, 2, 3), getData())
        assertContentEquals(byteArrayOf(1, 2, 3), getData())
        assertEquals(1, loads)
    }
}
