package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

        val dark = style.withThemeMode(ReaderThemeMode.DARK)
        val system = style.withThemeMode(ReaderThemeMode.SYSTEM)
        val custom = style.withThemeMode(ReaderThemeMode.CUSTOM)

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
}
