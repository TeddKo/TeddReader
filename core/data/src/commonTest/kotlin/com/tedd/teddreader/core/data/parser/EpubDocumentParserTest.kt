package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

/**
 * [EpubDocumentParser]의 챕터-문서 조립([EpubDocumentParser.parseChapters])과 OPF 표지 조회 폴백
 * 체인([findEpubCoverHref])을, 실제 ZIP 아카이브 없이 고정한다 — 아카이브를 여는 경로
 * ([EpubDocumentParser.parseWithCover])는 실제 EPUB 파일을 만들 수 있는 androidHostTest
 * 스위트에서 별도로 커버된다.
 */
class EpubDocumentParserTest {
    private val parser = EpubDocumentParser()

    /**
     * 챕터는 텍스트에서 헤딩이 자신만의 블록으로 빠져나온 섹션이 된다 — 순진한 태그 제거와
     * 달리, 더 이상 뒤따르는 문단으로 흘러들어가지 않는다.
     */
    @Test
    fun parsesChaptersIntoReadableSections() {
        val document = parser.parseChapters(
            id = DocumentId("epub-1"),
            title = "Book",
            chapters = listOf(
                EpubChapter("Intro", "<html><body><h1>Intro</h1><p>Hello&nbsp;reader</p></body></html>"),
                EpubChapter("Next", "<p>Second &amp; chapter</p>"),
            ),
        )

        assertEquals(DocumentFormat.EPUB, document.format)
        assertEquals(2, document.sections.size)
        assertEquals("Intro\n\nHello reader", document.sections.first().text)
        assertEquals("Second & chapter", document.sections[1].text)
        assertEquals(
            listOf(ReaderBlockKind.HEADING, ReaderBlockKind.PARAGRAPH, ReaderBlockKind.PARAGRAPH),
            document.blocks.map { it.kind },
        )
    }

    /**
     * 회귀 가드: 각 블록의 범위(range)는 하나의 개행으로 합쳐졌을 때의 섹션 텍스트를 정확히
     * 가리켜야 한다 — [EpubDocumentParser.parseChapters] 자신이 실행 중인 오프셋을 전진시킬 때
     * 수행하는 것과 같은 결합이다.
     */
    @Test
    fun blockRangesIndexTheSectionsAsTheyAreJoinedForReading() {
        val document = parser.parseChapters(
            id = DocumentId("epub-2"),
            title = "Book",
            chapters = listOf(
                EpubChapter("One", "<p>alpha</p>"),
                EpubChapter("Two", "<p>beta</p>"),
            ),
        )

        val joined = document.sections.joinToString(separator = "\n") { section -> section.text }
        assertEquals(
            listOf("alpha", "beta"),
            document.blocks.map { block -> joined.substring(block.range.start.toInt(), block.range.end.toInt()) },
        )
        assertEquals(
            listOf("alpha", "beta"),
            document.sections.map { section ->
                joined.substring(section.range.start.toInt(), section.range.end.toInt())
            },
        )
    }

    /**
     * EPUB 3의 `properties="cover-image"` 매니페스트 항목이 표지로 발견된다 — 책이 표지를 선언하는
     * 세 가지 방법 중 가장 명시적인 것이다.
     */
    @Test
    fun findsCoverHrefFromEpub3CoverImageProperty() {
        val opf = """
            <package>
              <manifest>
                <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="chapter" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals("images/cover.jpg", findEpubCoverHref(opf))
    }

    /**
     * EPUB 3 속성이 없을 때, 매니페스트 id를 가리키는 EPUB 2의 `<meta name="cover" content="...">`가
     * 표지로 발견된다.
     */
    @Test
    fun findsCoverHrefFromEpub2MetaCoverId() {
        val opf = """
            <package>
              <metadata>
                <meta name="cover" content="cover-image-id"/>
              </metadata>
              <manifest>
                <item id="cover-image-id" href="images/cover.png" media-type="image/png"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals("images/cover.png", findEpubCoverHref(opf))
    }

    /**
     * EPUB 3도 EPUB 2 표지 메타데이터도 없을 때, 자신의 id가 표지임을 암시하는 래스터 항목이 여전히
     * 발견된다.
     */
    @Test
    fun fallsBackToRasterItemWithCoverHint() {
        val opf = """
            <package>
              <manifest>
                <item id="front-cover" href="images/front-cover.webp" media-type="image/webp"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals("images/front-cover.webp", findEpubCoverHref(opf))
    }

    /**
     * 표지 메타데이터도 표지 힌트가 있는 항목도 전혀 없는 OPF는, 아무것도 추측하지 않고 표지 없음으로
     * 해석된다.
     */
    @Test
    fun returnsNullWhenNoCoverExists() {
        val opf = """
            <package>
              <manifest>
                <item id="chapter" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals(null, findEpubCoverHref(opf))
    }

    /**
     * 매니페스트 재사용 리팩터링에 대한 회귀 가드: `opfPath`를 받는 [findEpubCoverHref] 오버로드는
     * 여전히 표지 항목의 `href`를 OPF 자신이 위치한 곳을 기준으로 해석하므로, 중첩된 OPF에 상대적으로
     * 쓰인 표지가 올바른 컨테이너 경로에 도달한다 — 리팩터링 이전의 조회가 만들어내던 것과 같은
     * 결과다.
     */
    @Test
    fun resolvesCoverHrefRelativeToTheOpfPath() {
        val opf = """
            <package>
              <manifest>
                <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals("OPS/images/cover.jpg", findEpubCoverHref(opf, "OPS/content.opf".toPath()))
    }
}
