package com.tedd.teddreader.core.data.parser

import kotlin.random.Random
import kotlin.test.Test
import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.FileSystem

/** iOS PDF 바이트 폴백이 작업 실패에도 임시 파일을 지우고 치명 오류를 전파하는지 검증한다. */
class IosPdfMetadataReaderTest {
    /** PDF 작업이 실패해도 그 전에 기록된 임시 파일은 남지 않는다. */
    @Test
    fun temporaryPdfFileIsDeletedWhenBlockFails() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ios-pdf-cleanup-${Random.nextLong().toString(16)}.pdf"

        assertFailsWith<IllegalStateException> {
            withTemporaryPdfFile(FileSystem.SYSTEM, path, byteArrayOf(1)) {
                assertTrue(FileSystem.SYSTEM.exists(path))
                error("stop")
            }
        }

        assertFalse(FileSystem.SYSTEM.exists(path))
    }

    /** 메모리 고갈은 손상 PDF처럼 null로 축소하지 않고 호출자까지 전파된다. */
    @Test
    fun outOfMemoryErrorIsRethrown() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ios-pdf-error-${Random.nextLong().toString(16)}.pdf"

        assertFailsWith<OutOfMemoryError> {
            withTemporaryPdfFile(FileSystem.SYSTEM, path, byteArrayOf(1)) {
                throw OutOfMemoryError("exhausted")
            }
        }

        assertFalse(FileSystem.SYSTEM.exists(path))
    }

    /** 존재하지 않는 경로와 바이트 폴백이 없으면 PDFDocument 생성 실패가 예외 대신 기본 페이지 수 1이 된다. */
    @Test
    fun missingFileWithoutBytesFallsBackToSinglePage() {
        assertEquals(1, IosPdfMetadataReader().pageCount(missingLocation(), bytes = null))
    }

    /** 존재하지 않는 경로에 유효하지 않은 폴백 바이트가 주어져도 예외 없이 기본 페이지 수 1이다. */
    @Test
    fun missingFileWithInvalidBytesFallsBackToSinglePage() {
        assertEquals(1, IosPdfMetadataReader().pageCount(missingLocation(), byteArrayOf(1, 2, 3)))
    }

    /** 경로를 열 수 없으면 유효한 PDF 바이트 폴백으로 실제 페이지 수를 읽는다. */
    @Test
    fun missingFileWithValidBytesUsesFallback() {
        assertEquals(2, IosPdfMetadataReader().pageCount(missingLocation(), TwoPagePdf.encodeToByteArray()))
    }

    private fun missingLocation() = DocumentLocation(
        sourceUri = "file:///nonexistent-${Random.nextLong().toString(16)}/missing.pdf",
        displayName = "missing.pdf",
    )
}

/** 페이지가 두 개인 최소 PDF 원문; xref는 PDFKit이 재구성한다. */
private val TwoPagePdf = """%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>
endobj
4 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>
endobj
trailer
<< /Root 1 0 R /Size 5 >>
%%EOF
"""
