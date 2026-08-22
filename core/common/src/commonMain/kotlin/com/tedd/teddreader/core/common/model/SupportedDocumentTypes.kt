package com.tedd.teddreader.core.common.model

/**
 * The formats this reader can actually open, as opposed to the ones [DocumentFormat] can name.
 *
 * Kept apart from the enum because the two answer different questions: the enum has to be able to name a
 * format the app has classified (including [DocumentFormat.UNKNOWN]), while this set is what an importer
 * accepts and what a folder scan filters by.
 */
val SupportedDocumentFormats: Set<DocumentFormat> = setOf(
    DocumentFormat.TXT,
    DocumentFormat.PDF,
    DocumentFormat.EPUB,
    DocumentFormat.CBZ,
    DocumentFormat.IMAGE,
)

/**
 * MIME types the document pickers are opened with, so the system's own file browser greys out what this
 * reader cannot read.
 *
 * More than one type per format on purpose: EPUB arrives as both `application/epub` and
 * `application/epub+zip`, and a comic as either of two vendor types, depending on which app or download
 * produced the file. Detection therefore treats these as hints and falls back on the file name, which is
 * why a file whose type is absent or wrong still imports.
 */
val SupportedDocumentMimeTypes: Set<String> = setOf(
    "text/plain",
    "application/pdf",
    "application/epub",
    "application/epub+zip",
    "application/vnd.comicbook+zip",
    "application/x-cbz",
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "image/bmp",
)

/**
 * What the Google Drive picker is asked for: the same set plus `application/zip`.
 *
 * Drive stores a CBZ as a plain zip, so a comic in Drive is invisible to a picker that asks only for the
 * comic types. The extra type is confined to Drive rather than added to [SupportedDocumentMimeTypes],
 * which would otherwise offer every zip on the device as if it were a book.
 */
val GoogleDriveSupportedDocumentMimeTypes: Set<String> = SupportedDocumentMimeTypes + "application/zip"

/**
 * File extensions a document is recognised by when its MIME type is missing or wrong, and what a folder
 * import walks a directory tree looking for.
 */
val SupportedDocumentExtensions: Set<String> = setOf(
    "txt",
    "pdf",
    "epub",
    "cbz",
    "jpg",
    "jpeg",
    "png",
    "webp",
    "gif",
    "bmp",
)
