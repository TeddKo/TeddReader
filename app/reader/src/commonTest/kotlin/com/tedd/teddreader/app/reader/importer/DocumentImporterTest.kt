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
    fun importErrorMessageNamesTheFirstFailureReason() = runTest {
        val result = importDocuments(listOf("a.txt", "b.txt")) { name ->
            when (name) {
                "a.txt" -> throw IllegalArgumentException("Unsupported document format: a.txt")
                else -> throw IllegalStateException("Cannot open document: b.txt")
            }
        }

        assertEquals(2, result.failedCount)
        assertEquals("Unsupported document format: a.txt", result.firstFailureReason)
        assertEquals(
            "2 documents failed to import. Unsupported document format: a.txt",
            result.toImportErrorMessage(),
        )
    }

    @Test
    fun importErrorMessageFallsBackToTheExceptionTypeWhenThereIsNoMessage() = runTest {
        val result = importDocuments(listOf("a.txt")) { throw IllegalStateException() }

        assertEquals("IllegalStateException", result.firstFailureReason)
    }

    /** 문서 크기 상한 이하의 알려진 크기는 정상 가져오기 대상으로 유지되는지 검증한다. */
    @Test
    fun documentSizeLimitAcceptsMaximumSize() {
        requireDocumentSizeWithinLimit(
            sizeBytes = MaximumDocumentImportBytes,
            displayName = "maximum.cbz",
        )
    }

    /** provider가 보고한 크기가 상한을 넘으면 다운로드나 파싱 전에 거부되는지 검증한다. */
    @Test
    fun documentSizeLimitRejectsReportedOversize() {
        assertFailsWith<IllegalStateException> {
            requireDocumentSizeWithinLimit(
                sizeBytes = MaximumDocumentImportBytes + 1L,
                displayName = "oversize.pdf",
            )
        }
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
