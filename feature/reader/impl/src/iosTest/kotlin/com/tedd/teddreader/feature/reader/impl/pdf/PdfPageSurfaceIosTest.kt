package com.tedd.teddreader.feature.reader.impl.pdf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class PdfPageSurfaceIosTest {
    /** percent-encoding을 포함한 file URL이 PDFKit에 전달할 실제 파일 경로로 디코딩되는지 검증한다. */
    @Test
    fun fileUrlResolvesDecodedPath() {
        assertEquals("/tmp/My Book.pdf", readerPdfFilePath("file:///tmp/My%20Book.pdf"))
    }

    /** 인코딩 없이 저장된 `#` 포함 파일 이름이 fragment로 잘리지 않고 실제 파일 경로로 해석되는지 검증한다. */
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun rawFileNameWithHashResolvesToExistingFile() {
        val path = NSTemporaryDirectory().trimEnd('/') + "/teddreader-pdf#1?a%20b.pdf"
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, null, null))
        try {
            assertEquals(path, readerPdfFilePath("file://$path"))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    /** file URL이 아닌 URI가 로컬 PDF 경로로 오인되지 않는지 검증한다. */
    @Test
    fun nonFileUrlIsRejected() {
        assertNull(readerPdfFilePath("https://example.com/book.pdf"))
        assertNull(readerPdfFilePath("not a url"))
    }

    @Test
    fun nativePdfNavigatesOnlyWhenTheTargetPageChanges() {
        val page = Any()
        assertFalse(readerPdfShouldNavigate(currentPage = page, targetPage = page))
        assertTrue(readerPdfShouldNavigate(currentPage = Any(), targetPage = page))
        assertFalse(readerPdfShouldNavigate(currentPage = null, targetPage = null))
    }
}
