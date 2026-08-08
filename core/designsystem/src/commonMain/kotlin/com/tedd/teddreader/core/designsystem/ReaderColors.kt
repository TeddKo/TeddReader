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

fun ReaderColor.toColor(): Color = Color(argb.toInt())
