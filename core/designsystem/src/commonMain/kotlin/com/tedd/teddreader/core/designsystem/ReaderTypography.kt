package com.tedd.teddreader.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.tedd.teddreader.core.common.model.ReaderStyle

fun ReaderStyle.readerTextStyle(): TextStyle = TextStyle(
    color = textColor.toColor(),
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
    fontFamily = fontFamilyName.toFontFamily(),
)

private fun String?.toFontFamily(): FontFamily = when (this?.lowercase()) {
    "serif" -> FontFamily.Serif
    "mono", "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}
