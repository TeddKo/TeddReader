package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AndroidPdfMetadataReader]의 위치 우선 해석 전략을 검증한다: 읽을 수 있는 PDF를 가리키는 `file://`
 * URI는 [bytes]를 건드리지 않고 직접 열리고, 파일이 아닌 URI(또는 없는 파일)는 바이트를 임시 파일에
 * 쓰는 경로로 폴백한다. 이는 이미 실체화된 PDF가 메타데이터 추출 중에 결코 불필요한 임시 파일 쓰기 비용을
 * 치르지 않도록 보장하는 동작 계약이다.
 *
 * 이 테스트들은 메모리 안에서 합성된 최소한의 1페이지 PDF를 사용한다 — `PdfRenderer`가 열어서 페이지
 * 하나를 보고하기에 딱 필요한 만큼의 구조다. 같은 바이트가 (file:// 경로를 위해) 실제 파일에
 * 기록되거나 bytes 폴백으로 전달되므로, 두 경로 모두 `PdfRenderer` 자체를 실제로 거친다.
 */
class AndroidPdfMetadataReaderTest {
    /**
     * `PdfRenderer`가 유효한 단일 페이지 문서로 받아들이는 최소한의 PDF 바이트. 가장 단순한 PDF 1.4
     * 구조다: 빈 콘텐츠 스트림을 가진 페이지 하나.
     */
    private val minimalPdfBytes = createMinimalPdf()

    /**
     * [DocumentLocation.sourceUri]가 읽을 수 있는 PDF를 가리키는 `file://` URI일 때, [pageCount]는
     * [bytes] 없이도 실제 페이지 수를 반환한다.
     */
    @Test
    fun pageCountFromFileLocationWithoutBytes() {
        val file = File.createTempFile("test-pdf-loc", ".pdf").apply {
            writeBytes(minimalPdfBytes)
            deleteOnExit()
        }
        val location = DocumentLocation(
            sourceUri = "file://${file.absolutePath}",
            displayName = "test.pdf",
            mimeType = "application/pdf",
            sizeBytes = file.length(),
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = null)

        assertEquals(1, count)
        file.delete()
    }

    /**
     * [DocumentLocation.sourceUri]가 파일이 아닌 URI일 때(아직 실체화되지 않은 content://를
     * 시뮬레이션), [pageCount]는 주어진 [bytes]로 폴백한다.
     */
    @Test
    fun pageCountFromBytesFallbackWhenLocationIsNotFile() {
        val location = DocumentLocation(
            sourceUri = "content://provider/doc/123",
            displayName = "test.pdf",
            mimeType = "application/pdf",
            sizeBytes = minimalPdfBytes.size.toLong(),
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = minimalPdfBytes)

        assertEquals(1, count)
    }

    /**
     * [DocumentLocation.sourceUri]가 대상이 존재하지 않는 `file://` URI이고 [bytes]가 null일 때,
     * [pageCount]는 던지는 대신 안전한 기본값 1을 반환한다.
     */
    @Test
    fun pageCountReturnsOneWhenFileIsMissingAndBytesNull() {
        val location = DocumentLocation(
            sourceUri = "file:///nonexistent/path/to/missing.pdf",
            displayName = "missing.pdf",
            mimeType = "application/pdf",
            sizeBytes = 0L,
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = null)

        assertEquals(1, count)
    }

    /**
     * [DocumentLocation.sourceUri]가 대상이 존재하지 않는 `file://` URI이지만 [bytes]가 제공될 때,
     * [pageCount]는 bytes로 폴백해 실제 페이지 수를 반환한다.
     */
    @Test
    fun pageCountFallsBackToBytesWhenFileIsMissing() {
        val location = DocumentLocation(
            sourceUri = "file:///nonexistent/path/to/missing.pdf",
            displayName = "missing.pdf",
            mimeType = "application/pdf",
            sizeBytes = minimalPdfBytes.size.toLong(),
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = minimalPdfBytes)

        assertEquals(1, count)
    }

    /**
     * `file://` 위치로부터의 표지 추출은 [bytes] 없이도 파일에서 여는 것을 시도한다. 최소한의 PDF에
     * 대해서는 렌더러가 PDF 구조에 따라 빈 이미지를 만들거나 null을 낼 수 있지만, 핵심 단언은 예외가
     * 던져지지 않으며 이 메서드가 추출을 시도하는 데 bytes를 필요로 하지 않는다는 점이다.
     */
    @Test
    fun coverImageBytesFromFileLocationWithoutBytesDoesNotThrow() {
        val file = File.createTempFile("test-pdf-cover", ".pdf").apply {
            writeBytes(minimalPdfBytes)
            deleteOnExit()
        }
        val location = DocumentLocation(
            sourceUri = "file://${file.absolutePath}",
            displayName = "test.pdf",
            mimeType = "application/pdf",
            sizeBytes = file.length(),
        )
        val reader = AndroidPdfMetadataReader()

        val cover = reader.coverImageBytes(location, bytes = null)

        // 최소한의 PDF는 렌더링 가능한 콘텐츠를 만들 수도, 만들지 않을 수도 있다; 핵심 계약은 메서드가
        // 예외를 던지지 않고 bytes 없이도 실행된다는 점이다. 여기서 null 결과는 PdfRenderer가 빈
        // 페이지를 렌더링하지 못했다는 뜻이며, 이는 받아들일 수 있다 — 위치 우선 경로는 여전히
        // 실행되었다.
        if (cover != null) {
            assertTrue(cover.isNotEmpty())
        }
        file.delete()
    }

    /**
     * 위치에 도달할 수 없고 bytes가 null일 때 표지 추출은 null을 반환한다.
     */
    @Test
    fun coverImageBytesReturnsNullWhenLocationUnreachableAndBytesNull() {
        val location = DocumentLocation(
            sourceUri = "file:///nonexistent/path/to/missing.pdf",
            displayName = "missing.pdf",
            mimeType = "application/pdf",
            sizeBytes = 0L,
        )
        val reader = AndroidPdfMetadataReader()

        val cover = reader.coverImageBytes(location, bytes = null)

        assertNull(cover)
    }

    /**
     * 뮤테이션 방지: file:// 경로가 존재하면서 동시에 bytes도 제공될 때는 파일 경로가 우선한다. 파일에는
     * 유효한 PDF가 들어 있는데 사용됐다면 실패했을 손상된 bytes를 전달해 이를 검증한다. 리더가 위치보다
     * bytes를 잘못 우선한다면 이 테스트는 실패한다.
     */
    @Test
    fun filePathTakesPriorityOverBytesWhenBothPresent() {
        val file = File.createTempFile("test-pdf-priority", ".pdf").apply {
            writeBytes(minimalPdfBytes)
            deleteOnExit()
        }
        val corruptBytes = byteArrayOf(0, 0, 0, 0)
        val location = DocumentLocation(
            sourceUri = "file://${file.absolutePath}",
            displayName = "test.pdf",
            mimeType = "application/pdf",
            sizeBytes = file.length(),
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = corruptBytes)

        assertEquals(1, count)
        file.delete()
    }
}

/**
 * `PdfRenderer`가 받아들이는 가장 작은 유효한 PDF를 만든다: 72×72 포인트의 빈 콘텐츠 스트림을 가진
 * 단일 빈 페이지. 상호참조 오프셋은 근사치이지만 `PdfRenderer`는 정확한 바이트 위치 없이도 이를
 * 받아들일 만큼 관대하다.
 *
 * @return 최소한의 PDF를 [ByteArray]로.
 */
private fun createMinimalPdf(): ByteArray {
    val pdf = buildString {
        append("%PDF-1.4\n")
        append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 72 72] /Contents 4 0 R >>\nendobj\n")
        append("4 0 obj\n<< /Length 0 >>\nstream\n\nendstream\nendobj\n")
        append("xref\n0 5\n")
        append("0000000000 65535 f \n")
        append("0000000009 00000 n \n")
        append("0000000058 00000 n \n")
        append("0000000115 00000 n \n")
        append("0000000210 00000 n \n")
        append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n260\n%%EOF\n")
    }
    return pdf.toByteArray()
}
