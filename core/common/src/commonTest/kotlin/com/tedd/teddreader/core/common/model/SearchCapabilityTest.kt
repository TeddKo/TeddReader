package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 리더가 문서 검색을 제공하는 조건을 고정한다.
 *
 * 두 경우 모두 "결과 없음"만 답할 수 있는 검색을 제공하지 않기 위한 것이다. 추출된 텍스트가 없는 PDF에는 일치할 내용이 없고, 섹션은 있지만 아직 공백인 책은 빈 책이 아니라 가져오기 중이다.
 */
class SearchCapabilityTest {
    @Test
    fun pdfSearchIsNotReportedAsSupportedWithoutExtractedText() {
        val pdf = ReaderDocument(
            id = DocumentId("pdf-1"),
            format = DocumentFormat.PDF,
            title = "PDF",
            sections = emptyList(),
            pageCount = 1,
        )

        assertFalse(pdf.isTextSearchSupported())
    }

    @Test
    fun textDocumentSearchIsSupportedWhenSectionsHaveText() {
        val txt = ReaderDocument(
            id = DocumentId("txt-1"),
            format = DocumentFormat.TXT,
            title = "TXT",
            sections = listOf(
                ReaderSection(index = 0, text = "hello", range = TextRange(0, 5)),
            ),
        )

        assertTrue(txt.isTextSearchSupported())
    }
}
