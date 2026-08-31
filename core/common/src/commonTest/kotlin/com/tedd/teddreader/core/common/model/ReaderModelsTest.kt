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
            "loaded-fonts#layout8",
            ReaderStyle(publisherFontKey = "loaded-fonts").layoutKey().fontFamilyName,
        )
        assertEquals(
            "serif#layout8",
            ReaderStyle(fontFamilyName = "serif", publisherFontKey = "loaded-fonts").layoutKey().fontFamilyName,
        )
        assertEquals(
            "same-href=loaded#layout8",
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
        assertEquals("#layout8", ReaderStyle().layoutKey().fontFamilyName)
    }

    /**
     * A style following the system draws dark page colours on a dark device.
     *
     * The regression this covers shipped as a half-dark app: chrome resolved the system flag and went
     * dark while the page kept the light colours that were persisted when `SYSTEM` was chosen, so the
     * reader showed light paper inside a dark frame.
     */
    @Test
    fun systemThemeTakesDarkPageColoursOnADarkDevice() {
        val resolved = ReaderStyle().withThemeMode(ReaderThemeMode.SYSTEM).resolveSystemTheme(true)

        assertEquals(ReaderColor(ReaderDarkBackgroundArgb), resolved.backgroundColor)
        assertEquals(ReaderColor(ReaderDarkTextArgb), resolved.textColor)
    }

    /**
     * Publisher/document style follows the same live system fallback as app chrome when the book
     * does not provide a color of its own. Explicit EPUB colors remain gated by the PUBLISHER mode.
     */
    @Test
    fun publisherThemeTakesDarkFallbackColoursOnADarkDevice() {
        val resolved = ReaderStyle().withThemeMode(ReaderThemeMode.PUBLISHER).resolveSystemTheme(true)

        assertEquals(ReaderColor(ReaderDarkBackgroundArgb), resolved.backgroundColor)
        assertEquals(ReaderColor(ReaderDarkTextArgb), resolved.textColor)
        assertEquals(ReaderThemeMode.PUBLISHER, resolved.themeMode)
    }

    /**
     * Resolving for the system keeps the mode as `SYSTEM`, so the setting still reads back as
     * "follow system" rather than appearing to have rewritten itself to an explicit dark choice.
     */
    @Test
    fun resolvingForTheSystemDoesNotRewriteTheChosenMode() {
        val resolved = ReaderStyle().withThemeMode(ReaderThemeMode.SYSTEM).resolveSystemTheme(true)

        assertEquals(ReaderThemeMode.SYSTEM, resolved.themeMode)
    }

    /**
     * On a light device the same style stays light, which is also what the persisted value already held.
     */
    @Test
    fun systemThemeStaysLightOnALightDevice() {
        val resolved = ReaderStyle().withThemeMode(ReaderThemeMode.SYSTEM).resolveSystemTheme(false)

        assertEquals(ReaderColor(ReaderLightBackgroundArgb), resolved.backgroundColor)
        assertEquals(ReaderColor(ReaderLightTextArgb), resolved.textColor)
    }

    /**
     * An explicit theme ignores the system flag — choosing light *is* the decision not to follow it.
     *
     * Checked on a dark device specifically, because that is the direction where a stray resolution
     * would show up as the user's explicit choice being overridden.
     */
    @Test
    fun anExplicitThemeIgnoresTheSystemSetting() {
        val light = ReaderStyle().withThemeMode(ReaderThemeMode.LIGHT).resolveSystemTheme(true)
        val sepia = ReaderStyle().withThemeMode(ReaderThemeMode.SEPIA).resolveSystemTheme(true)

        assertEquals(ReaderColor(ReaderLightBackgroundArgb), light.backgroundColor)
        assertEquals(ReaderColor(ReaderSepiaBackgroundArgb), sepia.backgroundColor)
    }

    /**
     * Resolving colours never invalidates a stored pagination.
     *
     * Page breaks are keyed on type size, line height, family, and weight; if colour ever leaked into
     * that key, every system theme flip would silently repaginate the open document.
     */
    @Test
    fun resolvingForTheSystemLeavesThePaginationKeyAlone() {
        val stored = ReaderStyle().withThemeMode(ReaderThemeMode.SYSTEM)

        assertEquals(stored.layoutKey(), stored.resolveSystemTheme(true).layoutKey())
    }

    /**
     * [ReaderStyle.withLayoutFieldsOf] must copy exactly the fields [layoutKey] reduces a style to,
     * and nothing else — this is the "type frozen, colour instant" contract the whole stale-page-slice
     * fix rests on, pinned in one place. [live] and [measured] are built to differ in *every* field,
     * both layout and non-layout, so this test fails the day someone adds a fifth field to [layoutKey]
     * without adding it to [ReaderStyle.withLayoutFieldsOf]'s copier, exactly as it would fail today if
     * `fontFamilyName` were dropped from that copier.
     */
    @Test
    fun withLayoutFieldsOfCopiesOnlyTheLayoutKeyFieldsAndKeepsEveryColourFieldLive() {
        val live = ReaderStyle(
            fontSizeSp = 18f,
            fontFamilyName = "serif",
            publisherFontKey = "live-publisher-key",
            lineHeightMultiplier = 1.4f,
            fontWeight = 300,
            textColor = ReaderColor(0xFF010203),
            backgroundColor = ReaderColor(0xFF040506),
            backgroundImage = BackgroundImage(uri = "file:///live.png", opacity = 0.3f),
            themeMode = ReaderThemeMode.CUSTOM,
        )
        val measured = ReaderStyle(
            fontSizeSp = 24f,
            fontFamilyName = "sans",
            publisherFontKey = "measured-publisher-key",
            lineHeightMultiplier = 1.8f,
            fontWeight = 600,
            textColor = ReaderColor(0xFF0A0B0C),
            backgroundColor = ReaderColor(0xFF0D0E0F),
            backgroundImage = BackgroundImage(uri = "file:///measured.png", opacity = 0.6f),
            themeMode = ReaderThemeMode.DARK,
        )
        val drawn = live.withLayoutFieldsOf(measured)

        assertEquals(measured.layoutKey(), drawn.layoutKey())
        assertEquals(measured.fontWeight, drawn.fontWeight)
        assertEquals(live.textColor, drawn.textColor)
        assertEquals(live.backgroundColor, drawn.backgroundColor)
        assertEquals(live.themeMode, drawn.themeMode)
        assertEquals(live.backgroundImage, drawn.backgroundImage)
    }

    /**
     * [ReaderStyle.fontWeight] must move [layoutKey] when it differs from [ReaderDefaultFontWeight] — a
     * heavier or lighter weight changes glyph advances and so moves where lines break — but must leave
     * the key exactly as it already reads today for anyone still on the default weight, which is what
     * lets an existing reader's stored page layouts keep serving without a forced re-measurement the day
     * this setting ships.
     *
     * Falsification (the AGENTS.md drill): fold `fontWeight` back out of [layoutKey]'s computed family
     * string, e.g. by changing `fontWeightToken()`'s body to always return `""`, and re-run this suite.
     * Actual result: this test's second assertion fails —
     * `expected:<|w600#layout8> but was:<#layout8>` — because a 600-weight style's key then equals the
     * default-weight style's key instead of differing from it; the first assertion still passes, since it
     * only exercises the default weight. Restoring the token makes both assertions pass again.
     */
    @Test
    fun nonDefaultFontWeightChangesLayoutKeyButDefaultWeightDoesNot() {
        assertEquals("#layout8", ReaderStyle(fontWeight = ReaderDefaultFontWeight).layoutKey().fontFamilyName)
        assertEquals("|w600#layout8", ReaderStyle(fontWeight = 600).layoutKey().fontFamilyName)
    }

    /**
     * [ReaderStyle]'s `init` rejects a font weight outside the 300..600 range the four-option typography
     * setting offers, the same defensive bound [ReaderStyle.fontSizeSp] and
     * [ReaderStyle.lineHeightMultiplier] already enforce for their own ranges.
     */
    @Test
    fun readerStyleRejectsFontWeightOutsideSupportedRange() {
        assertFailsWith<IllegalArgumentException> {
            ReaderStyle(fontWeight = 299)
        }
        assertFailsWith<IllegalArgumentException> {
            ReaderStyle(fontWeight = 601)
        }
    }
}
