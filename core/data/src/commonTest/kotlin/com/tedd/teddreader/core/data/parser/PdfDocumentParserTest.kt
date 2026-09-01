package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [PdfDocumentParser]가 주입된 [PdfMetadataReader]에 위임하는 계약을 고정한다: 리더의 페이지 수와 표지
 * 바이트는 변형 없이 그대로 통과하고, 유효하지 않은(0 이하) 페이지 수는 페이지 없는 문서를 만드는 대신
 * 1로 바닥값이 매겨진다. 위치 우선 계약도 함께 검증한다: [DocumentLocation.sourceUri]가 접근 가능한
 * 로컬 파일을 가리킬 때 호출자는 `bytes = null`을 넘길 수 있다.
 */
class PdfDocumentParserTest {
    /**
     * 이 파일의 모든 테스트가 공유하는 고정 [DocumentLocation] 픽스처. 필드 값 자체는 파서에서 검증되지
     * 않고, 그저 가짜 [PdfMetadataReader]로 그대로 전달될 뿐이다.
     */
    private val location = DocumentLocation(
        sourceUri = "file:///book.pdf",
        displayName = "Book.pdf",
        mimeType = "application/pdf",
        sizeBytes = 12L,
    )

    /**
     * 파서의 페이지 수와 포맷은 주입된 [PdfMetadataReader]에서 그대로 나오며, 섹션은 만들어지지 않는다.
     * 바이트는 변형 없이 리더로 그대로 전달된다.
     */
    @Test
    fun usesPlatformMetadataReaderPageCount() {
        val bytes = byteArrayOf(1, 2, 3)
        val parser = PdfDocumentParser { passedLocation, passedBytes ->
            assertEquals(location, passedLocation)
            assertContentEquals(bytes, passedBytes)
            7
        }

        val document = parser.parse(
            id = DocumentId("pdf-1"),
            title = "Book",
            location = location,
            bytes = bytes,
        )

        assertEquals(DocumentFormat.PDF, document.format)
        assertEquals(7, document.pageCount)
        assertEquals(0, document.sections.size)
    }

    /**
     * 회귀 가드: 플랫폼 리더가 0페이지를 보고하면(손상되었거나 읽을 수 없는 PDF) 페이지 없는 문서를
     * 만들면 안 된다 — 개수는 1로 바닥값이 매겨진다.
     */
    @Test
    fun coercesInvalidPlatformPageCountToOne() {
        val parser = PdfDocumentParser { _, _ -> 0 }

        val document = parser.parse(
            id = DocumentId("pdf-1"),
            title = "Book",
            location = location,
            bytes = byteArrayOf(),
        )

        assertEquals(1, document.pageCount)
    }

    /** 플랫폼 리더의 표지 바이트는 [PdfDocumentParser.coverImageBytes]를 변형 없이 그대로 통과한다. */
    @Test
    fun passesThroughPlatformCoverBytes() {
        val bytes = byteArrayOf(9, 8, 7)
        val parser = PdfDocumentParser(
            object : PdfMetadataReader {
                override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int = 1
                override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? = bytes
            },
        )

        assertContentEquals(
            bytes,
            parser.coverImageBytes(location, bytes),
        )
    }

    /**
     * bytes가 null이면(위치 우선 경로), 파서는 null을 메타데이터 리더로 그대로 넘기고, 리더는 대신
     * [DocumentLocation.sourceUri]로부터 문서를 해석한다.
     */
    @Test
    fun passesNullBytesToMetadataReaderWhenLocationFirst() {
        var receivedBytes: ByteArray? = byteArrayOf(99)
        val parser = PdfDocumentParser { passedLocation, passedBytes ->
            assertEquals(location, passedLocation)
            receivedBytes = passedBytes
            5
        }

        val document = parser.parse(
            id = DocumentId("pdf-2"),
            title = "Book",
            location = location,
            bytes = null,
        )

        assertEquals(5, document.pageCount)
        assertNull(receivedBytes)
    }

    /**
     * bytes가 null인 표지 추출은 null과 함께 [PdfMetadataReader.coverImageBytes]에 위임되어, 플랫폼
     * 리더가 위치로부터 해석한다. bytes가 null일 때 null을 반환하는 가짜가 그 null이 그대로
     * 전달됐음을 확인해 준다.
     */
    @Test
    fun coverImageBytesWithNullBytesDelegatesToLocation() {
        val parser = PdfDocumentParser(
            object : PdfMetadataReader {
                override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int = 1
                override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? {
                    return if (bytes == null) byteArrayOf(1, 2, 3) else null
                }
            },
        )

        val result = parser.coverImageBytes(location, null)
        assertContentEquals(byteArrayOf(1, 2, 3), result)
    }

    /**
     * 뮤테이션 가드: 위치만 있는 호출(bytes = null)과 bytes가 주어진 호출을 구분하는 메타데이터
     * 리더가 파서를 거쳐 서로 다른 결과를 내놓는지 검증해, 파서가 조용히 기본값을 채우거나 null을
     * 무시하지 않음을 증명한다.
     */
    @Test
    fun metadataReaderReceivesDistinctBytesPresence() {
        var pageCountCallBytes: ByteArray? = byteArrayOf(99)
        var coverCallBytes: ByteArray? = byteArrayOf(99)
        val parser = PdfDocumentParser(
            object : PdfMetadataReader {
                override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int {
                    pageCountCallBytes = bytes
                    return if (bytes == null) 10 else 20
                }

                override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? {
                    coverCallBytes = bytes
                    return if (bytes == null) byteArrayOf(0xA) else byteArrayOf(0xB)
                }
            },
        )

        val documentLocationOnly = parser.parse(
            id = DocumentId("pdf-loc"),
            title = "Book",
            location = location,
            bytes = null,
        )
        assertEquals(10, documentLocationOnly.pageCount)
        assertNull(pageCountCallBytes)

        val coverLocationOnly = parser.coverImageBytes(location, null)
        assertContentEquals(byteArrayOf(0xA), coverLocationOnly)
        assertNull(coverCallBytes)

        val documentWithBytes = parser.parse(
            id = DocumentId("pdf-bytes"),
            title = "Book",
            location = location,
            bytes = byteArrayOf(1),
        )
        assertEquals(20, documentWithBytes.pageCount)
        assertContentEquals(byteArrayOf(1), pageCountCallBytes)

        val coverWithBytes = parser.coverImageBytes(location, byteArrayOf(2))
        assertContentEquals(byteArrayOf(0xB), coverWithBytes)
        assertContentEquals(byteArrayOf(2), coverCallBytes)
    }
}
