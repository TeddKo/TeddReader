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
 *
 * `Trim.Both` is the other half of that agreement. Untrimmed, the half-leading above the first line and
 * below the last belongs to whoever renders the line: pagination lays the whole document out at once, so a
 * page's opening line is a middle line there and carries no leading, and then the page is drawn on its own,
 * that same line becomes a first line, the leading appears — one line height of it, which pushed the last
 * line of the page off the bottom and clipped it. The taller the type, the more was lost. Trimmed, an
 * opening line measures the same either way.
 *
 * @receiver the reader's style: its size, line height, family and ink colour.
 * @return the text style both the page renderer and the page breaker use, so a page holds exactly the lines
 * that were measured for it.
 */
fun ReaderStyle.readerTextStyle(): TextStyle = TextStyle(
    color = textColor.toColor(),
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.Both,
        mode = LineHeightStyle.Mode.Minimum,
    ),
    fontFamily = fontFamilyName.toFontFamily(),
)

/**
 * Maps a stored family name onto a family this platform actually has.
 *
 * Books and readers name families loosely (`mono`, `monospace`), and an unknown name has to render as
 * something rather than nothing, so anything unrecognised — including a null, meaning "no choice made" —
 * falls back to the platform sans-serif.
 *
 * @receiver the stored family name, or null when the reader made no choice.
 * @return the matching generic family, defaulting to sans-serif.
 */
private fun String?.toFontFamily(): FontFamily = when (this?.lowercase()) {
    "serif" -> FontFamily.Serif
    "mono", "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}
