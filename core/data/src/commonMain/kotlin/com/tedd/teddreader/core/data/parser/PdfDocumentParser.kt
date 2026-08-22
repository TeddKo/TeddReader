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
     * @param location where the PDF came from; passed through to [metadataReader] as the handle a
     *   platform reader needs to open the file (a path or content URI, not just raw bytes).
     * @param bytes the PDF's raw contents, passed through to [metadataReader] for platforms that read
     *   the page count directly from bytes rather than reopening [location].
     * @return a [ReaderDocument] of [DocumentFormat.PDF] with no sections and a page count taken from
     *   [metadataReader], floored at 1 so a reader that fails to determine a count — or reports zero or
     *   a negative number for a malformed PDF — never yields an unopenable, page-less document.
     */
    fun parse(
        id: DocumentId,
        title: String,
        location: DocumentLocation,
        bytes: ByteArray,
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
     * @param bytes the PDF's raw contents; see [parse].
     * @return the cover image bytes [metadataReader] renders, or null when the platform reader has no
     *   cover support (its default returns null) or none could be produced for this file.
     */
    fun coverImageBytes(
        location: DocumentLocation,
        bytes: ByteArray,
    ): ByteArray? = metadataReader.coverImageBytes(location, bytes)
}
