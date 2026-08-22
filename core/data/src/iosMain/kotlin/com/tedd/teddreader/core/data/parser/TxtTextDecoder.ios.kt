package com.tedd.teddreader.core.data.parser

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingDOSKorean
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * iOS's implementation of the [decodeLegacyKoreanText] contract, using Core Foundation's
 * `kCFStringEncodingDOSKorean` — the DOS/Windows Korean code page, the same CP949-compatible family
 * Android reaches through `MS949`. Empty input is special-cased to an empty string before touching
 * native memory at all, since pinning a zero-length [ByteArray] and taking its address (as the
 * `usePinned`/`addressOf` call below would) is undefined behavior on Kotlin/Native. Beyond that,
 * `NSString.create` returns `null` on its own when [bytes] cannot be mapped under this encoding, which
 * satisfies the contract's "never throws" requirement without an explicit catch.
 */
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
