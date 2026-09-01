package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.SupportedDocumentExtensions
import com.tedd.teddreader.core.common.model.SupportedDocumentMimeTypes
import org.koin.core.annotation.Single

/**
 * 소스가 제공할 수 있는 정보 — 표시 이름, 자체 보고된 MIME 타입, 그리고 (선택적으로) 파일의 앞부분 바이트 — 를
 * 활용해 파일이 어떤 [DocumentFormat]인지 판별한다.
 *
 * 이름이나 MIME 타입 어느 쪽도 단독으로는 신뢰할 수 없다 — 클라우드 제공자는 `application/octet-stream` 같은
 * 범용 MIME 타입을 보고할 수 있고, 콘텐츠 URI에서 가져온 문서는 파일 확장자가 전혀 없을 수도 있다 — 따라서
 * 두 가지를 모두 검사하며, PDF와 래스터 이미지의 경우 다른 두 방법이 모두 틀릴 수 없는 최후 수단으로 파일의
 * 매직 바이트도 스니핑한다. [bytes]가 nullable인 것은 이름과 MIME 타입만 가진 호출자(아직 아무것도 읽지 않은
 * 상태)도 호출할 수 있어야 하기 때문이다; 이 경우 감지는 이름/MIME 매칭만으로 폴백되며, 시그니처가 필요한
 * 경우는 단순히 해결할 수 없다.
 */
@Single
class DocumentFormatDetector {
    /**
     * [location]에 있는 파일을 분류하며, 선택적으로 [bytes]를 통해 확인한다.
     *
     * 포맷은 고정된 순서로 시도되며 첫 번째 매칭이 우선한다: TXT, 그 다음 PDF, EPUB, CBZ, 마지막으로 래스터
     * IMAGE 순서이며, 아무것도 매칭되지 않으면 [DocumentFormat.UNKNOWN]으로 떨어진다. CBZ는 만화 전용 MIME
     * 타입(`application/vnd.comicbook+zip`, `application/x-cbz`)이나 리터럴 `.cbz` 확장자로만 인식된다 —
     * 의도적으로 ZIP 시그니처 스니핑을 피했는데, 모든 CBZ가 일반 `.zip`, `.docx`, `.mobi`와 같은 ZIP
     * 시그니처를 공유하므로 잘못 만화로 감지될 수 있기 때문이다. IMAGE는 이 리더가 실제로 디코딩할 수 있는
     * 래스터 포맷(JPEG, PNG, WebP, GIF, BMP)만 포함한다; SVG는 [SupportedDocumentMimeTypes]에 나열되지
     * 않으며 바이트도 [hasRasterImageSignature]의 어떤 시그니처와도 일치하지 않으므로, 호출자가
     * `image/svg+xml`을 보고하더라도 여기서는 UNKNOWN으로 해석된다.
     *
     * @param location 파일 자체의 정보: [DocumentLocation.displayName](확장자 확인에 사용)과
     *   [DocumentLocation.mimeType](범용이거나 누락되거나 아예 없을 수 있음).
     * @param bytes 파일의 앞부분 바이트, 아직 아무것도 읽지 않은 경우 null. PDF(`%PDF`)와 래스터 이미지
     *   포맷의 폴백 시그니처 검사에만 사용된다; 그 외 모든 포맷은 [location]만으로 판별된다.
     * @return 감지된 [DocumentFormat], 또는 어떤 검사도 매칭되지 않으면 [DocumentFormat.UNKNOWN].
     */
    fun detect(location: DocumentLocation, bytes: ByteArray?): DocumentFormat {
        val name = location.displayName.lowercase()
        val mimeType = location.mimeType?.lowercase()
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            mimeType == "text/plain" || name.endsWith(".txt") -> DocumentFormat.TXT
            mimeType == "application/pdf" || name.endsWith(".pdf") || (bytes?.startsWithAscii("%PDF") == true) -> DocumentFormat.PDF
            mimeType == "application/epub" || mimeType == "application/epub+zip" || name.endsWith(".epub") -> DocumentFormat.EPUB
            mimeType == "application/vnd.comicbook+zip" || mimeType == "application/x-cbz" || extension == "cbz" ->
                DocumentFormat.CBZ
            mimeType in SupportedImageMimeTypes || extension in SupportedImageExtensions || (bytes?.hasRasterImageSignature() == true) ->
                DocumentFormat.IMAGE
            else -> DocumentFormat.UNKNOWN
        }
    }
}

/**
 * 이 리더가 실제로 지원하는 이미지 MIME 타입 목록. 즉 [SupportedDocumentMimeTypes]에서 `image/` 계열로
 * 좁힌 것.
 */
private val SupportedImageMimeTypes = SupportedDocumentMimeTypes.filterTo(hashSetOf()) { it.startsWith("image/") }

/**
 * 이 리더가 디코딩할 수 있는 래스터 파일 확장자 목록; [SupportedDocumentExtensions]를 같은 방식으로 좁힌 것.
 */
private val SupportedImageExtensions = SupportedDocumentExtensions.filterTo(hashSetOf()) { extension ->
    extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
}

/**
 * [value]의 각 위치에 있는 바이트가 인덱스 0부터 순서대로 나타나는지 여부 — `%PDF`, `GIF89a`, `RIFF`, `BM`
 * 같은 ASCII 마커 검사로, [value] 자체를 바이트에서 디코딩할 필요가 없다.
 *
 * @receiver 파일의 앞부분 바이트; 수신자가 너무 짧으면 예외를 던지지 않고 불일치로 처리한다.
 * @param value 수신자의 시작 부분에서 기대되는 ASCII 마커.
 */
private fun ByteArray.startsWithAscii(value: String): Boolean =
    size >= value.length && value.indices.all { index -> this[index].toInt() and 0xFF == value[index].code }

/**
 * [this]가 JPEG, PNG, GIF, WebP, 또는 BMP의 매직 바이트로 시작하는지 여부 —
 * [DocumentFormatDetector.detect]가 IMAGE로 취급하는 포맷들. BMP의 시그니처는 두 ASCII 바이트 `BM`에
 * 불과하며, 다섯 포맷 중 가장 짧고 더 긴 마커나 길이 접두사가 붙은 청크 이름으로 고정되지 않은 유일한 것이다;
 * 우연히 그 두 바이트로 시작하는 비-BMP 파일이 잘못 인식될 수 있지만, 실제로 이 리더가 지원하는 어떤 포맷도
 * 이와 충돌하지 않는다.
 *
 * @receiver 파일의 앞부분 바이트.
 */
private fun ByteArray.hasRasterImageSignature(): Boolean =
    startsWithBytes(0xFF, 0xD8, 0xFF) ||
        startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
        startsWithAscii("GIF87a") ||
        startsWithAscii("GIF89a") ||
        (startsWithAscii("RIFF") && size >= 12 && copyOfRange(8, 12).startsWithAscii("WEBP")) ||
        startsWithAscii("BM")

/**
 * [values]의 각 위치에 있는 바이트가 인덱스 0부터 순서대로 나타나는지 여부 — [startsWithAscii]의 원시 바이트
 * 버전으로, ASCII 텍스트가 아닌 시그니처(JPEG의 `FF D8 FF`, PNG의 8바이트 헤더)에 사용된다.
 *
 * @receiver 파일의 앞부분 바이트; 수신자가 너무 짧으면 예외를 던지지 않고 불일치로 처리한다.
 * @param values 기대되는 바이트 시퀀스로, 각 바이트는 `0..255` 범위의 `Int`로 지정된다.
 */
private fun ByteArray.startsWithBytes(vararg values: Int): Boolean =
    size >= values.size && values.indices.all { index -> this[index].toInt() and 0xFF == values[index] }
