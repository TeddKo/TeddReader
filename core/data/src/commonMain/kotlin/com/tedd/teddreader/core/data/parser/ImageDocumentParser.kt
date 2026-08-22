package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import org.koin.core.annotation.Single

/**
 * Turns a single raster image file into a one-page [ReaderDocument] so the reader can open a lone
 * picture the same way it opens a book — as something with a page count, rather than a special case
 * threaded through every call site that expects a [ReaderDocument]. It never inspects the image's own
 * bytes; there is nothing to parse beyond "this is one picture", so [pageCount][ReaderDocument] is
 * always 1 and [sections][ReaderDocument] is always empty. Decoding the actual pixels happens later,
 * on demand, wherever the page is drawn.
 */
@Single
class ImageDocumentParser {
    /**
     * Builds the one-page document for the image identified by [id].
     *
     * @param id identity of the source image, carried through unchanged so the caller can trace the
     *   document back to the file it was opened from.
     * @param title label shown for the document; this parser does not derive one from the image.
     * @return a [ReaderDocument] of [DocumentFormat.IMAGE] with no sections and a page count of 1.
     */
    fun parse(
        id: DocumentId,
        title: String,
    ): ReaderDocument = ReaderDocument(
        id = id,
        format = DocumentFormat.IMAGE,
        title = title,
        sections = emptyList(),
        pageCount = 1,
    )
}
