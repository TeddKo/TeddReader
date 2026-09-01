package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 선택기 필터와 형식 목록이 이 리더가 여는 네 종류, 즉 재배치 가능 텍스트, 고정 페이지, 만화, 래스터 이미지를 계속 포함하도록 고정한다. 형식을 추가했을 때 파일 선택기에서 조용히 선택할 수 없게 되는 일을 막는다.
 */
class SupportedDocumentTypesTest {
    @Test
    fun supportedDocumentTypesExposeTextFixedPageComicAndRasterImages() {
        assertEquals(
            setOf(
                DocumentFormat.TXT,
                DocumentFormat.PDF,
                DocumentFormat.EPUB,
                DocumentFormat.CBZ,
                DocumentFormat.IMAGE,
            ),
            SupportedDocumentFormats,
        )
        assertTrue(SupportedDocumentMimeTypes.contains("text/plain"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/pdf"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/epub"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/epub+zip"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/vnd.comicbook+zip"))
        assertTrue(SupportedDocumentMimeTypes.contains("image/jpeg"))
        assertTrue(SupportedDocumentMimeTypes.contains("image/png"))
        assertEquals(
            setOf(
                "text/plain",
                "application/pdf",
                "application/epub",
                "application/epub+zip",
                "application/vnd.comicbook+zip",
                "application/x-cbz",
                "application/zip",
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
                "image/bmp",
            ),
            GoogleDriveSupportedDocumentMimeTypes,
        )
        assertEquals(
            setOf("txt", "pdf", "epub", "cbz", "jpg", "jpeg", "png", "webp", "gif", "bmp"),
            SupportedDocumentExtensions,
        )
        assertFalse(SupportedDocumentFormats.contains(DocumentFormat.UNKNOWN))
    }
}
