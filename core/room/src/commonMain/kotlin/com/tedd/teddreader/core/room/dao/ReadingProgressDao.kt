package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * Where each document is being read: exactly one row per document, replaced on every page turn.
 *
 * Both a suspend read and a flow exist because the two callers differ: opening a book needs the position
 * once, before it can lay anything out, while a screen showing progress needs to follow it. This is the
 * hottest write in the app — one per page turn — which is part of why the database stays in WAL mode.
 */
@Dao
interface ReadingProgressDao {
    /**
     * Writes a document's position, replacing the single row that document already has.
     *
     * @param progress the row to store.
     */
    @Upsert
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    /**
     * @param documentId the document being opened.
     * @return its stored position, or null when the book has never been opened.
     */
    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    suspend fun getProgress(documentId: String): ReadingProgressEntity?

    /**
     * @param documentId the document to watch.
     * @return a flow of its position, emitting null while the book has never been opened.
     */
    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    fun observeProgress(documentId: String): Flow<ReadingProgressEntity?>

    /**
     * @param documentId the document whose position is forgotten, so the next open starts it over.
     */
    @Query("DELETE FROM reading_progress WHERE documentId = :documentId")
    suspend fun deleteProgress(documentId: String)
}
