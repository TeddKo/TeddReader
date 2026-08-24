package com.tedd.teddreader.core.common.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins what is persisted, since every one of these types is written to storage and read back on a later
 * launch.
 *
 * A round trip failing here is an install that loses its settings or its reading positions on upgrade,
 * which is why the page-animation case checks that the pager presets are all still serializable names
 * rather than just that the enum compiles.
 */
class ReaderSerializationTest {
    // The default Json, exactly as production reads these back (DocumentRepositoryImpl and the settings
    // store both use `Json` with no leniency) — a laxer instance here would pass round trips production
    // rejects, hiding the silent per-section block loss an unknown key causes there.
    private val json = Json

    @Test
    fun readerStyleRoundTripsThroughJson() {
        val style = sepiaReaderStyle().copy(fontSizeSp = 22f, publisherFontKey = "epub-fonts")

        assertEquals(style.copy(publisherFontKey = null), json.decodeFromString(json.encodeToString(style)))
    }

    @Test
    fun publisherThemeModeRoundTripsThroughJson() {
        assertEquals(
            ReaderThemeMode.PUBLISHER,
            json.decodeFromString<ReaderThemeMode>(json.encodeToString(ReaderThemeMode.PUBLISHER)),
        )
    }

    @Test
    fun readerLocationRoundTripsThroughJson() {
        val location: ReaderLocation = ReaderLocation.EpubOffset(spineIndex = 2, offset = 32L)

        assertEquals(location, json.decodeFromString<ReaderLocation>(json.encodeToString(location)))
    }

    @Test
    fun readingHistoryRoundTripsThroughJson() {
        val entry = ReadingHistoryEntry(
            documentId = DocumentId("doc"),
            date = LocalDate(2026, 7, 6),
            activeMillis = 1_000L,
            wordsRead = 120L,
        )

        assertEquals(entry, json.decodeFromString(json.encodeToString(entry)))
    }

    @Test
    fun readerSpanWithInlineStyleDeltaRoundTripsThroughJson() {
        val span = ReaderSpan(
            range = TextRange(3, 7),
            style = null,
            styleDelta = ReaderSpanStyle(
                fontScale = 0.8f,
                italic = true,
                foregroundColor = ReaderColor(0xFF011689),
                fontFamilyName = "KoPub",
                fontHref = "OPS/fonts/KoPub.otf",
                underline = false,
            ),
        )

        assertEquals(span, json.decodeFromString(json.encodeToString(span)))
    }

    @Test
    fun readerBoxStyleRejectsBorderRadiusAbove100Percent() {
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            ReaderBoxStyle(borderRadiusPercent = 101f)
        }

        assertEquals("Border radius percent must be in 0..100.", error.message)
    }

    @Test
    fun readerSpanWithoutCssStyleStillDecodesFromOlderJson() {
        assertEquals(
            ReaderSpan(range = TextRange(3, 7), style = ReaderInlineStyle.BOLD),
            json.decodeFromString("""{"range":{"start":3,"end":7},"style":"BOLD"}"""),
        )
    }

    @Test
    fun readerBlockWithoutPageContainerStillDecodesFromOlderJson() {
        val block = json.decodeFromString<ReaderBlock>(
            """{"kind":"PARAGRAPH","range":{"start":0,"end":4},"level":0,"spans":[],"align":null,"imageHref":null,"label":null,"tableRow":null,"tableColumn":null,"imageAspectRatio":null,"imageNaturalWidthPx":null,"imageWidthPercent":null,"imageWidthEm":null,"float":null,"style":null}""",
        )

        assertFalse(block.isPageContainer)
    }

    @Test
    fun pageAnimationIncludesFoundationPagerPresets() {
        assertEquals(PageAnimation.FLUID_PAGER, json.decodeFromString<PageAnimation>("\"FLUID_PAGER\""))
        assertEquals(PageAnimation.CURL_PAGER, json.decodeFromString<PageAnimation>("\"CURL_PAGER\""))
        assertEquals(PageAnimation.CIRCLE_REVEAL, json.decodeFromString<PageAnimation>("\"CIRCLE_REVEAL\""))
        assertEquals(PageAnimation.MOVIE_CAROUSEL, json.decodeFromString<PageAnimation>("\"MOVIE_CAROUSEL\""))
        assertEquals(PageAnimation.PAGE_FLIP, json.decodeFromString<PageAnimation>("\"PAGE_FLIP\""))
    }

}
