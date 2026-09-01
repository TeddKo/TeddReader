package com.tedd.teddreader.core.data.parser

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** [defaultPdfMetadataReader] 계약에 대한 Android 구현. */
internal actual fun defaultPdfMetadataReader(): PdfMetadataReader = AndroidPdfMetadataReader()

/**
 * `android.graphics.pdf.PdfRenderer` 위에 구축된 Android용 [PdfMetadataReader]. `PdfRenderer`는
 * [ParcelFileDescriptor]가 필요하므로, 이 구현은 다음 우선순위로 하나를 해석한다:
 *
 * 1. [DocumentLocation.sourceUri]가 읽을 수 있는 파일을 가리키는 `file://` URI일 때, 그 파일이 복사도
 *    임시 파일도 불필요한 I/O도 없이 직접 열린다. 임포트된 모든 문서는 이 리더가 호출되기 전에 앱
 *    전용 저장소로 로컬 파일로 구체화되므로, 이것이 임포트 이후의 정상 경로다.
 * 2. 그렇지 않으면, [bytes]가(null이 아니면) 임시 파일에 기록되고, 사용 직후 즉시 삭제된다. 이 폴백은
 *    문서가 아직 구체화되지 않은 `content://` URI로 도착한 일시적인 경우(예: 바이트가 아직 메모리에
 *    있는 Google Drive 다운로드)와, 바이트를 무조건 넘기는 레거시 호출자를 커버한다.
 * 3. 파일 경로에 접근할 수 없고 [bytes]도 null이면, 예외를 던지지 않고 안전한 기본값(페이지 수는 1,
 *    표지는 null)이 반환된다.
 */
class AndroidPdfMetadataReader : PdfMetadataReader {
    /**
     * @param location 문서의 위치. [DocumentLocation.sourceUri]가 존재하는, 읽을 수 있는 파일을
     *   가리키는 `file://` URI라면, [bytes]를 건드리지 않고 그 파일이 직접 열린다.
     * @param bytes 폴백으로 쓰이는 문서의 원본 바이트. [location]을 직접 열 수 없을 때만 [PdfRenderer]가
     *   열도록 임시 파일에 기록된다. 호출자가 [location]이 접근 가능한 로컬 파일임을 보장하면 null.
     * @return 페이지 수. PDF를 전혀 열거나 렌더링할 수 없었으면(손상된 파일, I/O 실패, 바이트 폴백 없이
     *   접근 불가능한 위치) `1` — 이 메서드는 절대 던지지 않는다.
     */
    override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int =
        withPdfRenderer(location, bytes) { renderer ->
            renderer.pageCount.coerceAtLeast(1)
        } ?: 1

    /**
     * @param location 문서의 위치. [DocumentLocation.sourceUri]가 존재하는, 읽을 수 있는 파일을
     *   가리키는 `file://` URI라면, [bytes]를 건드리지 않고 그 파일이 직접 열린다.
     * @param bytes 폴백으로 쓰이는 문서의 원본 바이트. [location]을 직접 열 수 없을 때만 [PdfRenderer]가
     *   열도록 임시 파일에 기록된다. 호출자가 [location]이 접근 가능한 로컬 파일임을 보장하면 null.
     * @return 첫 페이지를 PNG로 인코딩한 썸네일. 종횡비를 유지하면서 360×480 영역에 맞도록 축소만
     *   되며(확대되지 않는다), 문서에 페이지가 없거나 페이지에 쓸 수 있는 크기가 없거나 어떤 이유로든
     *   렌더링이 실패하면 `null`.
     */
    override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? =
        withPdfRenderer(location, bytes) { renderer ->
            if (renderer.pageCount <= 0) return@withPdfRenderer null
            renderer.openPage(0).use { page ->
                if (page.width <= 0 || page.height <= 0) return@withPdfRenderer null
                val scale = minOf(360f / page.width.toFloat(), 480f / page.height.toFloat(), 1f)
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

    /**
     * 이 클래스 자체 문서에 설명된 위치 우선 전략으로 [PdfRenderer]를 열고, 그것으로 [block]을
     * 실행한 뒤 결과를 반환한다. 렌더러와 그 뒤의 [ParcelFileDescriptor]는 항상 닫히며, bytes 폴백을
     * 위해 만들어진 임시 파일은 항상 삭제된다.
     *
     * @param location 먼저 열어볼 문서의 위치.
     * @param bytes [location]을 직접 열 수 없을 때 임시 파일로 구체화할 폴백 바이트.
     * @param block 열린 렌더러로 수행할 작업.
     * @return [block]의 결과, 또는 어떤 렌더러도 열 수 없었으면(위치에 접근 불가능하고 bytes도
     *   null이거나, 어떤 I/O 실패가 있었으면) null.
     */
    private fun <T> withPdfRenderer(
        location: DocumentLocation,
        bytes: ByteArray?,
        block: (PdfRenderer) -> T,
    ): T? {
        val localFile = resolveLocalFile(location)
        if (localFile != null) {
            return try {
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use(block)
                }
            } catch (_: Throwable) {
                null
            }
        }
        if (bytes == null) return null
        val tempFile = File.createTempFile("tedd-reader", ".pdf")
        return try {
            tempFile.writeBytes(bytes)
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use(block)
            }
        } catch (_: Throwable) {
            null
        } finally {
            tempFile.delete()
        }
    }

    /**
     * [DocumentLocation.sourceUri]가 대상이 존재하고 읽을 수 있는 `file://` URI일 때, [location]을
     * 로컬 [File]로 해석한다. `content://` URI, 존재하지 않는 파일, 이 구현이 직접 열 수 없는 다른
     * 스킴에 대해서는 null을 반환한다.
     *
     * @param location 해석할 문서 위치.
     * @return 읽을 수 있는 [File], 또는 직접 접근이 불가능하면 null.
     */
    private fun resolveLocalFile(location: DocumentLocation): File? {
        val uri = location.sourceUri
        if (!uri.startsWith("file://")) return null
        val path = uri.removePrefix("file://")
        val file = File(path)
        return file.takeIf { it.exists() && it.canRead() }
    }
}
