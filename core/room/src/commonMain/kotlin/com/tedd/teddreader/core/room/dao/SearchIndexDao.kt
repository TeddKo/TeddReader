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

    // Everything opening a document needs except blocksJson — on a big book that column dwarfs every
    // other one combined, and every open used to pull all of it into memory as strings before a single
    // page was built. [getSectionBlocksJson] fetches that column back, only for the sections something
    // actually asks for.
    @Query(
        "SELECT sectionIndex, sectionTitle, text, startOffset, endOffset, documentTitle, navigationJson, parserVersion " +
            "FROM search_index WHERE documentId = :documentId ORDER BY sectionIndex",
    )
    suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry>

    @Query("SELECT sectionIndex, blocksJson FROM search_index WHERE documentId = :documentId AND sectionIndex IN (:sectionIndexes)")
    suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>): List<SectionBlocksJsonEntry>

    // What a progressive import resumes from: the last section already stored, and the offset right
    // after it — see DocumentRepositoryImpl.importNextSections. Reading only this one row instead of
    // every section (getDocumentSectionsWithoutBlocks) is what keeps resuming cheap late in a large book.
    @Query("SELECT sectionIndex, endOffset FROM search_index WHERE documentId = :documentId ORDER BY sectionIndex DESC LIMIT 1")
    suspend fun getLastSection(documentId: String): SectionOffsetEntry?

    // A progressive import defers retitling from navigation, and the navigation/title columns
    // themselves, until its last batch (see DocumentRepositoryImpl.importNextSections) instead of
    // resolving them — wrongly, since not every section is known yet — after every batch.
    @Query("UPDATE search_index SET sectionTitle = :title WHERE documentId = :documentId AND sectionIndex = :sectionIndex")
    suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String)

    @Query(
        "UPDATE search_index SET documentTitle = :documentTitle, navigationJson = :navigationJson " +
            "WHERE documentId = :documentId AND sectionIndex = :sectionIndex",
    )
    suspend fun updateDocumentTitleAndNavigation(
        documentId: String,
        sectionIndex: Int,
        documentTitle: String,
        navigationJson: String,
    )

    @Query("DELETE FROM search_index WHERE documentId = :documentId")
    suspend fun deleteSearchIndex(documentId: String)
}

/** [SearchIndexDao.getLastSection]'s answer: enough to resume a progressive import without reading
 * every section already stored. */
data class SectionOffsetEntry(
    val sectionIndex: Int,
    val endOffset: Long,
)

/** [SearchIndexEntity] without its `blocksJson` column — see [SearchIndexDao.getDocumentSectionsWithoutBlocks]. */
data class SearchIndexSectionEntry(
    val sectionIndex: Int,
    val sectionTitle: String?,
    val text: String,
    val startOffset: Long,
    val endOffset: Long,
    val documentTitle: String?,
    val navigationJson: String,
    val parserVersion: Int,
)

/** One section's `blocksJson`, fetched on demand — see [SearchIndexDao.getSectionBlocksJson]. */
data class SectionBlocksJsonEntry(
    val sectionIndex: Int,
    val blocksJson: String,
)
