package com.tedd.teddreader.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.tedd.teddreader.core.common.model.ReaderStyle

/**
 * The reader's body text.
 *
 * [LineHeightStyle.Mode.Minimum] is what lets a picture sit in the text at all. The reader draws a
 * standalone image as inline content inside the page's text, and the default mode, `Fixed`, holds
 * every line box to exactly [lineHeight] — Compose documents it as "middle lines respect the
 * specified line height at all times and tall glyphs can overflow to upper or lower lines", which is
 * precisely an illustration drawn across the prose above and below it. `Minimum` treats the line
 * height as a floor instead, so a line holding an image grows to the image and the text moves out of
 * its way; the same measurement then tells pagination how much of the page that image really takes.
 */
fun ReaderStyle.readerTextStyle(): TextStyle = TextStyle(
    color = textColor.toColor(),
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.None,
        mode = LineHeightStyle.Mode.Minimum,
    ),
    fontFamily = fontFamilyName.toFontFamily(),
)

private fun String?.toFontFamily(): FontFamily = when (this?.lowercase()) {
    "serif" -> FontFamily.Serif
    "mono", "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}
