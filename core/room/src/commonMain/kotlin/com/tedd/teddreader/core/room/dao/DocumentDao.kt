package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * The library table: what documents exist and when each was added or last opened.
 *
 * [observeRecentDocuments] orders by last-opened falling back to added, which is what makes the home
 * screen show a freshly imported book at the top before it has ever been read. Deleting a document here
 * takes its progress, bookmarks, search index and page layouts with it — those tables cascade on this
 * row — so nothing else has to clean up after a removal.
 */
@Dao
interface DocumentDao {
    /**
     * Inserts a library row or replaces the existing one.
     *
     * @param document the whole row; every column is written, so a caller edits a copy of what it read.
     */
    @Upsert
    suspend fun upsertDocument(document: DocumentEntity)

    /**
     * @param documentId the document's id.
     * @return its row, or null when nothing has been imported under that id.
     */
    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getDocument(documentId: String): DocumentEntity?

    /**
     * @return a flow of every library row, most recently opened first and falling back to when it was
     * added, so a freshly imported book sits at the top before it has ever been read.
     */
    @Query("SELECT * FROM documents ORDER BY COALESCE(lastOpenedAtEpochMillis, addedAtEpochMillis) DESC")
    fun observeRecentDocuments(): Flow<List<DocumentEntity>>

    /**
     * Rewrites the bookmarked flag for every matching row in one statement.
     */
    @Query("UPDATE documents SET isBookmarked = :isBookmarked WHERE id IN (:documentIds)")
    suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean)

    /**
     * Rewrites the folder pair for every matching row in one statement.
     */
    @Query("UPDATE documents SET folderId = :folderId, folderName = :folderName WHERE id IN (:documentIds)")
    suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?)

    /**
     * Renames every row currently carrying [folderId].
     */
    @Query("UPDATE documents SET folderName = :folderName WHERE folderId = :folderId")
    suspend fun renameFolder(folderId: String, folderName: String)

    /**
     * Clears [folderId] from every current member in one statement.
     */
    @Query("UPDATE documents SET folderId = NULL, folderName = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)

    /**
     * Stamps an open, which is the only thing that reorders the library list.
     *
     * @param documentId the document that was opened.
     * @param openedAtEpochMillis when it was opened.
     */
    @Query("UPDATE documents SET lastOpenedAtEpochMillis = :openedAtEpochMillis WHERE id = :documentId")
    suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long)

    /**
     * Removes a library row, and with it — by cascade — its progress, saved places, stored text and
     * measured page layouts.
     *
     * @param documentId the document to remove.
     */
    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    /**
     * Removes many library rows in one statement.
     */
    @Query("DELETE FROM documents WHERE id IN (:documentIds)")
    suspend fun deleteDocuments(documentIds: List<String>)

    /**
     * Updates only the character/word counts and embedded-font index for a document, leaving every
     * other column — favourite, folder, lastOpened — untouched. Used by import batches so a concurrent
     * library edit (starring, moving to a folder) is never clobbered by an import that reads and
     * rewrites the whole row.
     *
     * @param documentId the document to update.
     * @param characterCount the accumulated character count so far.
     * @param wordCount the accumulated word count so far.
     * @param embeddedFontHrefsJson the JSON-encoded sorted font-href set, or null to clear the index.
     */
    @Query(
        "UPDATE documents SET characterCount = :characterCount, wordCount = :wordCount, " +
            "embeddedFontHrefsJson = :embeddedFontHrefsJson WHERE id = :documentId",
    )
    suspend fun updateCountsAndFontIndex(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        embeddedFontHrefsJson: String?,
    )

    /**
     * Stamps a document's import as complete and writes the final counts in one targeted update,
     * without touching favourite/folder/lastOpened columns.
     *
     * @param documentId the document to mark complete.
     * @param characterCount the final character count.
     * @param wordCount the final word count.
     * @param importCompletedAtEpochMillis the completion timestamp.
     */
    @Query(
        "UPDATE documents SET characterCount = :characterCount, wordCount = :wordCount, " +
            "importCompletedAtEpochMillis = :importCompletedAtEpochMillis WHERE id = :documentId",
    )
    suspend fun updateCountsAndMarkComplete(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        importCompletedAtEpochMillis: Long,
    )

    /**
     * Writes only the embedded font href index column, leaving everything else untouched.
     * Used by the legacy backfill path after the first full-blocks scan.
     *
     * @param documentId the document to update.
     * @param embeddedFontHrefsJson the JSON-encoded sorted font-href set.
     */
    @Query("UPDATE documents SET embeddedFontHrefsJson = :embeddedFontHrefsJson WHERE id = :documentId")
    suspend fun updateEmbeddedFontHrefsJson(documentId: String, embeddedFontHrefsJson: String)
}
