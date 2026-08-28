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
    fun chapterPageLabelIsAbsentWithoutAUsableChapterPosition() {
        assertNull(readerChapterPageLabel(null, PageIndex(current = 0, total = 1)))
        assertNull(readerChapterPageLabel("Chapter", null))
        assertNull(readerChapterPageLabel("Chapter", PageIndex(current = 0, total = 0)))
    }
}
