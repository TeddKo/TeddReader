package com.tedd.teddreader.core.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.jvm.JvmInline

/** Storage tag for [ReaderLocation.TextOffset], the prefix [parseReaderLocation] matches to rebuild it. */
private const val TEXT_LOCATION_PREFIX = "txt"

/** Storage tag for [ReaderLocation.EpubOffset], the prefix [parseReaderLocation] matches to rebuild it. */
private const val EPUB_LOCATION_PREFIX = "epub"

/** Storage tag for [ReaderLocation.PdfPage], the prefix [parseReaderLocation] matches to rebuild it. */
private const val PDF_LOCATION_PREFIX = "pdf"

/**
 * Milliseconds in a minute, the divisor [ReadingStats.wordsPerMinute] uses to turn a millisecond
 * duration into a per-minute rate.
 */
private const val MILLIS_PER_MINUTE = 60_000f

/**
 * The reader's built-in page colours, as ARGB, shared by every theme that is not the reader's own
 * custom one.
 *
 * They live here rather than in the design system because a stored [ReaderStyle] holds concrete
 * colours: a theme is applied by copying these into the style (see [withThemeMode]), so the same
 * constants have to be reachable from the model layer that persists them.
 */
const val ReaderLightTextArgb: Long = 0xFF1F1F1FL

/** The light theme's page background colour, paired with [ReaderLightTextArgb]. */
const val ReaderLightBackgroundArgb: Long = 0xFFFFFBF2L

/** The dark theme's ink colour, paired with [ReaderDarkBackgroundArgb]. */
const val ReaderDarkTextArgb: Long = 0xFFECE6D6L

/** The dark theme's page background colour, paired with [ReaderDarkTextArgb]. */
const val ReaderDarkBackgroundArgb: Long = 0xFF12100DL

/** The sepia theme's ink colour, paired with [ReaderSepiaBackgroundArgb]. */
const val ReaderSepiaTextArgb: Long = 0xFF3B2F24L

/** The sepia theme's page background colour, paired with [ReaderSepiaTextArgb]. */
const val ReaderSepiaBackgroundArgb: Long = 0xFFF4ECD8L

/**
 * Where a reader is in a document, in terms the document's own format can answer for.
 *
 * A page number cannot be stored — it only means something for one type size on one screen — so every
 * position is expressed as something intrinsic to the book: a character offset for reflowable text, a
 * spine item plus an offset for EPUB, a page number for PDF, where the page *is* the document's own
 * unit. Reading positions and saved places are both kept this way, which is what lets them survive a
 * font-size change, a re-import, and a different device.
 *
 * Being sealed is the point: adding a format means adding a case here, and every `when` that resolves a
 * position stops compiling until it has an answer for it.
 *
 * [asStorageString] is the on-disk form, deliberately a short prefixed string rather than the
 * serializer's JSON, so a stored position is greppable in the database and cheap to compare — and
 * [parseReaderLocation] is its exact inverse.
 */
@Serializable
sealed interface ReaderLocation {
    /** The compact `prefix:…` form written to storage, read back by [parseReaderLocation]. */
    fun asStorageString(): String

    /** A character offset into the whole joined text — how a plain text document, and reflowable text
     *  in general, names a place. *
 * @property offset the character position in the whole joined text.
 * @throws IllegalArgumentException if [offset] is negative, which marks a corrupt stored row.
 */
    @Serializable
    @SerialName(TEXT_LOCATION_PREFIX)
    data class TextOffset(val offset: Long) : ReaderLocation {
        init {
            require(offset >= 0L) { "Text offset must be positive." }
        }

        override fun asStorageString(): String = "$TEXT_LOCATION_PREFIX:$offset"
    }

    /**
     * A spine item plus a character offset inside it. Carrying the spine index as well as the offset is
     * what keeps an EPUB position meaningful while the book is still being imported: the offsets of
     * later chapters are not known yet, but the chapter the reader is in already is.
     *
 * @property spineIndex the spine item, known from the moment the book's manifest is read.
 * @property offset the character position inside the document as a whole.
 * @throws IllegalArgumentException if either value is negative.
 */
    @Serializable
    @SerialName(EPUB_LOCATION_PREFIX)
    data class EpubOffset(
        val spineIndex: Int,
        val offset: Long,
    ) : ReaderLocation {
        init {
            require(spineIndex >= 0) { "EPUB spine index must be positive." }
            require(offset >= 0L) { "EPUB offset must be positive." }
        }

        override fun asStorageString(): String = "$EPUB_LOCATION_PREFIX:$spineIndex:$offset"
    }

    /** A page number, for a format whose pages are fixed by the file itself and never re-flow. *
 * @property pageIndex the zero-based page of the file itself.
 * @throws IllegalArgumentException if [pageIndex] is negative.
 */
    @Serializable
    @SerialName(PDF_LOCATION_PREFIX)
    data class PdfPage(val pageIndex: Int) : ReaderLocation {
        init {
            require(pageIndex >= 0) { "PDF page index must be positive." }
        }

        override fun asStorageString(): String = "$PDF_LOCATION_PREFIX:$pageIndex"
    }
}

/**
 * Reads back a position written by [ReaderLocation.asStorageString].
 *
 * A malformed value throws rather than resolving to the first page: a stored position that cannot be
 * parsed means the row was written by something this build does not understand, and silently sending
 * the reader to the beginning of the book would hide that while losing their place.
 *
 * @param value a string produced by [ReaderLocation.asStorageString].
 * @return the position it names.
 * @throws IllegalStateException if the prefix is unknown or its numbers are missing or unparseable —
 * which means the row was written by something this build does not understand.
 */
fun parseReaderLocation(value: String): ReaderLocation {
    val parts = value.split(":")
    return when (parts.firstOrNull()) {
        TEXT_LOCATION_PREFIX -> ReaderLocation.TextOffset(parts.requireLong(1, value))
        EPUB_LOCATION_PREFIX -> ReaderLocation.EpubOffset(
            spineIndex = parts.requireInt(1, value),
            offset = parts.requireLong(2, value),
        )
        PDF_LOCATION_PREFIX -> ReaderLocation.PdfPage(parts.requireInt(1, value))
        else -> error("Unsupported ReaderLocation: $value")
    }
}

/**
 * The [index]th colon-separated field of a stored [ReaderLocation], read as an [Int].
 *
 * @receiver the fields of a stored value, already split on `:` by [parseReaderLocation].
 * @param index which field to read.
 * @param source the original stored string, echoed into the error so a corrupt row is traceable.
 * @return the field parsed as an [Int].
 * @throws IllegalStateException if the field is missing or is not a valid integer.
 */
private fun List<String>.requireInt(index: Int, source: String): Int =
    getOrNull(index)?.toIntOrNull() ?: error("Invalid ReaderLocation: $source")

/**
 * The [index]th colon-separated field of a stored [ReaderLocation], read as a [Long].
 *
 * @receiver the fields of a stored value, already split on `:` by [parseReaderLocation].
 * @param index which field to read.
 * @param source the original stored string, echoed into the error so a corrupt row is traceable.
 * @return the field parsed as a [Long].
 * @throws IllegalStateException if the field is missing or is not a valid integer.
 */
private fun List<String>.requireLong(index: Int, source: String): Long =
    getOrNull(index)?.toLongOrNull() ?: error("Invalid ReaderLocation: $source")

/**
 * The page the reader is on out of the pages currently known, as the page counter and progress bar
 * show it.
 *
 * [total] is "known so far", not "in the book": while an import or a measurement is still running it
 * grows, and the reader is told the truth at each step rather than shown a guess that later corrects
 * itself. [progress] is computed here so every screen derives the same fraction from the same pair, and
 * a total of zero yields zero instead of dividing by it.
 *
 * @property current the page being shown, zero-based.
 * @property total pages known so far, which grows while an import or a measurement is still running.
 * @throws IllegalArgumentException if either value is negative, or if [current] exceeds a non-zero
 * [total].
 */
@Serializable
data class PageIndex(
    val current: Int,
    val total: Int,
) {
    init {
        require(current >= 0) { "current page must be positive." }
        require(total >= 0) { "total page count must be positive." }
        require(current <= total || total == 0) { "current page must less than total." }
    }

    /** [current] as a fraction of [total], computed here so every screen agrees; 0f when [total] is zero. */
    val progress: Float = if (total == 0) 0f else current.toFloat() / total.toFloat()
}

/**
 * An ARGB colour a reader page is drawn with, validated on construction so a stored style can never
 * carry a value that is not a colour.
 *
 * Inline over the `Long` so the model layer stays free of any UI colour type — the design system
 * converts at the edge — while costing nothing at runtime.
 *
 * @property argb the colour as `0xAARRGGBB`.
 * @throws IllegalArgumentException if [argb] does not fit that range.
 */
@Serializable
@JvmInline
value class ReaderColor(val argb: Long) {
    init {
        require(argb in MIN_ARGB..MAX_ARGB) { "ARGB color must fit 0xAARRGGBB." }
    }
}

/** Lower bound of a valid [ReaderColor], every channel at zero. */
private const val MIN_ARGB = 0x00000000L

/** Upper bound of a valid [ReaderColor], every channel at its maximum (`0xFFFFFFFF`). */
private const val MAX_ARGB = 0xFFFFFFFFL

/**
 * The language the app's own interface is shown in — independent of the language a book happens to be
 * written in.
 *
 * [SYSTEM] follows the platform's own locale; choosing [ENGLISH] or [KOREAN] pins the interface to that
 * language even if the device's locale later changes.
 */
@Serializable
enum class AppLanguage {
    SYSTEM,
    ENGLISH,
    KOREAN,
}

/**
 * Which set of page colours a reader is using, remembered alongside the colours themselves.
 *
 * The mode is stored as well as the colours because the colours alone cannot say *why* they are what
 * they are: [CUSTOM] means the reader chose them and nothing may overwrite them, while [SYSTEM] means
 * they are still following the platform and may be replaced when it changes.
 */
@Serializable
enum class ReaderThemeMode {
    PUBLISHER,
    SYSTEM,
    LIGHT,
    DARK,
    SEPIA,
    CUSTOM,
}

/**
 * A picture drawn behind the reader's text, for a [ReaderStyle] that wants more than one of the built-in
 * page colours.
 *
 * Switching to a built-in theme drops this (see [withThemeMode]), because a picture chosen to sit under
 * one set of page colours can leave text illegible under another.
 *
 * @property uri where the picture is.
 * @property opacity how strongly it shows through, 0..1, so text stays legible over it.
 * @throws IllegalArgumentException if [uri] is blank or [opacity] falls outside 0..1.
 */
@Serializable
data class BackgroundImage(
    val uri: String,
    val opacity: Float = 1f,
) {
    init {
        require(uri.isNotBlank()) { "Background image uri must not be blank." }
        require(opacity in 0f..1f) { "Background image opacity must be 0..1." }
    }
}

/**
 * Everything about how a page looks, and the value the reader both stores and draws from.
 *
 * The type fields and the colour fields sit together for storage, but they are not equal: only type
 * moves where pages break, which is why [layoutKey] exists and why measurement keys on it instead of on
 * this whole object.
 *
 * The bounds in `init` are what a stored style is trusted to hold — 8..80sp, 1..3× line height — so
 * every screen can render a persisted style without re-validating it.
 *
 * @property fontSizeSp type size in sp; changing it re-measures the book.
 * @property fontFamilyName the family the reader chose, or null to honor publisher fonts before falling
 * back to the system default; also re-measures.
 * @property publisherFontKey a non-persisted cache-buster for embedded publisher fonts: null until the
 * reader knows which embedded fonts loaded or failed for this document, then a stable summary string that
 * forces a fresh measurement for that resolved set without polluting stored settings.
 * @property lineHeightMultiplier line height as a multiple of the font size; also re-measures.
 * @property textColor the ink colour, which cannot move a line break.
 * @property backgroundColor the page colour, which cannot move a line break either.
 * @property backgroundImage a picture behind the text, or null for a plain page.
 * @property themeMode which theme these colours came from, so [withThemeMode] knows what may be replaced.
 * @throws IllegalArgumentException if [fontSizeSp] is outside 8..80 or [lineHeightMultiplier] outside
 * 1..3.
 */
@Serializable
data class ReaderStyle(
    val fontSizeSp: Float = 18f,
    val fontFamilyName: String? = null,
    @Transient val publisherFontKey: String? = null,
    val lineHeightMultiplier: Float = ReaderDefaultLineHeightMultiplier,
    val textColor: ReaderColor = ReaderColor(ReaderLightTextArgb),
    val backgroundColor: ReaderColor = ReaderColor(ReaderLightBackgroundArgb),
    val backgroundImage: BackgroundImage? = null,
    val themeMode: ReaderThemeMode = ReaderThemeMode.PUBLISHER,
) {
    init {
        require(fontSizeSp in 8f..80f) { "fontSizeSp must be 8..80." }
        require(lineHeightMultiplier in 1f..3f) { "lineHeightMultiplier must be 1..3." }
    }
}

/**
 * The line-height slider's neutral point, and the reader's out-of-the-box line height.
 *
 * This is part of the line-height contract, not just a default: a block whose book states its own line
 * height draws it *exactly as stated* while the slider sits here, and scales it proportionally as the
 * slider moves (see the renderer's paragraph styling). Multiplying the book's value by the slider's raw
 * value instead set every styled book's lines 45% looser than the book asked for before the reader
 * touched anything.
 */
const val ReaderDefaultLineHeightMultiplier: Float = 1.45f

/**
 * The part of a [ReaderStyle] that decides where the pages break.
 *
 * Laying a book out is the most expensive thing the reader does, and only type decides the outcome:
 * the same text at the same size, line height and family breaks in the same places whatever colour
 * it is drawn in. Comparing whole styles instead made a theme switch look like a new measurement and
 * laid the entire book out again for a change that cannot move a single line.
 *
 * @property fontSizeSp the type size pages were measured at.
 * @property lineHeightMultiplier the line height they were measured at.
 * @property fontFamilyName the family they were measured with, or null for the system default.
 */
data class ReaderLayoutKey(
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val fontFamilyName: String?,
)

/** The [ReaderLayoutKey] for this style — what to compare when the question is "must this be measured again?".
 * @receiver the style to reduce.
 * @return only the fields that decide page boundaries.
 */
fun ReaderStyle.layoutKey(): ReaderLayoutKey = ReaderLayoutKey(
    fontSizeSp = fontSizeSp,
    lineHeightMultiplier = lineHeightMultiplier,
    fontFamilyName = "${fontFamilyName ?: publisherFontKey ?: ""}$LayoutAlgorithmVersionSuffix",
)

/**
 * Marker folded into every [ReaderLayoutKey]'s family field, bumped whenever the *layout algorithm*
 * changes — gap sizing, style resolution, anything that moves a line without moving a character.
 *
 * Stored page layouts are keyed by the layout key plus a character count, so an algorithm change that
 * left the text identical would otherwise keep serving page breaks measured by the old code, clipping
 * pages until the user happened to change a setting. Folding the version into the key makes every stored
 * layout from an older algorithm a clean cache miss instead; the store's own trimming then discards them.
 */
private const val LayoutAlgorithmVersionSuffix = "#layout7"

/**
 * This style under [mode], which is how a theme choice is applied: the mode's colours are copied in and
 * the mode is recorded with them.
 *
 * Switching to a built-in theme also drops any background image, because a picture chosen for one set
 * of page colours makes text illegible under another. [ReaderThemeMode.CUSTOM] keeps every colour as it
 * is — it means "the reader chose these", so there is nothing to overwrite.
 *
 * @receiver the style to convert.
 * @param mode the theme to apply.
 * @return this style under [mode]: a built-in theme replaces the colours and drops any background image,
 * while `CUSTOM` keeps every colour and only records the mode.
 */
fun ReaderStyle.withThemeMode(mode: ReaderThemeMode): ReaderStyle = when (mode) {
    ReaderThemeMode.PUBLISHER,
    ReaderThemeMode.LIGHT,
        -> copy(
            textColor = ReaderColor(ReaderLightTextArgb),
            backgroundColor = ReaderColor(ReaderLightBackgroundArgb),
            backgroundImage = null,
            themeMode = mode,
        )

    ReaderThemeMode.SYSTEM -> copy(
        textColor = ReaderColor(ReaderLightTextArgb),
        backgroundColor = ReaderColor(ReaderLightBackgroundArgb),
        backgroundImage = null,
        themeMode = ReaderThemeMode.SYSTEM,
    )

    ReaderThemeMode.DARK -> copy(
        textColor = ReaderColor(ReaderDarkTextArgb),
        backgroundColor = ReaderColor(ReaderDarkBackgroundArgb),
        backgroundImage = null,
        themeMode = ReaderThemeMode.DARK,
    )

    ReaderThemeMode.SEPIA -> copy(
        textColor = ReaderColor(ReaderSepiaTextArgb),
        backgroundColor = ReaderColor(ReaderSepiaBackgroundArgb),
        backgroundImage = null,
        themeMode = ReaderThemeMode.SEPIA,
    )

    ReaderThemeMode.CUSTOM -> copy(
        themeMode = ReaderThemeMode.CUSTOM,
    )
}

/**
 * Applies the platform's current dark-theme setting to a style that asked to follow it.
 *
 * [ReaderThemeMode.SYSTEM] cannot be resolved at the moment it is chosen, because the answer changes
 * later — the user flips the system switch, or the device crosses into its night schedule, without ever
 * reopening this app's settings. So the stored style keeps the light page colours as a resting value
 * and the real decision is deferred to here, where the live system flag is available.
 *
 * Without this step the app read as two halves under `SYSTEM` on a dark device: chrome resolved through
 * the system flag and went dark, while the page kept the light colours that were persisted when the mode
 * was picked. Only the page colours move; the mode itself is preserved so the setting still reads back as
 * "follow system" rather than silently rewriting itself to dark.
 *
 * Every other mode is returned untouched — an explicit light, dark, or sepia choice is a decision to
 * ignore the system setting, and [ReaderThemeMode.PUBLISHER] keeps the document's own colours.
 *
 * Page layout is unaffected: [layoutKey] covers type size, line height and family, so changing colour
 * never invalidates a stored pagination.
 *
 * @receiver the persisted style, whose [ReaderStyle.themeMode] decides whether anything changes.
 * @param systemInDarkTheme the platform's live dark-theme flag, sampled by the UI layer.
 * @return this style with the page colours the system currently calls for, or unchanged when the mode
 * does not follow the system.
 */
fun ReaderStyle.resolveSystemTheme(systemInDarkTheme: Boolean): ReaderStyle =
    if (themeMode == ReaderThemeMode.SYSTEM && systemInDarkTheme) {
        copy(
            textColor = ReaderColor(ReaderDarkTextArgb),
            backgroundColor = ReaderColor(ReaderDarkBackgroundArgb),
        )
    } else {
        this
    }

/** A whole style in the dark theme, for a caller that has no existing style to convert. *
 * @return a complete style in the dark theme, with default type.
 */
fun darkReaderStyle(): ReaderStyle = ReaderStyle(
    textColor = ReaderColor(ReaderDarkTextArgb),
    backgroundColor = ReaderColor(ReaderDarkBackgroundArgb),
    themeMode = ReaderThemeMode.DARK,
)

/** A whole style in the sepia theme, for a caller that has no existing style to convert. *
 * @return a complete style in the sepia theme, with default type.
 */
fun sepiaReaderStyle(): ReaderStyle = ReaderStyle(
    textColor = ReaderColor(ReaderSepiaTextArgb),
    backgroundColor = ReaderColor(ReaderSepiaBackgroundArgb),
    themeMode = ReaderThemeMode.SEPIA,
)

/**
 * Which way a page turn goes, which also decides how a swipe or an edge tap is read.
 *
 * [CONTINUOUS] is only ever read, never written: it is a value older installs stored, kept in the enum
 * so their settings still deserialize, and treated as [VERTICAL] wherever it is resolved.
 */
@Serializable
enum class PageTurnMode {
    HORIZONTAL,
    VERTICAL,
    CONTINUOUS,
}

/**
 * How a page turn is animated. The reader picks its pager implementation from this value, so the set is
 * the list of pagers that exist, not a list of visual effects.
 *
 * [BOOK_CURL] and [SHEET_FLIP] are read-only leftovers of pagers that were replaced: they stay so older
 * stored settings still deserialize, and resolve to [CURL_PAGER] and [SLIDE] respectively.
 */
@Serializable
enum class PageAnimation {
    NONE,
    SLIDE,
    FADE,
    SCROLL,
    BOOK_CURL,
    SHEET_FLIP,
    FLUID_PAGER,
    CURL_PAGER,
    CIRCLE_REVEAL,
    MOVIE_CAROUSEL,
    PAGE_FLIP,
}

/**
 * What auto-scroll advances by: pixels for a smooth crawl, whole lines for a paced read, or whole pages.
 * The unit changes what "speed" means, which is why the two are stored together in [AutoScrollConfig].
 */
@Serializable
enum class AutoScrollMode {
    PIXEL,
    LINE,
    PAGE,
}

/**
 * Auto-scroll as one setting: whether it is on, what it advances by, and how fast.
 *
 * Speed is normalised to `MIN_SPEED..MAX_SPEED` rather than stored in pixels or lines per second,
 * because its real-world meaning depends on [mode] and on the device's own density; each pager converts
 * it at the point of use. [clampSpeed] is exposed so a slider can clamp before constructing, since the
 * `init` bound rejects rather than corrects.
 *
 * @property enabled whether auto-scroll is running.
 * @property mode what it advances by, which is what gives [speed] its meaning.
 * @property speed normalised 0.01..1, converted to pixels or lines by each pager at the point of use.
 * @throws IllegalArgumentException if [speed] is not positive — use [AutoScrollConfig.clampSpeed] on a
 * slider value first, since `init` rejects rather than corrects.
 */
@Serializable
data class AutoScrollConfig(
    val enabled: Boolean = false,
    val mode: AutoScrollMode = AutoScrollMode.PIXEL,
    val speed: Float = MAX_SPEED,
) {
    init {
        require(speed > 0f) { "Auto-scroll speed must be positive." }
    }

    /** Bounds and a clamp helper for [AutoScrollConfig.speed]. */
    companion object {
        /** The slowest speed [speed] accepts; the `init` block rejects anything at or below zero. */
        const val MIN_SPEED: Float = 0.01f

        /** The fastest speed [speed] accepts, and the default a config is built with when none is given. */
        const val MAX_SPEED: Float = 1f

        /**
         * Clamps a raw value, e.g. straight off a slider, into the range [AutoScrollConfig] accepts —
         * for a caller that must not risk the `init` block's [IllegalArgumentException] on an
         * out-of-range speed.
         *
         * @param speed a raw speed value to clamp.
         * @return [speed] coerced into [MIN_SPEED]..[MAX_SPEED].
         */
        fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }
}

/**
 * The size of the area a page is laid out into. Part of what a stored page layout is keyed on, since the
 * same book at the same type breaks differently in a different box.
 *
 * The unit is whichever the caller works in and is not encoded here — the reader keeps its pagination
 * viewport in sp and its pane-measurement viewport in px — so callers on both sides of that line must
 * not mix the two.
 *
 * @property widthPx width of the box a page is laid out into.
 * @property heightPx height of that box.
 * @throws IllegalArgumentException if either is not positive, since a page cannot be laid out into
 * nothing.
 */
@Serializable
data class ViewportSize(
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(widthPx > 0) { "Viewport width must be positive." }
        require(heightPx > 0) { "Viewport height must be positive." }
    }
}

/**
 * One page as the reader draws it: its number, where it starts in the document, its text, and the block
 * structure that styles that text.
 *
 * [location] is what makes a page addressable after re-pagination — the reader saves it, and finds the
 * page again from it once the pages change shape. [textRange] is the same span as absolute document
 * offsets, which is what search results, bookmarks and the section lookups compare against.
 *
 * [blocks] arriving empty is not a bug but a state: a section whose block structure has not been decoded
 * yet produces a page with plain text, and the same page is published again once it has. A caller that
 * needs styled text must ensure the blocks are decoded before it builds the page, not after.
 *
 * @property pageIndex this page's number and the total known when it was built.
 * @property location where the page starts, which is how it is found again after re-pagination.
 * @property text the page's text, ready to draw.
 * @property textRange the same span as absolute document offsets, for search, bookmarks and section
 * lookups.
 * @property blocks the structure that styles [text]; empty means the section's blocks are not decoded
 * yet, so the page renders as plain text and is published again once they are.
 */
@Serializable
data class PageWindow(
    val pageIndex: PageIndex,
    val location: ReaderLocation,
    val text: String,
    val textRange: TextRange? = null,
    val blocks: List<ReaderBlock> = emptyList(),
)

/**
 * Reading totals for one document, as the document-info screen shows them.
 *
 * [wordsPerMinute] is derived rather than stored so it can never disagree with the figures it comes
 * from, and it answers zero when there is no measured reading time — which today is always, since
 * nothing records reading sessions (see ReadingStatsRepository).
 *
 * @property documentId the document summarised.
 * @property activeMillis summed reading time — zero today, since no session is ever recorded.
 * @property charactersRead characters in the book, from the document itself.
 * @property wordsRead words in the book, from the document itself.
 * @throws IllegalArgumentException if any figure is negative.
 */
@Serializable
data class ReadingStats(
    val documentId: DocumentId,
    val activeMillis: Long,
    val charactersRead: Long,
    val wordsRead: Long,
) {
    init {
        require(activeMillis >= 0L) { "activeMillis must be positive." }
        require(charactersRead >= 0L) { "charactersRead must be positive." }
        require(wordsRead >= 0L) { "wordsRead must be positive." }
    }

    /** [wordsRead] per minute of [activeMillis], derived so it can never disagree with those two figures; 0f while no reading time is recorded. */
    val wordsPerMinute: Float = if (activeMillis == 0L) {
        0f
    } else {
        wordsRead.toFloat() / (activeMillis.toFloat() / MILLIS_PER_MINUTE)
    }
}
