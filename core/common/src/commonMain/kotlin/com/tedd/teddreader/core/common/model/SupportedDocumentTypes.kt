package com.tedd.teddreader.core.common.model

val SupportedDocumentFormats: Set<DocumentFormat> = setOf(
    DocumentFormat.TXT,
    DocumentFormat.PDF,
    DocumentFormat.EPUB,
)

val SupportedDocumentMimeTypes: Set<String> = setOf(
    "text/plain",
    "application/pdf",
    "application/epub",
    "application/epub+zip",
)

val SupportedDocumentExtensions: Set<String> = setOf(
    "txt",
    "pdf",
    "epub",
)
