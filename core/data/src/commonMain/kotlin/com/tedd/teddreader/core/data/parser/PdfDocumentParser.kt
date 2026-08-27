package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.ReaderDocument
import org.koin.core.annotation.Single

/**
 * Opens a PDF as a purely page-counted [ReaderDocument]: no text is extracted and no [ReaderSection]s
 * are produced, because the pages this reader shows for a PDF are rendered images of the page, not
 * reflowed text. All of the real PDF work — reading the page count, and, where the platform supports
 * it, rendering a cover thumbnail — is delegated to [metadataReader], which wraps an actual PDF engine
 * per platform. This class only shapes that platform's answer into the common
 * [ReaderDocument]/cover-bytes contract the rest of the app expects.
 *
 * The API is **location-first**: [bytes] is nullable throughout, and callers that already have the PDF
 * materialized on disk (the normal case after import) pass `null` to avoid holding the entire file in
 * memory just to hand it to a platform reader that would immediately write it back out to a temporary
 * file. When [bytes] is non-null it serves as a fallback for platforms that cannot open
 * [DocumentLocation.sourceUri] directly (e.g. an un-materialized Android `content://` URI).
 *
 * @property metadataReader the platform's PDF reader. Defaults to [defaultPdfMetadataReader], the
 *   expect/actual factory that wires in the real per-platform implementation; tests pass a fake here to
 *   control the reported page count and cover bytes without touching a real PDF.
 */
@Single
class PdfDocumentParser(
    private val metadataReader: PdfMetadataReader = defaultPdfMetadataReader(),
) {
    /**
     * Builds the page-counted document for the PDF at [location].
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title label shown for the document; not derived from the PDF's own metadata here.
     * @param location where the PDF came from; passed through to [metadataReader] as the primary
     *   handle a platform reader needs to open the file (a path or content URI).
     * @param bytes the PDF's raw contents as a fallback for [metadataReader], or `null` when the
     *   caller knows [location] is a reachable local file and no in-memory fallback is needed.
     * @return a [ReaderDocument] of [DocumentFormat.PDF] with no sections and a page count taken from
     *   [metadataReader], floored at 1 so a reader that fails to determine a count — or reports zero or
     *   a negative number for a malformed PDF — never yields an unopenable, page-less document.
     */
    fun parse(
        id: DocumentId,
        title: String,
        location: DocumentLocation,
        bytes: ByteArray? = null,
    ): ReaderDocument = ReaderDocument(
        id = id,
        format = DocumentFormat.PDF,
        title = title,
        sections = emptyList(),
        pageCount = metadataReader.pageCount(location, bytes).coerceAtLeast(1),
    )

    /**
     * The PDF's cover thumbnail, straight from [metadataReader].
     *
     * @param location where the PDF came from; see [parse].
     * @param bytes the PDF's raw contents as a fallback, or `null` when [location] is a reachable
     *   local file; see [parse].
     * @return the cover image bytes [metadataReader] renders, or null when the platform reader has no
     *   cover support (its default returns null) or none could be produced for this file.
     */
    fun coverImageBytes(
        location: DocumentLocation,
        bytes: ByteArray? = null,
    ): ByteArray? = metadataReader.coverImageBytes(location, bytes)
}
