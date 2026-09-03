package com.tedd.teddreader.core.data.parser

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingDOSKorean
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * [decodeLegacyKoreanText] 계약의 iOS 구현. Core Foundation의 `kCFStringEncodingDOSKorean` —
 * Android가 `MS949`로 도달하는 것과 같은 CP949 호환 계열인 DOS/Windows 한국어 코드 페이지 —
 * 를 사용한다. 빈 입력은 네이티브 메모리를 건드리기 전에 빈 문자열로 특수 처리된다. 아래의
 * `usePinned`/`addressOf` 호출처럼 길이 0인 [ByteArray]를 핀 고정하고 그 주소를 취하는 것은
 * Kotlin/Native에서 정의되지 않은 동작이기 때문이다. 그 외에는, `NSString.create`가 [bytes]를 이
 * 인코딩으로 매핑할 수 없을 때 스스로 `null`을 반환하므로, 명시적인 catch 없이도 계약의 "결코 던지지
 * 않음" 요구사항을 만족한다.
 *
 * 바이트 버퍼를 받는 `NSString.create` 오버로드는 `BetaInteropApi`로 표시되어 있어 opt-in이 필요하다.
 * 반환된 `NSString`은 [String]으로 캐스트하지 않고 `toString()`으로 옮긴다. 타입 시스템에서
 * `NSString`과 [String]은 서로 무관해 캐스트가 "null일 때만 성립한다"는 경고를 남기는 반면,
 * `NSString`의 `description`은 문자열 자신이므로 `toString()`은 같은 내용을 경고 없이 돌려준다.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun decodeLegacyKoreanText(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return ""

    val encoding = CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingDOSKorean.toUInt())
    return bytes.usePinned { pinned ->
        NSString.create(
            bytes = pinned.addressOf(0),
            length = bytes.size.toULong(),
            encoding = encoding,
        )?.toString()
    }
}
