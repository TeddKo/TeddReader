package com.tedd.teddreader.core.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

private const val TEXT_LOCATION_PREFIX = "txt"
private const val EPUB_LOCATION_PREFIX = "epub"
private const val PDF_LOCATION_PREFIX = "pdf"
private const val MILLIS_PER_MINUTE = 60_000f

const val ReaderLightTextArgb: Long = 0xFF1F1F1FL
const val ReaderLightBackgroundArgb: Long = 0xFFFFFBF2L
const val ReaderDarkTextArgb: Long = 0xFFECE6D6L
const val ReaderDarkBackgroundArgb: Long = 0xFF12100DL
const val ReaderSepiaTextArgb: Long = 0xFF3B2F24L
const val ReaderSepiaBackgroundArgb: Long = 0xFFF4ECD8L

@Serializable
sealed interface ReaderLocation {
    fun asStorageString(): String

    @Serializable
    @SerialName(TEXT_LOCATION_PREFIX)
    data class TextOffset(val offset: Long) : ReaderLocation {
        init {
            require(offset >= 0L) { "Text offset must be positive." }
        }

        override fun asStorageString(): String = "$TEXT_LOCATION_PREFIX:$offset"
    }

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

    @Serializable
    @SerialName(PDF_LOCATION_PREFIX)
    data class PdfPage(val pageIndex: Int) : ReaderLocation {
        init {
            require(pageIndex >= 0) { "PDF page index must be positive." }
        }

        override fun asStorageString(): String = "$PDF_LOCATION_PREFIX:$pageIndex"
    }
}

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

private fun List<String>.requireInt(index: Int, source: String): Int =
    getOrNull(index)?.toIntOrNull() ?: error("Invalid ReaderLocation: $source")

private fun List<String>.requireLong(index: Int, source: String): Long =
    getOrNull(index)?.toLongOrNull() ?: error("Invalid ReaderLocation: $source")

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

    val progress: Float = if (total == 0) 0f else current.toFloat() / total.toFloat()
}

@Serializable
@JvmInline
value class ReaderColor(val argb: Long) {
    init {
        require(argb in MIN_ARGB..MAX_ARGB) { "ARGB color must fit 0xAARRGGBB." }
    }
}

private const val MIN_ARGB = 0x00000000L
private const val MAX_ARGB = 0xFFFFFFFFL

@Serializable
enum class ReaderThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    SEPIA,
    CUSTOM,
}

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

@Serializable
data class ReaderStyle(
    val fontSizeSp: Float = 18f,
    val fontFamilyName: String? = null,
    val lineHeightMultiplier: Float = 1.45f,
    val textColor: ReaderColor = ReaderColor(ReaderLightTextArgb),
    val backgroundColor: ReaderColor = ReaderColor(ReaderLightBackgroundArgb),
    val backgroundImage: BackgroundImage? = null,
    val themeMode: ReaderThemeMode = ReaderThemeMode.SYSTEM,
) {
    init {
        require(fontSizeSp in 8f..80f) { "fontSizeSp must be 8..80." }
        require(lineHeightMultiplier in 1f..3f) { "lineHeightMultiplier must be 1..3." }
    }
}

fun darkReaderStyle(): ReaderStyle = ReaderStyle(
    textColor = ReaderColor(ReaderDarkTextArgb),
    backgroundColor = ReaderColor(ReaderDarkBackgroundArgb),
    themeMode = ReaderThemeMode.DARK,
)

fun sepiaReaderStyle(): ReaderStyle = ReaderStyle(
    textColor = ReaderColor(ReaderSepiaTextArgb),
    backgroundColor = ReaderColor(ReaderSepiaBackgroundArgb),
    themeMode = ReaderThemeMode.SEPIA,
)

@Serializable
enum class PageTurnMode {
    HORIZONTAL,
    VERTICAL,
    CONTINUOUS,
}

@Serializable
enum class PageAnimation {
    NONE,
    SLIDE,
    FADE,
    SCROLL,
    BOOK_CURL,
    SHEET_FLIP, // Legacy stored value; deserialize as SLIDE for JSON compatibility.
    FLUID_PAGER,
    CURL_PAGER,
    CIRCLE_REVEAL,
    MOVIE_CAROUSEL,
    PAGE_FLIP,
}

@Serializable
enum class AutoScrollMode {
    PIXEL,
    PAGE,
}

@Serializable
data class AutoScrollConfig(
    val enabled: Boolean = false,
    val mode: AutoScrollMode = AutoScrollMode.PIXEL,
    val speed: Float = 1f,
) {
    init {
        require(speed > 0f) { "Auto-scroll speed must be positive." }
    }
}

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

@Serializable
data class PageWindow(
    val pageIndex: PageIndex,
    val location: ReaderLocation,
    val text: String,
    val textRange: TextRange? = null,
)

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

    val wordsPerMinute: Float = if (activeMillis == 0L) {
        0f
    } else {
        wordsRead.toFloat() / (activeMillis.toFloat() / MILLIS_PER_MINUTE)
    }
}
