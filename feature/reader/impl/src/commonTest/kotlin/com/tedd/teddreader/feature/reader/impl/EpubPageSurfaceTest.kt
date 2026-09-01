package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderDarkTextArgb
import com.tedd.teddreader.core.common.model.ReaderLightTextArgb
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.designsystem.toColor
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EpubPageSurfaceTest {
    @Test
    fun deepestPublisherPageContainerBackgroundWinsForPaneBackground() {
        val page = ReaderPageUi(
            text = "body",
            blocks = persistentListOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, 4),
                    level = 1,
                    style = ReaderBlockStyle(boxStyle = ReaderBoxStyle(backgroundColor = ReaderColor(0xFF111111))),
                    isPageContainer = true,
                ),
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, 4),
                    level = 2,
                    style = ReaderBlockStyle(boxStyle = ReaderBoxStyle(backgroundColor = ReaderColor(0xFF222222))),
                    isPageContainer = true,
                ),
            ),
        )

        assertTrue(epubPageContainerBackgroundColor(page, ReaderStyle(themeMode = ReaderThemeMode.PUBLISHER))?.argb == 0xFF222222)
    }

    @Test
    fun transparentDeepestPageContainerFallsBackToOuterPaintedBackground() {
        val page = ReaderPageUi(
            text = "body",
            blocks = persistentListOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, 4),
                    level = 1,
                    style = ReaderBlockStyle(boxStyle = ReaderBoxStyle(backgroundColor = ReaderColor(0xFF111111))),
                    isPageContainer = true,
                ),
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, 4),
                    level = 2,
                    style = ReaderBlockStyle(boxStyle = ReaderBoxStyle(backgroundColor = ReaderColor(0x00123456))),
                    isPageContainer = true,
                ),
            ),
        )

        assertEquals(0xFF111111, epubPageContainerBackgroundColor(page, ReaderStyle(themeMode = ReaderThemeMode.PUBLISHER))?.argb)
    }

    @Test
    fun darkPublisherPageBackgroundUsesLightFallbackText() {
        val page = pageWithPublisherBackground(0xFF111111)
        val style = ReaderStyle(
            textColor = ReaderColor(ReaderLightTextArgb),
            themeMode = ReaderThemeMode.PUBLISHER,
        )

        assertEquals(ReaderColor(ReaderDarkTextArgb).toColor(), epubPageTextStyle(page, style).color)
    }

    @Test
    fun lightPublisherPageBackgroundUsesDarkFallbackText() {
        val page = pageWithPublisherBackground(0xFFF8F8F8)
        val style = ReaderStyle(
            textColor = ReaderColor(ReaderDarkTextArgb),
            themeMode = ReaderThemeMode.PUBLISHER,
        )

        assertEquals(ReaderColor(ReaderLightTextArgb).toColor(), epubPageTextStyle(page, style).color)
    }

    private fun pageWithPublisherBackground(argb: Long) = ReaderPageUi(
        text = "body",
        blocks = persistentListOf(
            ReaderBlock(
                ReaderBlockKind.CONTAINER,
                TextRange(0, 4),
                level = 1,
                style = ReaderBlockStyle(boxStyle = ReaderBoxStyle(backgroundColor = ReaderColor(argb))),
                isPageContainer = true,
            ),
        ),
    )

    @Test
    fun borderInsetUsesDensityScaledStrokeWidth() {
        val boxStyle = ReaderBoxStyle(
            borderLeft = com.tedd.teddreader.core.common.model.ReaderBorder(widthPx = 3f),
            borderRight = com.tedd.teddreader.core.common.model.ReaderBorder(widthPx = 1f),
        )

        assertEquals(3f, maxBorderHalfStrokePx(boxStyle, density = 2f))
    }

    @Test
    fun nonPublisherThemeIgnoresPageContainerBackground() {
        val page = ReaderPageUi(
            text = "body",
            blocks = persistentListOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, 4),
                    level = 2,
                    style = ReaderBlockStyle(boxStyle = ReaderBoxStyle(backgroundColor = ReaderColor(0xFF222222))),
                    isPageContainer = true,
                ),
            ),
        )

        assertTrue(epubPageContainerBackgroundColor(page, ReaderStyle(themeMode = ReaderThemeMode.LIGHT)) == null)
    }

    private fun page(
        fontFiles: Map<String, String> = emptyMap(),
        failedFonts: Set<String> = emptySet(),
    ) = ReaderPageUi(
        text = "body",
        blocks = persistentListOf(
            ReaderBlock(
                ReaderBlockKind.PARAGRAPH,
                TextRange(0, 4),
                style = ReaderBlockStyle(fontHref = "OPS/fonts/book.otf"),
            ),
        ),
        embeddedFontFiles = fontFiles.toPersistentMap(),
        failedEmbeddedFontHrefs = failedFonts.toPersistentSet(),
    )

    @Test
    fun pendingPublisherFontsBlockMeasuredPagination() {
        assertFalse(canMeasureEpubPage(page(), ReaderStyle(fontFamilyName = null)))
    }

    @Test
    fun failedPublisherFontsStillAllowMeasuredPagination() {
        assertTrue(canMeasureEpubPage(page(failedFonts = setOf("OPS/fonts/book.otf")), ReaderStyle(fontFamilyName = null)))
    }

    @Test
    fun resolvedMissingPublisherFontsStillAllowMeasuredPagination() {
        assertTrue(
            canMeasureEpubPage(
                page(),
                ReaderStyle(fontFamilyName = null),
                failedResolvedFontHrefs = setOf("OPS/fonts/book.otf"),
            ),
        )
    }

    @Test
    fun userFontOverrideBypassesPublisherFontWait() {
        assertTrue(canMeasureEpubPage(page(), ReaderStyle(fontFamilyName = "serif")))
    }

    /** Verifies recomposition can address one embedded image with the same memory-cache entry. */
    @Test
    fun imageMemoryCacheKeyIsStableWithinDocumentAndHref() {
        val documentUri = "file:///library/book.epub"
        val imageHref = "OPS/images/plate.jpg"

        assertEquals(
            epubImageMemoryCacheKey(documentUri, imageHref),
            epubImageMemoryCacheKey(documentUri, imageHref),
        )
    }

    /** Verifies common EPUB hrefs cannot return a decoded bitmap from a different document. */
    @Test
    fun imageMemoryCacheKeySeparatesDocumentsSharingHref() {
        val imageHref = "OPS/images/cover.jpg"

        assertNotEquals(
            epubImageMemoryCacheKey("file:///library/first.epub", imageHref),
            epubImageMemoryCacheKey("file:///library/second.epub", imageHref),
        )
    }

    /** Verifies requests without both stable identities stay outside Coil's memory cache. */
    @Test
    fun imageMemoryCacheKeyRequiresDocumentAndHref() {
        assertEquals(null, epubImageMemoryCacheKey(null, "OPS/images/cover.jpg"))
        assertEquals(null, epubImageMemoryCacheKey("file:///library/book.epub", null))
    }
}
