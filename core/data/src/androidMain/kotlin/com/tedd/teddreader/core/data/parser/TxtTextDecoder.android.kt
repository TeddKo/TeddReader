package com.tedd.teddreader.core.data.parser

import java.nio.charset.Charset

internal actual fun decodeLegacyKoreanText(bytes: ByteArray): String? = runCatching {
    String(bytes, Charset.forName("MS949"))
}.getOrNull()
