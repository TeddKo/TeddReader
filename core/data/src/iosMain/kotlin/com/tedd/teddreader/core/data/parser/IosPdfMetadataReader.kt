package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import okio.FileSystem
import okio.IOException
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
 * 문서를 샌드박스로 materialize하지 않은 호출자를 위한 레거시 경로다. 두 메서드는 같은 위치 우선
 * 해석 전략을 공유하며, 임시 파일은 생성이나 읽기가 실패해도 정리를 시도한다.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPdfMetadataReader : PdfMetadataReader {
    /**
     * @param location 문서의 위치; 페이지 수는 [DocumentLocation.sourceUri]에 있는 파일에서 직접
     *   읽힌다.
     * @param bytes [location]의 경로가 `PDFDocument`로 열릴 수 없을 때만 쓰이는 폴백 바이트.
     *   호출자가 [location]이 도달 가능한 로컬 파일임을 보장하면 null.
     * @return 페이지 수. `location`의 경로에 파일이 없고 바이트 폴백도 없거나, 예상 I/O 실패로 파일을
     *   열 수 없으면 `1`.
     * @throws OutOfMemoryError 파일 기록이나 PDFKit 문서 생성 중 메모리가 고갈될 때.
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
     *   PNG 인코딩 썸네일, 또는 첫 페이지가 없거나 예상 I/O 실패이면 `null`.
     * @throws OutOfMemoryError 파일 기록·렌더링·이미지 인코딩 중 메모리가 고갈될 때.
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
     * @return [block]의 결과, 또는 어떤 문서도 열 수 없었으면 null. 임시 파일에서도 열리지 않으면
     *   폴백 역시 null이다.
     * @throws OutOfMemoryError 파일 기록·PDFKit 작업 중 메모리가 고갈될 때.
     */
    private fun <T> withPdfDocument(
        location: DocumentLocation,
        bytes: ByteArray?,
        block: (PDFDocument) -> T,
    ): T? {
        val documentFromLocation = openFromLocation(location)
        if (documentFromLocation != null) {
            return block(documentFromLocation)
        }
        if (bytes == null) return null
        val fileSystem = systemFileSystem()
        val tempPath = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-pdf-cover-${Random.nextLong().toString(16)}.pdf"
        return withTemporaryPdfFile(fileSystem, tempPath, bytes) {
            val url = NSURL.fileURLWithPath(tempPath.toString())
            openPdfDocument(url)?.let(block)
        }
    }

    /**
     * [location]의 파일 경로에서 [PDFDocument]를 열려고 시도한다. URI가 `file://` 경로가 아니거나
     * 그 경로의 파일이 유효한 PDF로 열릴 수 없으면 null을 반환한다. 열기 실패는 [openPdfDocument]가
     * null로 축소한다.
     *
     * @param location 해석할 문서 위치.
     * @return 열린 [PDFDocument], 또는 직접 접근이 불가능하면 null.
     */
    private fun openFromLocation(location: DocumentLocation): PDFDocument? {
        val path = location.sourceUri.removePrefix("file://")
        val url = NSURL.fileURLWithPath(path)
        return openPdfDocument(url)
    }
}

/**
 * [url]의 PDF를 여는 `PDFDocument` 생성의 실패를 null로 바꾼다. ObjC `initWithURL:`은 열 수 없는
 * 파일에 nil을 돌려주지만 Kotlin/Native는 이를 non-null 생성자로 노출해 nil에서
 * NullPointerException을 던지는데, 이는 존재하지 않거나 손상된 PDF에서 예상되는 실패이므로
 * [PdfMetadataReader]의 기본값 계약과 바이트 폴백이 동작하도록 여기서만 좁게 잡는다. 다른 예외와
 * [Error]는 숨기지 않는다.
 *
 * @param url 열 PDF 파일의 URL.
 * @return 열린 [PDFDocument], 또는 열 수 없으면 null.
 */
@OptIn(ExperimentalForeignApi::class)
private fun openPdfDocument(url: NSURL): PDFDocument? = try {
    PDFDocument(url)
} catch (_: NullPointerException) {
    null
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

/**
 * [bytes]를 iOS PDF 폴백용 [path]에 기록하고 [block]이 끝날 때까지 유지한 뒤 항상 삭제한다. 파일
 * 작업의 예상 I/O 실패는 null로 축소하지만, [block]이 던지는 프로그래밍 오류와 [Error]는 숨기지 않는다.
 *
 * @param fileSystem 임시 파일을 기록하고 삭제할 파일 시스템.
 * @param path 이번 호출만 소유하는 임시 PDF 경로.
 * @param bytes 임시 파일에 기록할 PDF 원본 바이트.
 * @param block 기록된 파일을 사용하는 작업.
 * @return [block]의 결과, 또는 파일 기록이 I/O로 실패하면 null.
 * @throws OutOfMemoryError 파일 기록이나 [block] 실행 중 메모리가 고갈될 때.
 */
internal fun <T> withTemporaryPdfFile(
    fileSystem: FileSystem,
    path: okio.Path,
    bytes: ByteArray,
    block: (okio.Path) -> T,
): T? = try {
    fileSystem.write(path) { write(bytes) }
    block(path)
} catch (_: IOException) {
    null
} finally {
    try {
        fileSystem.delete(path, mustExist = false)
    } catch (_: IOException) {
    }
}
