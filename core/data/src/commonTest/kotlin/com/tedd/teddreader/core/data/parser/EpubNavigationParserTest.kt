package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EPUB 3의 nav 문서([parseEpubNavDocument])와 EPUB 2의 NCX([parseNcxDocument]) 모두
 * 동일한 [ParsedNavigation] 구조로 파싱되는 방식을 고정한다: 헤딩 레이블, 항목별 중첩 레이블/레벨/href,
 * 그리고 링크 텍스트 내 인라인 이미지의 `alt` 텍스트가 해당 링크의 레이블로 합쳐지는 것을 포함한다.
 */
class EpubNavigationParserTest {
    /**
     * nav 문서의 헤딩, 중첩된 `<li>` 레벨, 그리고 링크 텍스트 내 인라인 `<img alt>`가
     * 모두 올바르게 파싱됨을 검증한다.
     */
    @Test
    fun parsesEpub3NavHeadingNestedLabelsAndInlineImageAlt() {
        val parsed = parseEpubNavDocument(
            """
            <html><body>
              <nav epub:type="landmarks toc">
                <h2>Contents</h2>
                <ol>
                  <li><a href="text/ch1.xhtml">Chapter <img src="icon.png" alt="One"/></a>
                    <ol>
                      <li><a href="text/ch1.xhtml#scene">Scene <span>One</span></a></li>
                    </ol>
                  </li>
                </ol>
              </nav>
            </body></html>
            """.trimIndent(),
        )

        assertEquals("Contents", parsed.heading)
        assertEquals(listOf("Chapter One", "Scene One"), parsed.entries.map { it.title })
        assertEquals(listOf(1, 2), parsed.entries.map { it.level })
        assertEquals(listOf("text/ch1.xhtml", "text/ch1.xhtml#scene"), parsed.entries.map { it.href })
    }

    /** NCX의 `docTitle`과 중첩된 `navPoint` 레이블 및 레벨이 올바르게 파싱됨을 검증한다. */
    @Test
    fun parsesNcxDocTitleAndNestedLabels() {
        val parsed = parseNcxDocument(
            """
            <ncx>
              <docTitle><text>Guide</text></docTitle>
              <navMap>
                <navPoint id="n1">
                  <navLabel><text>Chapter 1</text></navLabel>
                  <content src="text/ch1.xhtml"/>
                  <navPoint id="n1-1">
                    <navLabel><text>Scene 1</text></navLabel>
                    <content src="text/ch1.xhtml#scene"/>
                  </navPoint>
                </navPoint>
              </navMap>
            </ncx>
            """.trimIndent(),
        )

        assertEquals("Guide", parsed.heading)
        assertEquals(listOf("Chapter 1", "Scene 1"), parsed.entries.map { it.title })
        assertEquals(listOf(1, 2), parsed.entries.map { it.level })
    }
}
