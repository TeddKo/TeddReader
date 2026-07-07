package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    @Upsert
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    suspend fun getProgress(documentId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    fun observeProgress(documentId: String): Flow<ReadingProgressEntity?>

    @Query("DELETE FROM reading_progress WHERE documentId = :documentId")
    suspend fun deleteProgress(documentId: String)
}
