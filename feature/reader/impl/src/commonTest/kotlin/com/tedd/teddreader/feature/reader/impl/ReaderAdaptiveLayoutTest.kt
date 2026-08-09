package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageIndex
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderAdaptiveLayoutTest {
    @Test
    fun compactWidthUsesOnePane() {
        assertEquals(1, readerPaneCount(widthDp = 575f))
    }

    @Test
    fun exactTwoPaneBoundaryUsesTwoPanes() {
        assertEquals(2, readerPaneCount(widthDp = 576f))
    }

    @Test
    fun landscapeBelowBoundaryStillUsesOnePane() {
        assertEquals(1, readerPaneCount(widthDp = 560f))
    }

    @Test
    fun tabletWidthKeepsTwoPanes() {
        assertEquals(2, readerPaneCount(widthDp = 840f))
    }

    @Test
    fun pageSlotsArePreferredAndLegacySlotsRemainFallbacks() {
        val state = ReaderUiState(
            previousPage = ReaderPageUi(page = 1, text = "previous"),
            currentPage = ReaderPageUi(page = 2, text = "legacy"),
            pageSlots = listOf(ReaderPageUi(page = 2, text = "window")),
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
}
