package com.tedd.teddreader.app.reader.navigation

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.feature.bookmarks.api.BookmarksRoute
import com.tedd.teddreader.feature.document_info.api.DocumentInfoRoute
import com.tedd.teddreader.feature.home.api.HomeRoute
import com.tedd.teddreader.feature.home.api.LibraryRoute
import com.tedd.teddreader.feature.reader.api.ReaderRoute
import com.tedd.teddreader.feature.search.api.SearchRoute
import com.tedd.teddreader.feature.settings.api.SettingsRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderNavHostTest {
    @Test
    fun navigationKeysRoundTripThroughStorageTokens() {
        val documentId = "content://books.example/library:volume/7?chapter=part:2"
        val keys = listOf(
            HomeRoute,
            ReaderRoute(documentId),
            SearchRoute(documentId),
            BookmarksRoute(documentId),
            DocumentInfoRoute(documentId),
            SettingsRoute,
        )

        keys.forEach { key ->
            assertEquals(key, storageTokenToNavKey(navKeyToStorageToken(key)))
        }
    }

    @Test
    fun importedDocumentRouteReturnsReaderOnlyForSingleImport() {
        assertEquals(ReaderRoute("doc-1"), importedDocumentRoute(listOf(DocumentId("doc-1"))))
        assertNull(importedDocumentRoute(listOf(DocumentId("doc-1"), DocumentId("doc-2"))))
    }

    @Test
    fun libraryRouteRoundTripsThroughStorageTokens() {
        val keys = listOf(
            LibraryRoute(folderId = null),
            LibraryRoute(folderId = "550e8400-e29b-41d4-a716-446655440000"),
        )

        keys.forEach { key ->
            assertEquals(key, storageTokenToNavKey(navKeyToStorageToken(key)))
        }
    }

}
