package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation

/**
 * Reads what this reader needs out of a PDF's structure — page count and a cover thumbnail — without
 * a common-Kotlin PDF library to do it with. Both platforms have their own native PDF framework
 * (Android's `PdfRenderer`, iOS's PDFKit) capable of this, so each [defaultPdfMetadataReader]
 * implementation wraps its platform's framework instead.
 *
 * The contract is **location-first**: implementations resolve the document from
 * [DocumentLocation.sourceUri] whenever it names a reachable local file path, falling back to the
 * raw [bytes] only when the location cannot be opened directly (a `content://` URI on Android that
 * has not been materialized, or a location the platform framework cannot reach). This avoids
 * re-materializing an already-on-disk PDF into a temporary file just to read its metadata or render
 * a cover, which was the redundant I/O path this interface previously forced by requiring bytes
 * unconditionally.
 *
 * Callers may pass `bytes = null` when they know [DocumentLocation.sourceUri] points at a readable
 * local file (a sandboxed copy on iOS, or a `file://` URI on Android after materialization). When
 * [bytes] is null and the location turns out to be unreachable, implementations fall back to
 * returning a safe default (1 for [pageCount], null for [coverImageBytes]) rather than throwing.
 */
fun interface PdfMetadataReader {
    /**
     * The document's page count.
     *
     * Implementations try [DocumentLocation.sourceUri] as a local file first; only when that path
     * is not directly openable do they fall back to writing [bytes] to a temporary file.
     *
     * @param location The document's location, whose [DocumentLocation.sourceUri] is the primary
     *   source for resolving the PDF.
     * @param bytes The document's raw bytes as a fallback when [location] cannot be opened
     *   directly, or `null` when the caller guarantees [location] is a reachable local file.
     * @return The page count. Implementations never throw and never return less than 1, falling
     *   back to `1` on any failure to read the PDF's actual structure, since a document this
     *   reader has already accepted needs at least one page to show.
     */
    fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int

    /**
     * A thumbnail of the document's first page, for showing on the bookshelf without opening a full
     * PDF renderer every time.
     *
     * Implementations try [DocumentLocation.sourceUri] as a local file first; only when that path
     * is not directly openable do they fall back to writing [bytes] to a temporary file.
     *
     * @param location The document's location, whose [DocumentLocation.sourceUri] is the primary
     *   source for resolving the PDF.
     * @param bytes The document's raw bytes as a fallback when [location] cannot be opened
     *   directly, or `null` when the caller guarantees [location] is a reachable local file.
     * @return PNG-encoded bytes of a thumbnail scaled to fit a small display box, or `null` if the
     *   document has no page to render or rendering it fails. The default implementation is `null`,
     *   for any future caller of this interface that only needs [pageCount].
     */
    fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? = null
}

/** The platform's [PdfMetadataReader] — Android's wraps `PdfRenderer`, iOS's wraps PDFKit. */
internal expect fun defaultPdfMetadataReader(): PdfMetadataReader
