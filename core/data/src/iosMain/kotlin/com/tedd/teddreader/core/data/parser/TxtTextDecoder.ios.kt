package com.tedd.teddreader.core.data.parser

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingDOSKorean
import platform.Foundation.NSString
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class)
internal actual fun decodeLegacyKoreanText(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return ""

    val encoding = CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingDOSKorean.toUInt())
    return bytes.usePinned { pinned ->
        NSString.create(
            bytes = pinned.addressOf(0),
            length = bytes.size.toULong(),
            encoding = encoding,
        ) as String?
    }
}
