package com.tedd.teddreader.core.ui.reader

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * 임베디드 폰트 하나를 Kotlin 바이트 배열로 복사할 수 있는 최대 바이트 수다. 가져오기 단계의 폰트 상한
 * (`MAX_EPUB_FONT_BYTES`, 64 MiB)과 같아야 하며, 네이티브 `NSData` 적재 자체를 막지는 않고 Kotlin 쪽
 * 복사본 할당만 막는다.
 */
private const val MAX_READER_FONT_BYTES = 64 * 1024 * 1024

/**
 * [path]의 임베디드 폰트를 Compose가 지연 로드하고 한 번 복사해 재사용하는 글꼴 모음으로 만든다.
 *
 * @param path 앱 저장소에 복사된 폰트 파일의 절대 경로.
 * @return Compose가 사용할 글꼴 모음이며 파일을 열거나 폰트를 구성할 수 없으면 null.
 */
internal actual fun readerFontFamilyFromFile(path: String): FontFamily? =
    runCatching {
        FontFamily(
            Font(
                identity = path,
                getData = cachedReaderFontData {
                    NSData.dataWithContentsOfFile(path)?.toByteArray() ?: ByteArray(0)
                },
            ),
        )
    }.getOrNull()

/**
 * 네이티브 폰트 데이터 복사가 측정마다 반복되지 않도록 첫 결과를 지연 캐시한다.
 *
 * @param load 처음 요청될 때 폰트 바이트를 읽는 작업.
 * @return 첫 호출의 바이트 배열을 이후 호출에서도 반환하는 공급자.
 */
internal fun cachedReaderFontData(load: () -> ByteArray): () -> ByteArray {
    val data by lazy(load)
    return { data }
}

/**
 * 네이티브 데이터 길이를 허용 가능한 JVM 공통 배열 크기로 검증한다. 명시적 상한이 `Int` 범위보다 작아
 * `ULong`에서 변환하기 전에 비교하면 잘린 음수 크기와 과도한 배열 할당을 함께 막는다.
 *
 * @param length Foundation이 보고한 부호 없는 데이터 길이.
 * @return 허용 범위의 배열 크기이며 상한을 넘으면 null.
 */
internal fun readerFontByteArraySize(length: ULong): Int? =
    length.takeIf { it <= MAX_READER_FONT_BYTES.toULong() }?.toInt()

/**
 * Foundation 데이터를 상한 내의 Kotlin 바이트 배열로 복사한다.
 *
 * @receiver 복사할 네이티브 폰트 데이터.
 * @return 복사된 바이트이며 허용 상한을 넘으면 null.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray? {
    val sizeInt = readerFontByteArraySize(length) ?: return null
    if (sizeInt == 0) return ByteArray(0)
    val result = ByteArray(sizeInt)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}
