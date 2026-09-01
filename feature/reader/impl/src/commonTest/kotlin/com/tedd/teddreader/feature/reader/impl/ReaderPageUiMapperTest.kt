package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderPageUiMapperTest {
    @Test
    fun facingUsesMountWindow() {
        val windows = listOf(
            PageWindow(PageIndex(0, 4), ReaderLocation.TextOffset(0), "a", TextRange(0, 1)),
            PageWindow(PageIndex(1, 4), ReaderLocation.TextOffset(1), "b", TextRange(1, 2)),
            PageWindow(PageIndex(2, 4), ReaderLocation.TextOffset(2), "c", TextRange(2, 3)),
            PageWindow(PageIndex(3, 4), ReaderLocation.TextOffset(3), "d", TextRange(3, 4)),
        )
        val facing = readerPageFacingUi(
            ReaderPageUiContext(
                pageIndex = PageIndex(1, 4),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(windows),
                embeddedImages = emptyMap(),
                embeddedFontFiles = emptyMap(),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = emptySet(),
            ),
        )
        assertEquals("b", facing.current.text)
        assertEquals(listOf(0, 1, 2, 3), facing.slots.map { it.page })
    }

    @Test
    fun pageUiFiltersEmbeddedFontsToTheCurrentPage() {
        val block = ReaderBlock(
            kind = ReaderBlockKind.PARAGRAPH,
            range = TextRange(0, 4),
            style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
            spans = listOf(
                ReaderSpan(range = TextRange(1, 3), styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf")),
            ),
        )
        val page = readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "test", TextRange(0, 4), blocks = listOf(block)),
                    ),
                ),
                embeddedImages = emptyMap(),
                embeddedFontFiles = mapOf(
                    "fonts/body.otf" to "/tmp/body.otf",
                    "fonts/inline.otf" to "/tmp/inline.otf",
                    "fonts/other.otf" to "/tmp/other.otf",
                ),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = setOf("fonts/inline.otf"),
            ),
        )

        assertEquals(
            mapOf("fonts/body.otf" to "/tmp/body.otf", "fonts/inline.otf" to "/tmp/inline.otf"),
            page?.embeddedFontFiles?.toMap(),
        )
        assertEquals(setOf("fonts/inline.otf"), page?.failedEmbeddedFontHrefs?.toSet())
    }

    /**
     * 이미지 href, 블록 스타일 폰트 href, span 폰트 href가 페이지 안에서 반복되는 블록은 각각 항목 하나로
     * 합쳐져야 한다 — 리소스 맵과 집합은 href를 키로 하므로, 중복 참조는 두 번째 항목을 만들지 않고
     * 최초 참조 순서도 흐트러뜨리지 않는다.
     */
    @Test
    fun pageUiDeduplicatesRepeatedHrefs() {
        val blocks = listOf(
            ReaderBlock(
                kind = ReaderBlockKind.IMAGE,
                range = TextRange(0, 1),
                imageHref = "img/a.png",
                style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
                spans = listOf(
                    ReaderSpan(range = TextRange(0, 1), styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf")),
                ),
            ),
            ReaderBlock(
                kind = ReaderBlockKind.IMAGE,
                range = TextRange(1, 2),
                imageHref = "img/a.png",
                style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
                spans = listOf(
                    ReaderSpan(range = TextRange(1, 2), styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf")),
                ),
            ),
        )
        val page = readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "ab", TextRange(0, 2), blocks = blocks),
                    ),
                ),
                embeddedImages = mapOf("img/a.png" to byteArrayOf(1)),
                embeddedFontFiles = mapOf("fonts/body.otf" to "/tmp/body.otf", "fonts/inline.otf" to "/tmp/inline.otf"),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = emptySet(),
            ),
        )

        assertEquals(listOf("img/a.png"), page?.embeddedImages?.keys?.toList())
        assertEquals(listOf("fonts/body.otf", "fonts/inline.otf"), page?.embeddedFontFiles?.keys?.toList())
    }

    /**
     * 로드된 폰트 맵과 실패한 폰트 집합은 서로 독립적인 입력에 대해 해석되므로, 로드된 파일도 있고
     * 실패로 표시도 된 href는 양쪽 모두에 나타나야 한다 — 어느 한쪽이 다른 쪽을 가려서는 안 된다.
     */
    @Test
    fun pageUiKeepsHrefInBothLoadedAndFailed() {
        val block = ReaderBlock(
            kind = ReaderBlockKind.IMAGE,
            range = TextRange(0, 1),
            imageHref = "img/a.png",
            style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
        )
        val page = readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "a", TextRange(0, 1), blocks = listOf(block)),
                    ),
                ),
                embeddedImages = mapOf("img/a.png" to byteArrayOf(1)),
                embeddedFontFiles = mapOf("fonts/body.otf" to "/tmp/body.otf"),
                failedEmbeddedImageHrefs = setOf("img/a.png"),
                failedEmbeddedFontHrefs = setOf("fonts/body.otf"),
            ),
        )

        assertEquals(listOf("img/a.png"), page?.embeddedImages?.keys?.toList())
        assertEquals(setOf("img/a.png"), page?.failedEmbeddedImageHrefs?.toSet())
        assertEquals(listOf("fonts/body.otf"), page?.embeddedFontFiles?.keys?.toList())
        assertEquals(setOf("fonts/body.otf"), page?.failedEmbeddedFontHrefs?.toSet())
    }

    /**
     * 블록 자체의 스타일 폰트는 그 블록의 span 폰트보다 먼저 제공되며, 블록들은 목록 순서를 유지하므로,
     * 해석된 폰트 맵은 블록마다 스타일 폰트 다음 span 폰트 순으로 순회한다.
     */
    @Test
    fun pageUiOrdersStyleFontBeforeSpanFont() {
        val block = ReaderBlock(
            kind = ReaderBlockKind.PARAGRAPH,
            range = TextRange(0, 4),
            style = ReaderBlockStyle(fontHref = "fonts/style.otf"),
            spans = listOf(
                ReaderSpan(range = TextRange(0, 2), styleDelta = ReaderSpanStyle(fontHref = "fonts/span-a.otf")),
                ReaderSpan(range = TextRange(2, 4), styleDelta = ReaderSpanStyle(fontHref = "fonts/span-b.otf")),
            ),
        )
        val page = readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "text", TextRange(0, 4), blocks = listOf(block)),
                    ),
                ),
                embeddedImages = emptyMap(),
                embeddedFontFiles = mapOf(
                    "fonts/style.otf" to "/tmp/style.otf",
                    "fonts/span-a.otf" to "/tmp/span-a.otf",
                    "fonts/span-b.otf" to "/tmp/span-b.otf",
                ),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = emptySet(),
            ),
        )

        assertEquals(
            listOf("fonts/style.otf", "fonts/span-a.otf", "fonts/span-b.otf"),
            page?.embeddedFontFiles?.keys?.toList(),
        )
    }

    /**
     * 블록이 없는 페이지는 빈 리소스 컬렉션으로 해석되며 context의 맵이나 집합 어느 것도 건드리지 않는다.
     */
    @Test
    fun pageUiWithNoBlocksHasEmptyResources() {
        val page = readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "text", TextRange(0, 4)),
                    ),
                ),
                embeddedImages = mapOf("img/a.png" to byteArrayOf(1)),
                embeddedFontFiles = mapOf("fonts/body.otf" to "/tmp/body.otf"),
                failedEmbeddedImageHrefs = setOf("img/a.png"),
                failedEmbeddedFontHrefs = setOf("fonts/body.otf"),
            ),
        )

        assertEquals(emptyMap(), page?.embeddedImages?.toMap())
        assertEquals(emptyMap(), page?.embeddedFontFiles?.toMap())
        assertEquals(emptySet(), page?.failedEmbeddedImageHrefs?.toSet())
        assertEquals(emptySet(), page?.failedEmbeddedFontHrefs?.toSet())
    }

    @Test
    fun pageUiCarriesItsChapterLocalPageIndex() {
        val pages = (0 until 5).map { page ->
            PageWindow(
                pageIndex = PageIndex(page, 5),
                location = ReaderLocation.TextOffset(page * 10L),
                text = "page $page",
                textRange = TextRange(page * 10L, (page + 1) * 10L),
            )
        }
        val page = readerPageUi(
            page = 3,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(3, 5),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = pages,
                    sections = listOf(
                        ReaderSection(0, "", TextRange(0, 20), "One"),
                        ReaderSection(1, "", TextRange(20, 50), "Two"),
                    ),
                ),
                embeddedImages = emptyMap(),
                embeddedFontFiles = emptyMap(),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = emptySet(),
            ),
        )

        assertEquals("Two", page?.chapterTitle)
        assertEquals(PageIndex(current = 1, total = 3), page?.chapterPageIndex)
    }

    /**
     * 모든 원소 읽기를 세는 목록이다 — 블록을 N번 순회하는 매퍼가 있다면 N번의 읽기로 그대로 잡힌다.
     * 단일 패스 매퍼는 블록이 몇 가지 리소스 종류를 제공하든 상관없이 각 블록 인덱스를 정확히 한 번씩만
     * 읽는다.
     *
     * @property delegate 모든 읽기가 위임되는 실제 블록 목록.
     * @property readCount [get] 호출 한 번당 하나씩, 지금까지 처리된 원소 읽기 횟수.
     */
    private class CountingBlockList(private val delegate: List<ReaderBlock>) : AbstractList<ReaderBlock>() {
        var readCount = 0
            private set

        override val size: Int get() = delegate.size

        override fun get(index: Int): ReaderBlock {
            readCount++
            return delegate[index]
        }
    }

    /**
     * 리소스 해석에 대한 거의-단일-패스 불변 조건을 지킨다. 페이지 하나를 매핑하면 설계상 블록 목록을
     * 네 번 읽는다: 불변 복사본, 챕터 제목·챕터 페이지 커버리지 스캔 두 번, 그리고 이미지·폰트·실패를
     * 하나로 접는 리소스 순회 한 번. 예전 매퍼의 네 번 분리된 리소스 순회는 두 챕터 조회까지 더하면
     * 여전히 일곱 번의 패스가 들었을 것이다.
     */
    @Test
    fun pageUiWalksBlocksInOneResourcePassBeyondTheCopy() {
        val blocks = CountingBlockList(
            listOf(
                ReaderBlock(
                    kind = ReaderBlockKind.IMAGE,
                    range = TextRange(0, 1),
                    imageHref = "img/a.png",
                    style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
                    spans = listOf(
                        ReaderSpan(range = TextRange(0, 1), styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf")),
                    ),
                ),
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(1, 4),
                    style = ReaderBlockStyle(fontHref = "fonts/head.otf"),
                ),
            ),
        )
        readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "abcd", TextRange(0, 4), blocks = blocks),
                    ),
                ),
                embeddedImages = mapOf("img/a.png" to byteArrayOf(1)),
                embeddedFontFiles = mapOf(
                    "fonts/body.otf" to "/tmp/body.otf",
                    "fonts/inline.otf" to "/tmp/inline.otf",
                    "fonts/head.otf" to "/tmp/head.otf",
                ),
                failedEmbeddedImageHrefs = setOf("img/a.png"),
                failedEmbeddedFontHrefs = setOf("fonts/inline.otf"),
            ),
        )

        val copyPass = blocks.size
        val chapterTitlePass = blocks.size
        val chapterPagePass = blocks.size
        val singleResourcePass = blocks.size
        assertEquals(
            copyPass + chapterTitlePass + chapterPagePass + singleResourcePass,
            blocks.readCount,
            "mapping a page must read its blocks exactly four times (copy, two chapter scans, one " +
                "resource pass), but it read ${blocks.readCount} for ${blocks.size} blocks",
        )
        val oldFourResourcePassReads = copyPass + chapterTitlePass + chapterPagePass + 4 * blocks.size
        assertTrue(
            blocks.readCount < oldFourResourcePassReads,
            "the old four-resource-pass mapper would read blocks $oldFourResourcePassReads times; " +
                "the current one must fold them into a single pass",
        )
    }
}
