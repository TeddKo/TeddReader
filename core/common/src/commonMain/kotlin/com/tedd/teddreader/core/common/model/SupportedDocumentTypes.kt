package com.tedd.teddreader.core.common.model

val SupportedDocumentFormats: Set<DocumentFormat> = setOf(
    DocumentFormat.TXT,
    DocumentFormat.PDF,
    DocumentFormat.EPUB,
    DocumentFormat.CBZ,
    DocumentFormat.IMAGE,
)

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
