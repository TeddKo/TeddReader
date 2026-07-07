package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Upsert
    suspend fun upsertDocument(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getDocument(documentId: String): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY COALESCE(lastOpenedAtEpochMillis, addedAtEpochMillis) DESC")
    fun observeRecentDocuments(): Flow<List<DocumentEntity>>

    @Query("UPDATE documents SET lastOpenedAtEpochMillis = :openedAtEpochMillis WHERE id = :documentId")
    suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long)

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)
}
