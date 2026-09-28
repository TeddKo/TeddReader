package com.tedd.teddreader.core.ui.reader

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** iOS 임베디드 폰트 로딩의 캐시와 메모리 상한 계약을 검증한다. */
class ReaderPlatformFontsIosTest {
    /** 같은 폰트의 네이티브 데이터를 한 번만 복사해 이후 측정에서 재사용한다. */
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

    /** 허용 상한을 넘거나 `Int`로 표현할 수 없는 길이는 배열 할당 전에 거부한다. */
    @Test
    fun oversizedFontLengthIsRejectedBeforeConversion() {
        assertEquals(64 * 1024 * 1024, readerFontByteArraySize((64 * 1024 * 1024).toULong()))
        assertNull(readerFontByteArraySize((64 * 1024 * 1024 + 1).toULong()))
        assertNull(readerFontByteArraySize(ULong.MAX_VALUE))
    }
}
