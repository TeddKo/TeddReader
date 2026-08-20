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
        // The half-leading above the first line and below the last is trimmed, because pagination and
        // drawing disagree about it otherwise. Pagination lays the whole document out at once, so a
        // page's opening line is a middle line there and carries no leading of its own; the page is
        // then drawn on its own, that same line becomes a first line, and the leading appears — one
        // line height of it, which pushed the last line of the page off the bottom and clipped it. The
        // taller the type, the more it lost. Trimmed, an opening line measures the same either way.
        trim = LineHeightStyle.Trim.Both,
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
