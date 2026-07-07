package com.tedd.teddreader.feature.reader.impl.pdf

import com.tedd.teddreader.core.common.model.PageIndex

interface PdfPageRenderer {
    suspend fun loadPage(
        documentUri: String,
        pageIndex: Int,
    ): PdfPage
}

data class PdfPage(
    val pageIndex: PageIndex,
    val widthPx: Int,
    val heightPx: Int,
)
