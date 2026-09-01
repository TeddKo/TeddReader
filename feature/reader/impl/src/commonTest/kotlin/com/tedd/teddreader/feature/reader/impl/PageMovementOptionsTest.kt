package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PageMovementOptionsTest {
    @Test
    fun pageMovementOptionsExcludeLegacyEntries() {
        assertEquals(
            listOf(PageTurnMode.HORIZONTAL, PageTurnMode.VERTICAL),
            readerPageTurnModeOptions,
        )
        assertEquals(
            listOf(
                PageAnimation.NONE,
                PageAnimation.SLIDE,
                PageAnimation.FADE,
                PageAnimation.SCROLL,
                PageAnimation.FLUID_PAGER,
                PageAnimation.CURL_PAGER,
                PageAnimation.THREE_D_CURL,
                PageAnimation.CIRCLE_REVEAL,
                PageAnimation.MOVIE_CAROUSEL,
                PageAnimation.PAGE_FLIP,
            ),
            readerPageAnimationOptions,
        )
    }
}
