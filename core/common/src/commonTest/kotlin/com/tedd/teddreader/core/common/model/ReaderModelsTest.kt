package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the arithmetic and the theme rules the reader's own state is built on: how progress is derived from
 * a page index, that a text range cannot be inverted, that a document's counts come from its sections, and
 * that applying a built-in theme replaces colours while leaving the reader's chosen type alone.
 *
 * The auto-scroll case guards the enum's order rather than its values: `LINE` sits between `PIXEL` and
 * `PAGE` because the settings screen shows the modes in that order, from finest to coarsest.
 */
class ReaderModelsTest {
    @Test
    fun pageProgressUsesCanonicalPageIndex() {
        assertEquals(0.5f, PageIndex(current = 5, total = 10).progress)
    }

    @Test
    fun textRangeRejectsInvalidOrder() {
        assertFailsWith<IllegalArgumentException> {
            TextRange(start = 10, end = 1)
        }
    }

    @Test
    fun readerDocumentCalculatesCharacterAndWordCount() {
        val document = ReaderDocument(
            id = DocumentId("doc-1"),
            format = DocumentFormat.TXT,
            title = "Sample",
            sections = listOf(
                ReaderSection(
                    index = 0,
                    text = "hello reader",
                    range = TextRange(0L, 12L),
                ),
            ),
        )

        assertEquals(12L, document.characterCount)
        assertEquals(2L, document.wordCount)
    }

    @Test
    fun autoScrollModeEntriesIncludeLineBetweenPixelAndPage() {
        assertContentEquals(
            listOf(AutoScrollMode.PIXEL, AutoScrollMode.LINE, AutoScrollMode.PAGE),
            AutoScrollMode.entries,
        )
    }

    @Test
    fun withThemeModePreservesTypographyAndUpdatesBuiltInThemeColors() {
        val style = ReaderStyle(
            fontSizeSp = 24f,
            fontFamilyName = "serif",
            lineHeightMultiplier = 1.8f,
            textColor = ReaderColor(0xFF010203),
            backgroundColor = ReaderColor(0xFF040506),
            backgroundImage = BackgroundImage(uri = "file:///bg.png", opacity = 0.5f),
            themeMode = ReaderThemeMode.CUSTOM,
        )

        val publisher = style.withThemeMode(ReaderThemeMode.PUBLISHER)
        val dark = style.withThemeMode(ReaderThemeMode.DARK)
        val system = style.withThemeMode(ReaderThemeMode.SYSTEM)
        val custom = style.withThemeMode(ReaderThemeMode.CUSTOM)

        assertEquals(24f, publisher.fontSizeSp)
        assertEquals("serif", publisher.fontFamilyName)
        assertEquals(1.8f, publisher.lineHeightMultiplier)
        assertEquals(ReaderColor(ReaderLightTextArgb), publisher.textColor)
        assertEquals(ReaderColor(ReaderLightBackgroundArgb), publisher.backgroundColor)
        assertEquals(null, publisher.backgroundImage)
        assertEquals(ReaderThemeMode.PUBLISHER, publisher.themeMode)

        assertEquals(24f, dark.fontSizeSp)
        assertEquals("serif", dark.fontFamilyName)
        assertEquals(1.8f, dark.lineHeightMultiplier)
        assertEquals(ReaderColor(ReaderDarkTextArgb), dark.textColor)
        assertEquals(ReaderColor(ReaderDarkBackgroundArgb), dark.backgroundColor)
        assertEquals(null, dark.backgroundImage)
        assertEquals(ReaderThemeMode.DARK, dark.themeMode)

        assertEquals(ReaderColor(ReaderLightTextArgb), system.textColor)
        assertEquals(ReaderColor(ReaderLightBackgroundArgb), system.backgroundColor)
        assertEquals(ReaderThemeMode.SYSTEM, system.themeMode)
        assertEquals(null, system.backgroundImage)

        assertEquals(ReaderColor(0xFF010203), custom.textColor)
        assertEquals(ReaderColor(0xFF040506), custom.backgroundColor)
        assertEquals(ReaderThemeMode.CUSTOM, custom.themeMode)
        assertEquals(BackgroundImage(uri = "file:///bg.png", opacity = 0.5f), custom.backgroundImage)
    }

    @Test
    fun layoutKeyFallsBackToPublisherFontKeyWhenNoUserFontIsChosen() {
        assertEquals(
            "loaded-fonts#layout7",
            ReaderStyle(publisherFontKey = "loaded-fonts").layoutKey().fontFamilyName,
        )
        assertEquals(
            "serif#layout7",
            ReaderStyle(fontFamilyName = "serif", publisherFontKey = "loaded-fonts").layoutKey().fontFamilyName,
        )
        assertEquals(
            "same-href=loaded#layout7",
            ReaderStyle(publisherFontKey = "same-href=loaded").layoutKey().fontFamilyName,
        )
    }

    /**
     * The layout-algorithm marker is what turns every page layout stored by an older algorithm into a
     * clean cache miss: the stored key was written without it (or with an older one), so a lookup after
     * the algorithm changed can never serve stale page breaks for unchanged text.
     */
    @Test
    fun layoutKeyCarriesTheLayoutAlgorithmVersion() {
        assertEquals("#layout7", ReaderStyle().layoutKey().fontFamilyName)
    }
}
