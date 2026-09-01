package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import okio.FileSystem
import okio.buffer
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import kotlin.random.Random

/** [defaultPdfMetadataReader] 계약의 iOS 구현. */
internal actual fun defaultPdfMetadataReader(): PdfMetadataReader = IosPdfMetadataReader()

/**
 * PDFKit 위에 만들어진 iOS의 [PdfMetadataReader]. [pageCount]와 [coverImageBytes] 모두
 * **위치 우선** 방식으로 문서를 해석한다: 경로에 도달 가능하면 임시 파일 쓰기 없이,
 * [DocumentLocation.sourceUri]에 인코딩된 파일 경로에서 곧바로 `PDFDocument`를 연다. 경로를 열 수
 * 없고 [bytes]가 null이 아닐 때만 이 구현은 [bytes]를 임시 파일에 쓰는 방식으로 폴백한다 — 아직
 * 문서를 샌드박스로 materialize하지 않은 호출자를 위한 레거시 경로다.
 *
 * 이 변경 전에는 [pageCount]는 이미 위치를 직접 사용했지만 [coverImageBytes]는 무조건 바이트를
 * 임시 파일에 썼다 — 이미 앱 샌드박스에 있는 PDF의 표지를 추출할 때마다 불필요한 복사를 만들었다.
 * 이제 두 메서드 모두 같은 위치 우선 해석 전략을 공유한다.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPdfMetadataReader : PdfMetadataReader {
    /**
     * @param location 문서의 위치; 페이지 수는 [DocumentLocation.sourceUri]에 있는 파일에서 직접
     *   읽힌다.
     * @param bytes [location]의 경로가 `PDFDocument`로 열릴 수 없을 때만 쓰이는 폴백 바이트.
     *   호출자가 [location]이 도달 가능한 로컬 파일임을 보장하면 null.
     * @return 페이지 수. `location`의 경로에 파일이 없고 바이트 폴백도 없거나, 파일이 PDF로 열릴
     *   수 없으면 `1` — 이 함수는 절대 던지지 않는다.
     */
    override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int =
        withPdfDocument(location, bytes) { document ->
            document.pageCount.toInt().coerceAtLeast(1)
        } ?: 1

    /**
     * @param location 문서의 위치; 도달 가능하면 표지는 [DocumentLocation.sourceUri]에 있는 파일에서
     *   직접 렌더링된다.
     * @param bytes [location]의 경로가 `PDFDocument`로 열릴 수 없을 때만 쓰이는 폴백 바이트.
     *   호출자가 [location]이 도달 가능한 로컬 파일임을 보장하면 null.
     * @return PDFKit 자체의 `thumbnailOfSize`로 360×480 영역에 맞게 크기 조정된 첫 페이지의
     *   PNG 인코딩 썸네일, 또는 문서에 첫 페이지가 없거나 렌더링이 어떤 이유로든 실패하면 `null`.
     */
    override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? =
        withPdfDocument(location, bytes) { document ->
            val page = document.pageAtIndex(0UL) ?: return@withPdfDocument null
            val thumbnail = page.thumbnailOfSize(
                size = CGSizeMake(360.0, 480.0),
                forBox = kPDFDisplayBoxMediaBox,
            )
            UIImagePNGRepresentation(thumbnail)?.toByteArray()
        }

    /**
     * 위치 우선 전략으로 [PDFDocument]를 연다: 먼저 [location]의 로컬 파일 경로를 시도하고, 그다음
     * [bytes]를 임시 파일에 쓰는 것으로 폴백한다. 성공적으로 열린 문서에 대해 [block]을 실행하고,
     * 이후 임시 파일이 있으면 정리한다.
     *
     * @param location 먼저 열기를 시도할 문서의 위치.
     * @param bytes [location]을 열 수 없을 때 임시 파일로 materialize할 폴백 바이트.
     * @param block 열린 [PDFDocument]로 수행할 작업.
     * @return [block]의 결과, 또는 어떤 문서도 열 수 없었으면 null.
     */
    private fun <T> withPdfDocument(
        location: DocumentLocation,
        bytes: ByteArray?,
        block: (PDFDocument) -> T,
    ): T? {
        val documentFromLocation = openFromLocation(location)
        if (documentFromLocation != null) {
            return runCatching { block(documentFromLocation) }.getOrNull()
        }
        if (bytes == null) return null
        val fileSystem = systemFileSystem()
        val tempPath = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-pdf-cover-${Random.nextLong().toString(16)}.pdf"
        val sink = fileSystem.sink(tempPath).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            val url = NSURL.fileURLWithPath(tempPath.toString())
            val document = PDFDocument(url)
            block(document)
        } catch (_: Throwable) {
            null
        } finally {
            fileSystem.delete(tempPath)
        }
    }

    /**
     * [location]의 파일 경로에서 [PDFDocument]를 열려고 시도한다. URI가 `file://` 경로가 아니거나
     * 그 경로의 파일이 유효한 PDF로 열릴 수 없으면 null을 반환한다.
     *
     * @param location 해석할 문서 위치.
     * @return 열린 [PDFDocument], 또는 직접 접근이 불가능하면 null.
     */
    private fun openFromLocation(location: DocumentLocation): PDFDocument? = runCatching {
        val path = location.sourceUri.removePrefix("file://")
        val url = NSURL.fileURLWithPath(path)
        PDFDocument(url)
    }.getOrNull()
}

/**
 * 이 `NSData`의 바이트를 Kotlin [ByteArray]로 복사한다.
 *
 * @receiver 복사할 데이터.
 * @return 같은 길이의 [ByteArray]. 빈 입력은 네이티브 메모리를 건드리지 않고 빈 배열로 특수 처리된다.
 *   길이 0인 [ByteArray]를 pin하고 그 주소를 얻는 것은 Kotlin/Native에서 정의되지 않은 동작이기
 *   때문이다.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size == 0) return result

    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.convert())
    }
    return result
}
