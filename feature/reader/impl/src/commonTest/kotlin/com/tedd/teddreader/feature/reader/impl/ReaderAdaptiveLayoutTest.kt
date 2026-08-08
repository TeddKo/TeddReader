package com.tedd.teddreader.feature.reader.impl

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
}
