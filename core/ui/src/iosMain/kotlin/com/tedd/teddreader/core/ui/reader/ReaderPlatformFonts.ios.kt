package com.tedd.teddreader.core.ui.reader

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

internal actual fun readerFontFamilyFromFile(path: String): FontFamily? =
    runCatching {
        FontFamily(
            Font(
                identity = path,
                getData = { NSData.dataWithContentsOfFile(path)?.toByteArray() ?: ByteArray(0) },
            ),
        )
    }.getOrNull()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val sizeInt = length.toInt()
    if (sizeInt == 0) return ByteArray(0)
    val result = ByteArray(sizeInt)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}
