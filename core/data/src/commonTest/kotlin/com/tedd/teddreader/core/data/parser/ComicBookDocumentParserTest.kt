package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 실제 ZIP 아카이브를 전혀 열지 않고 CBZ의 페이지 목록이 어떻게 만들어지는지를 고정한다: 메타데이터와
 * 리소스 포크 항목은 걸러지고, 페이지는 자연스러운 읽기 순서로 정렬되며 `cover`라는 이름의 항목은
 * 강제로 맨 앞에 오고, 페이지 수만으로도 유효한 코믹
 * [com.tedd.teddreader.core.common.model.ReaderDocument]를 만들기에 충분하다.
 * 실제로 아카이브를 여는 것은 계측 테스트로 별도 커버된다.
 */
class ComicBookDocumentParserTest {
    /**
     * 회귀 가드: `__MACOSX/` 메타데이터와 AppleDouble `._` 접두사 항목, 그리고 이미지가 아닌 파일은
     * 제거되어야 한다; `cover`라는 이름 그대로인 항목은 항상 맨 먼저 정렬되어야 한다; 그리고 나머지
     * 페이지는 일반 문자열 순서가 아니라 자연스러운 숫자 순서로 정렬되어야 한다(`page10`보다
     * `page2`가 먼저).
     */
    @Test
    fun comicPageNamesFilterMetadataAndUseNaturalReadingOrder() {
        assertEquals(
            listOf("cover.jpg", "chapter/page1.png", "chapter/page2.png", "chapter/page10.png"),
            sortedComicPageNames(
                listOf(
                    "chapter/page10.png",
                    "__MACOSX/._page1.jpg",
                    "notes.txt",
                    "chapter/page2.png",
                    "cover.jpg",
                    "chapter/page1.png",
                ),
            ),
        )
    }

    /**
     * 합성된 CBZ 문서는 주어진 페이지 수와 CBZ 포맷을 가지며, 섹션은 전혀 없다 — 코믹은 텍스트가
     * 없다.
     */
    @Test
    fun comicMetadataUsesImageCountAsPageCount() {
        val document = comicReaderDocument(
            id = DocumentId("comic-1"),
            title = "Comic.cbz",
            pageCount = 12,
        )

        assertEquals(DocumentFormat.CBZ, document.format)
        assertEquals(12, document.pageCount)
        assertEquals(emptyList(), document.sections)
    }
}
