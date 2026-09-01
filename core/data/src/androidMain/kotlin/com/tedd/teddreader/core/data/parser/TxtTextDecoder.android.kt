package com.tedd.teddreader.core.data.parser

import java.nio.charset.Charset

/**
 * [decodeLegacyKoreanText] 계약에 대한 Android 구현. JVM의 `MS949` 문자셋 — Windows-949/CP949 한국어
 * 코드 페이지에 대한 Java의 별칭 — 을 사용한다. 어떤 디코딩 실패든 전파하지 않고 붙잡아 `null`로
 * 보고하여, "절대 던지지 않는다"는 계약의 절반을 지킨다.
 */
internal actual fun decodeLegacyKoreanText(bytes: ByteArray): String? = runCatching {
    String(bytes, Charset.forName("MS949"))
}.getOrNull()
