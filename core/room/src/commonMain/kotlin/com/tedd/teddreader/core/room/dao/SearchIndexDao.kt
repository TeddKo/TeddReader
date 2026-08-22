package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.SearchIndexEntity

/**
 * A document's stored text, one row per section — the table both search and the reader read from.
 *
 * It carries more than search needs, and deliberately so: the same rows hold the text a reader lays out,
 * the block structure that styles it, the book's title and table of contents, and the parser version that
 * wrote them. One table means opening a book is one query rather than a join, and a progressive import can
 * append sections to it as they are parsed.
 *
 * The column split is what makes that affordable. `blocksJson` dwarfs every other column on a large book,
 * so [getDocumentSectionsWithoutBlocks] leaves it out and [getSectionBlocksJson] fetches it back only for
 * the sections something is about to draw.
 */
@Dao
interface SearchIndexDao {
    /**
     * Writes or replaces stored sections. A progressive import calls this per batch, which is what lets a
     * book be read while the rest of it is still being parsed.
     *
     * @param entries the section rows to store.
     */
    @Upsert
    suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>)

    /**
     * @param documentId the document to search.
     * @param query the text to match, already trimmed by the repository.
     * @param limit the greatest number of *sections* to return; occurrences inside them are counted by the
     * caller.
     * @return the matching section rows in document order.
     */
    @Query("SELECT * FROM search_index WHERE documentId = :documentId AND text LIKE '%' || :query || '%' ORDER BY sectionIndex LIMIT :limit")
    suspend fun search(documentId: String, query: String, limit: Int): List<SearchIndexEntity>

    /**
     * Everything opening a document needs except `blocksJson`.
     *
     * That one column dwarfs all the others combined on a big book, and reading it here meant every open
     * pulled the whole of it into memory as strings before a single page was built. [getSectionBlocksJson]
     * fetches it back for the sections that actually need styling.
     *
     * @param documentId the document to load.
     * @return its sections in document order, each without its block structure.
     */
    @Query(
        "SELECT sectionIndex, sectionTitle, text, startOffset, endOffset, documentTitle, navigationJson, parserVersion " +
            "FROM search_index WHERE documentId = :documentId ORDER BY sectionIndex",
    )
    suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry>

    /**
     * @param documentId the document the sections belong to.
     * @param sectionIndexes the sections whose block structure is needed.
     * @return the stored JSON per section, omitting any section that has none.
     */
    @Query("SELECT sectionIndex, blocksJson FROM search_index WHERE documentId = :documentId AND sectionIndex IN (:sectionIndexes)")
    suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>): List<SectionBlocksJsonEntry>

    /**
     * The last section already stored and where its text ends — everything a progressive import needs to
     * resume.
     *
     * One row instead of every section is what keeps resuming cheap late in a large book: the alternative
     * ([getDocumentSectionsWithoutBlocks]) reads all the text imported so far just to find its end.
     *
     * @param documentId the document being imported.
     * @return the highest stored section and the offset just past its text, or null when nothing is stored
     * yet.
     */
    @Query("SELECT sectionIndex, endOffset FROM search_index WHERE documentId = :documentId ORDER BY sectionIndex DESC LIMIT 1")
    suspend fun getLastSection(documentId: String): SectionOffsetEntry?

    /**
     * Renames one stored section, which is how a title from the book's table of contents replaces the one
     * guessed from the chapter's own markup.
     *
     * A progressive import defers this, and the title/navigation columns, to its last batch: resolving a
     * heading against navigation before every section exists names some sections wrongly, and a title the
     * reader saw change under them is worse than one that arrives late.
     *
     * @param documentId the document.
     * @param sectionIndex the section to rename.
     * @param title the title taken from the book's own navigation.
     */
    @Query("UPDATE search_index SET sectionTitle = :title WHERE documentId = :documentId AND sectionIndex = :sectionIndex")
    suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String)

    /**
     * Writes the book's own title and table of contents, which belong to the document as a whole but are
     * stored on one section row so an open reads them with the text instead of in a second query.
     *
     * @param documentId the document.
     * @param sectionIndex the section row these book-wide values are stored on.
     * @param documentTitle the book's title.
     * @param navigationJson the book's table of contents, serialised.
     */
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

    /**
     * @param documentId the document whose stored text is removed.
     */
    @Query("DELETE FROM search_index WHERE documentId = :documentId")
    suspend fun deleteSearchIndex(documentId: String)
}

/**
 * [SearchIndexDao.getLastSection]'s answer: enough to resume a progressive import without reading
 * every section already stored.
 *
 * @property sectionIndex the highest section index already stored for the document.
 * @property endOffset one past the last character of that section — where the next import batch
 * resumes from.
 */
data class SectionOffsetEntry(
    val sectionIndex: Int,
    val endOffset: Long,
)

/**
 * [SearchIndexEntity] without its `blocksJson` column — see [SearchIndexDao.getDocumentSectionsWithoutBlocks].
 *
 * @property sectionIndex the section's position in document order.
 * @property sectionTitle the section's heading, or null when it has none.
 * @property text the section's text, line-ending normalised at parse time.
 * @property startOffset where that text begins in the whole document.
 * @property endOffset one past where it ends.
 * @property documentTitle the book's own title, present only on the section row it was written to.
 * @property navigationJson the book's table of contents, serialised the same way and on the same row.
 * @property parserVersion which build of the parser wrote this row, so the reader can tell stored text
 * that predates a parser change.
 */
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

/**
 * One section's `blocksJson`, fetched on demand — see [SearchIndexDao.getSectionBlocksJson].
 *
 * @property sectionIndex the section this block structure belongs to.
 * @property blocksJson the section's block structure, serialised.
 */
data class SectionBlocksJsonEntry(
    val sectionIndex: Int,
    val blocksJson: String,
)
