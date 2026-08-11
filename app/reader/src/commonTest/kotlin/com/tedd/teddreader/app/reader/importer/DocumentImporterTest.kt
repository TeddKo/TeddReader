package com.tedd.teddreader.app.reader.importer

import com.tedd.teddreader.core.common.model.DocumentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentImporterTest {
    @Test
    fun importDocumentsReturnsSuccessfulIdsInInputOrderAndFailureCount() = runTest {
        val requests = listOf(
            ExternalDocumentImportRequest(sourceUri = "file:///alpha.epub"),
            ExternalDocumentImportRequest(sourceUri = "file:///broken.epub"),
            ExternalDocumentImportRequest(sourceUri = "file:///omega.epub"),
        )

        val (importedDocumentIds, failedCount) = importDocuments(requests) { request ->
            when (request.sourceUri) {
                "file:///broken.epub" -> throw IllegalStateException("boom")
                "file:///alpha.epub" -> DocumentId("doc-alpha")
                else -> DocumentId("doc-omega")
            }
        }

        assertEquals(listOf(DocumentId("doc-alpha"), DocumentId("doc-omega")), importedDocumentIds)
        assertEquals(1, failedCount)
    }

    @Test
    fun importDocumentsRethrowsCancellationException() = runTest {
        val cancellation = CancellationException("cancel import")

        val thrown = assertFailsWith<CancellationException> {
            importDocuments(listOf(ExternalDocumentImportRequest(sourceUri = "file:///cancel.epub"))) {
                throw cancellation
            }
        }

        assertEquals(cancellation, thrown)
    }
}
