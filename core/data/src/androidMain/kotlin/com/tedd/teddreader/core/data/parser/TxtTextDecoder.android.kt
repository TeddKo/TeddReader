package com.tedd.teddreader.core.data.parser

import java.nio.charset.Charset

/**
 * Android's implementation of the [decodeLegacyKoreanText] contract, using the JVM's `MS949` charset
 * — Java's alias for the Windows-949/CP949 Korean code page. Any decoding failure is caught and
 * reported as `null` rather than propagated, honouring the "never throws" half of the contract.
 */
internal actual fun decodeLegacyKoreanText(bytes: ByteArray): String? = runCatching {
    String(bytes, Charset.forName("MS949"))
}.getOrNull()
