package com.tedd.teddreader.core.ui.reader

import androidx.compose.ui.text.font.FontFamily

internal expect fun readerFontFamilyFromFile(path: String): FontFamily?

fun loadReaderEmbeddedFontFamilies(fontFilesByHref: Map<String, String>): Map<String, FontFamily> =
    buildMap(fontFilesByHref.size) {
        fontFilesByHref.forEach { (href, path) ->
            readerFontFamilyFromFile(path)?.let { put(href, it) }
        }
    }
