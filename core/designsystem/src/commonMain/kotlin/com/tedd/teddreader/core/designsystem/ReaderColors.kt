package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderDarkBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderDarkTextArgb
import com.tedd.teddreader.core.common.model.ReaderLightBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderLightTextArgb
import com.tedd.teddreader.core.common.model.ReaderSepiaBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderSepiaTextArgb
import com.tedd.teddreader.core.common.model.ReaderStyle

/**
 * Every colour a reading page and its controls draw with.
 *
 * Separate from the app's own Material palette because a reading page is not app chrome: its ink and paper
 * are the reader's choice, and the controls floating over them have to stay legible against whatever that
 * choice is. Bundling them means a theme is one value to pass and one thing to swap.
 *
 * @property text the ink the book is set in.
 * @property background the paper behind it.
 * @property controls the surface of the bars and sheets floating over the page, alpha'd so the page shows
 * through.
 * @property controlsContent ink for those controls, chosen to stay legible on [controls].
 * @property selection the wash behind selected text.
 * @property highlight the wash behind a search hit or a highlighted passage.
 * @property bookmark the accent that marks a saved place.
 * @property divider hairlines between control rows.
 * @property dimOverlay the scrim over the page while a sheet or dialog is open.
 */
@Immutable
data class ReaderColors(
    val text: Color,
    val background: Color,
    val controls: Color,
    val controlsContent: Color,
    val selection: Color,
    val highlight: Color,
    val bookmark: Color,
    val divider: Color,
    val dimOverlay: Color,
)

/** Day reading: warm paper and near-black ink, the reader's default. */
val LightReaderColors = ReaderColors(
    text = ReaderColor(ReaderLightTextArgb).toColor(),
    background = ReaderColor(ReaderLightBackgroundArgb).toColor(),
    controls = PaperWarm.copy(alpha = 0.97f),
    controlsContent = Color(0xFF1F1F1F),
    selection = SageMuted.copy(alpha = 0.40f),
    highlight = ClayPrimary.copy(alpha = 0.40f),
    bookmark = ClayPrimary,
    divider = Color(0xFFE1D8CA),
    dimOverlay = Color(0x66000000),
)

/** Night reading in a dark room: dark paper, warm off-white ink to keep the glare down. */
val DarkReaderColors = ReaderColors(
    text = ReaderColor(ReaderDarkTextArgb).toColor(),
    background = ReaderColor(ReaderDarkBackgroundArgb).toColor(),
    controls = Color(0xF21D1B16),
    controlsContent = Color(0xFFECE6D6),
    selection = Color(0x66C8C0FF),
    highlight = Color(0x668A6A00),
    bookmark = ClayPrimary,
    divider = Color(0xFF36332D),
    dimOverlay = Color(0x99000000),
)

/** Sepia: aged-paper tone for long sessions in warm light. */
val SepiaReaderColors = ReaderColors(
    text = ReaderColor(ReaderSepiaTextArgb).toColor(),
    background = ReaderColor(ReaderSepiaBackgroundArgb).toColor(),
    controls = Color(0xF2E8D9BC),
    controlsContent = Color(0xFF3B2F24),
    selection = SageMuted.copy(alpha = 0.40f),
    highlight = Color(0x66D79A2B),
    bookmark = ClayPrimary,
    divider = Color(0xFFD8C7A3),
    dimOverlay = Color(0x66000000),
)

/** A deeper night than [DarkReaderColors], for reading with the lights off. */
val NightReaderColors = ReaderColors(
    text = Color(0xFFF2EDE2),
    background = CharcoalNight,
    controls = Color(0xF2231F24),
    controlsContent = Color(0xFFF2EDE2),
    selection = SageMuted.copy(alpha = 0.48f),
    highlight = ClayPrimary.copy(alpha = 0.38f),
    bookmark = ClayPrimary,
    divider = Color(0xFF4C463C),
    dimOverlay = Color(0xB3000000),
)

/** Pure black on white with saturated accents, for readers who need maximum contrast. */
val HighContrastReaderColors = ReaderColors(
    text = Color.White,
    background = Color.Black,
    controls = Color(0xFF111111),
    controlsContent = Color.White,
    selection = Color(0xFF2B61FF),
    highlight = Color(0x66FFD400),
    bookmark = Color(0xFFFFB000),
    divider = Color(0x66FFFFFF),
    dimOverlay = Color(0xCC000000),
)

/**
 * Builds a page palette from the colours the reader chose themselves.
 *
 * Only ink and paper come from the style — a reader picks those two, not nine — so the rest is derived:
 * controls take the page colour at 95% so the page shows through, their ink takes the text colour, and
 * dividers take the text colour at 16%. Selection, highlight and bookmark are kept from
 * [LightReaderColors] because they have to remain recognisable as *those* marks whatever the page is.
 *
 * @receiver the reader's own style, whose text and background colours drive everything here.
 * @return a palette that stays legible on the reader's own page colours.
 */
fun ReaderStyle.readerColors(): ReaderColors = ReaderColors(
    text = textColor.toColor(),
    background = backgroundColor.toColor(),
    controls = backgroundColor.toColor().copy(alpha = 0.95f),
    controlsContent = textColor.toColor(),
    selection = LightReaderColors.selection,
    highlight = LightReaderColors.highlight,
    bookmark = LightReaderColors.bookmark,
    divider = textColor.toColor().copy(alpha = 0.16f),
    dimOverlay = LightReaderColors.dimOverlay,
)

/**
 * Converts a stored colour into Compose's own, and the single crossing point between the model layer and
 * the UI layer's colour type.
 *
 * @receiver the stored `0xAARRGGBB` value.
 * @return the same colour as Compose sees it, alpha included.
 */
fun ReaderColor.toColor(): Color = Color(argb.toInt())
