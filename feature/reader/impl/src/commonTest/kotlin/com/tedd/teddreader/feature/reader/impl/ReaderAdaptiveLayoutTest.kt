package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.ui.system.DisplayFold
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderAdaptiveLayoutTest {
    @Test
    fun phoneLandscapeUsesOnePane() {
        assertEquals(1, readerPaneCount(widthDp = 840f, heightDp = 360f))
    }

    @Test
    fun tabletPortraitUsesTwoPanes() {
        assertEquals(2, readerPaneCount(widthDp = 600f, heightDp = 960f))
    }

    @Test
    fun tabletLandscapeUsesTwoPanes() {
        assertEquals(2, readerPaneCount(widthDp = 960f, heightDp = 600f))
    }

    @Test
    fun separatingVerticalFoldUsesTwoPanesBelowTabletClass() {
        assertEquals(
            2,
            readerPaneCount(
                widthDp = 520f,
                heightDp = 700f,
                fold = DisplayFold(
                    startDp = 258f,
                    endDp = 262f,
                    isVertical = true,
                    isSeparating = true,
                ),
            ),
        )
    }

    @Test
    fun pageSlotsArePreferredAndLegacySlotsRemainFallbacks() {
        val state = ReaderUiState(
            previousPage = ReaderPageUi(page = 1, text = "previous"),
            currentPage = ReaderPageUi(page = 2, text = "legacy"),
            pageSlots = persistentListOf(ReaderPageUi(page = 2, text = "window")),
        )

        assertEquals("window", state.pageSlot(2)?.text)
        assertEquals("previous", state.pageSlot(1)?.text)
    }

    @Test
    fun twoPaneNextDoesNotOverlapACompleteFinalSpread() {
        assertEquals(null, readerNextPage(currentPage = 2, totalPages = 4, paneCount = 2))
        assertEquals(4, readerNextPage(currentPage = 2, totalPages = 5, paneCount = 2))
    }

    @Test
    fun spreadPageIndexUsesLogicalSpreadForTwoPane() {
        assertEquals(
            PageIndex(current = 0, total = 2),
            readerSpreadPageIndex(currentPage = 0, totalPages = 4, paneCount = 2),
        )
        assertEquals(
            PageIndex(current = 0, total = 3),
            readerSpreadPageIndex(currentPage = 1, totalPages = 5, paneCount = 2),
        )
        assertEquals(PageIndex(current = 1, total = 2), readerSpreadPageIndex(currentPage = 2, totalPages = 4, paneCount = 2))
        assertEquals(PageIndex(current = 2, total = 3), readerSpreadPageIndex(currentPage = 4, totalPages = 5, paneCount = 2))
    }

    @Test
    fun spreadPageIndexKeepsSinglePaneAndClampsInvalidInput() {
        assertEquals(PageIndex(current = 2, total = 5), readerSpreadPageIndex(currentPage = 2, totalPages = 5, paneCount = 1))
        assertEquals(PageIndex(current = 0, total = 0), readerSpreadPageIndex(currentPage = 3, totalPages = 0, paneCount = 2))
        assertEquals(PageIndex(current = 1, total = 2), readerSpreadPageIndex(currentPage = 99, totalPages = 4, paneCount = 2))
    }

    @Test
    fun selectedSpreadUsesSpreadAnchorForGoToPage() {
        assertEquals(2, readerSpreadAnchorPage(selectedSpread = 1, totalPages = 4, paneCount = 2))
        assertEquals(2, readerSpreadAnchorPage(selectedSpread = 1, totalPages = 5, paneCount = 2))
        assertEquals(4, readerSpreadAnchorPage(selectedSpread = 2, totalPages = 5, paneCount = 2))
        assertEquals(4, readerSpreadAnchorPage(selectedSpread = 99, totalPages = 5, paneCount = 2))
        assertEquals(3, readerSpreadAnchorPage(selectedSpread = 3, totalPages = 5, paneCount = 1))
    }

    @Test
    fun textReadProgressUsesAbsoluteOffsetAndKeepsTheCurrentValueUntilCharacterCountIsKnown() {
        assertEquals(0, readerReadProgressPercent(ReaderLocation.TextOffset(12), characterCount = null))
        assertEquals(37, readerReadProgressPercent(ReaderLocation.TextOffset(12), characterCount = null, currentPercent = 37))
        assertEquals(0, readerReadProgressPercent(ReaderLocation.TextOffset(0), characterCount = 31))
        assertEquals(52, readerReadProgressPercent(ReaderLocation.TextOffset(16), characterCount = 31))
        assertEquals(100, readerReadProgressPercent(ReaderLocation.TextOffset(31), characterCount = 31))
    }

    @Test
    fun visualReadProgressStillUsesCurrentPageOverTotalPages() {
        assertEquals(0, readerVisualReadProgressPercent(PageIndex(current = 0, total = 0)))
        assertEquals(5, readerVisualReadProgressPercent(PageIndex(current = 0, total = 20)))
        assertEquals(50, readerVisualReadProgressPercent(PageIndex(current = 9, total = 20)))
        assertEquals(100, readerVisualReadProgressPercent(PageIndex(current = 19, total = 20)))
    }
}
