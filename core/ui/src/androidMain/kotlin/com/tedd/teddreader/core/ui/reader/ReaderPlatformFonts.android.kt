package com.tedd.teddreader.core.ui.reader

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.io.File

internal actual fun readerFontFamilyFromFile(path: String): FontFamily? =
    runCatching {
        val file = File(path)
        if (!file.isFile) return null
        FontFamily(Typeface.createFromFile(file))
    }.getOrNull()
