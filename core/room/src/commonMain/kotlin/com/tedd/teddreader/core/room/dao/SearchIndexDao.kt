package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.SearchIndexEntity

@Dao
interface SearchIndexDao {
    @Upsert
    suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>)

    @Query("SELECT * FROM search_index WHERE documentId = :documentId AND text LIKE '%' || :query || '%' ORDER BY sectionIndex LIMIT :limit")
    suspend fun search(documentId: String, query: String, limit: Int): List<SearchIndexEntity>

    @Query("SELECT * FROM search_index WHERE documentId = :documentId ORDER BY sectionIndex")
    suspend fun getDocumentSections(documentId: String): List<SearchIndexEntity>

    @Query("DELETE FROM search_index WHERE documentId = :documentId")
    suspend fun deleteSearchIndex(documentId: String)
}
