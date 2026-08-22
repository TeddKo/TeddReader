package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation

/**
 * Reads what this reader needs out of a PDF's structure — page count and a cover thumbnail — without
 * a common-Kotlin PDF library to do it with. Both platforms have their own native PDF framework
 * (Android's `PdfRenderer`, iOS's PDFKit) capable of this, so each [defaultPdfMetadataReader]
 * implementation wraps its platform's framework instead. An implementation may resolve the document
 * from either [DocumentLocation.sourceUri] or the raw [bytes] depending on what its native API needs
 * a file path versus a byte buffer for; callers must keep both pointing at the same document, since a
 * given method here is free to use either.
 */
fun interface PdfMetadataReader {
    /**
     * The document's page count.
     *
     * @param location The document's location; some implementations resolve the file directly from
     *   [DocumentLocation.sourceUri] instead of reading [bytes].
     * @param bytes The document's raw bytes.
     * @return The page count. Implementations are expected to never throw and to never return less
     *   than 1, falling back to `1` on any failure to read the PDF's actual structure, since a
     *   document this reader has already accepted needs at least one page to show.
     */
    fun pageCount(location: DocumentLocation, bytes: ByteArray): Int

    /**
     * A thumbnail of the document's first page, for showing on the bookshelf without opening a full
     * PDF renderer every time.
     *
     * @param location The document's location; some implementations resolve the file directly from
     *   [DocumentLocation.sourceUri] instead of reading [bytes].
     * @param bytes The document's raw bytes.
     * @return PNG-encoded bytes of a thumbnail scaled to fit a small display box, or `null` if the
     *   document has no page to render or rendering it fails. The default implementation is `null`,
     *   for any future caller of this interface that only needs [pageCount].
     */
    fun coverImageBytes(location: DocumentLocation, bytes: ByteArray): ByteArray? = null
}

/** The platform's [PdfMetadataReader] — Android's wraps `PdfRenderer`, iOS's wraps PDFKit. */
internal expect fun defaultPdfMetadataReader(): PdfMetadataReader
