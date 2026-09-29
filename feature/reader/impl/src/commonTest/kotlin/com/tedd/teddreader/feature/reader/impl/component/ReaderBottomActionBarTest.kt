package com.tedd.teddreader.feature.reader.impl.component

import com.tedd.teddreader.core.common.model.PageIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderBottomActionBarTest {
    @Test
    fun chapterPageLabelUsesTitleBulletAndOneBasedPageFraction() {
        assertEquals(
            "Chapter Two • 2/3",
            readerChapterPageLabel("Chapter Two", PageIndex(current = 1, total = 3)),
        )
    }

    @Test
    fun chapterPageLabelGroupsThousands() {
        assertEquals(
            "Chapter • 1,234/12,345",
            readerChapterPageLabel("Chapter", PageIndex(current = 1_233, total = 12_345)),
        )
    }

    @Test
    fun chapterPageLabelIsAbsentWithoutAUsableChapterPosition() {
        assertNull(readerChapterPageLabel(null, PageIndex(current = 0, total = 1)))
        assertNull(readerChapterPageLabel("Chapter", null))
        assertNull(readerChapterPageLabel("Chapter", PageIndex(current = 0, total = 0)))
    }

    @Test
    fun documentPageLabelGroupsThousandsAndMarksIncompletePagination() {
        assertEquals("1,234 / 12,345", readerDocumentPageLabel(1_233, 12_345, isPaginationComplete = true))
        assertEquals("1,234 / 12,345+", readerDocumentPageLabel(1_233, 12_345, isPaginationComplete = false))
    }
}
