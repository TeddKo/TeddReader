package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.PageLayoutEntity

/**
 * The measured page boundaries of a book, cached so a re-open never re-measures it.
 *
 * A row is identified by document, type and viewport together, because that whole tuple is what a
 * measurement is true for — the same book at a larger font, or on a wider pane, breaks elsewhere. Rows are
 * a cache and nothing more: pagination is deterministic on identical input, so a deleted row costs one
 * re-measurement and never data.
 *
 * The table is bounded by [trimPageLayouts] rather than by expiry, since what makes it grow is a reader
 * trying type settings, not time passing.
 */
@Dao
interface PageLayoutDao {
    /**
     * Writes a measurement, replacing any row with the same (document, type, viewport) key.
     *
     * @param layout the measured page starts and the key they belong to.
     */
    @Upsert
    suspend fun upsertPageLayout(layout: PageLayoutEntity)

    /**
     * @param documentId the book.
     * @param fontSizeSp the type size to match exactly.
     * @param lineHeightMultiplier the line height to match exactly.
     * @param fontFamilyName the family to match; `""` stands for the system default (see PageLayoutEntity).
     * @param viewportWidthPx the pane width to match exactly.
     * @param viewportHeightPx the pane height to match exactly.
     * @return the stored measurement for exactly that combination, or null when none was ever made.
     */
    @Query(
        "SELECT * FROM page_layouts WHERE documentId = :documentId AND fontSizeSp = :fontSizeSp AND " +
            "lineHeightMultiplier = :lineHeightMultiplier AND fontFamilyName = :fontFamilyName AND " +
            "viewportWidthPx = :viewportWidthPx AND viewportHeightPx = :viewportHeightPx",
    )
    suspend fun getPageLayout(
        documentId: String,
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamilyName: String,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): PageLayoutEntity?

    /**
     * The newest measurement for this type at *any* viewport, for a caller that has no pane measurement
     * yet.
     *
     * Ignoring the viewport is the point: a row measured for this type at some pane size is still a real
     * measurement, and the most recent one is the best available guess at what the pane is about to report,
     * almost always on the very same physical screen. That beats paginating against a guessed viewport,
     * which would publish a page count the first real measurement then contradicts.
     *
     * @param documentId the book.
     * @param fontSizeSp the type size to match.
     * @param lineHeightMultiplier the line height to match.
     * @param fontFamilyName the family to match; `""` stands for the system default.
     * @return the most recently written measurement for that type at any viewport, or null when none exists.
     */
    @Query(
        "SELECT * FROM page_layouts WHERE documentId = :documentId AND fontSizeSp = :fontSizeSp AND " +
            "lineHeightMultiplier = :lineHeightMultiplier AND fontFamilyName = :fontFamilyName " +
            "ORDER BY writtenAtEpochMillis DESC LIMIT 1",
    )
    suspend fun getNewestPageLayoutForStyle(
        documentId: String,
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamilyName: String,
    ): PageLayoutEntity?

    /**
     * Drops every measurement of one book, which costs a re-measurement and never data.
     *
     * @param documentId the book whose measurements to discard.
     */
    @Query("DELETE FROM page_layouts WHERE documentId = :documentId")
    suspend fun deletePageLayouts(documentId: String)

    /**
     * Keeps only the [keep] most recently written rows for a document, discarding older measurements.
     *
     * A reader who tries a few font sizes or line heights before settling measures a new layout each time,
     * so without this the table would grow by one row per combination ever tried. Newest-first is the right
     * order to keep: the settings a reader arrived at are the ones they will open the book with again.
     *
     * @param documentId the book to bound.
     * @param keep how many of the most recently written measurements to keep.
     */
    @Query(
        "DELETE FROM page_layouts WHERE documentId = :documentId AND rowid NOT IN " +
            "(SELECT rowid FROM page_layouts WHERE documentId = :documentId " +
            "ORDER BY writtenAtEpochMillis DESC LIMIT :keep)",
    )
    suspend fun trimPageLayouts(documentId: String, keep: Int)

    /**
     * Deletes all partial-layout rows for a document — used when the document grows (a new import
     * batch lands) so stale partial measurements that addressed an older prefix are not mistakenly
     * restored by a later open.
     *
     * @param documentId the document whose partial rows to discard.
     */
    @Query("DELETE FROM page_layouts WHERE documentId = :documentId AND isPartial = 1")
    suspend fun deletePartialPageLayouts(documentId: String)

    /**
     * Promotes a partial-layout row to complete by setting its `isPartial` flag to `0`. Called once
     * the import completes and the row's existing character count proves its measurement covers the
     * whole document.
     *
     * @param documentId the document whose partial rows to promote.
     * @param characterCount the exact character count the row must carry to be promoted — only rows
     *   whose `characterCount` already matches are touched, so a stale row for an older prefix is
     *   never accidentally promoted.
     */
    @Query(
        "UPDATE page_layouts SET isPartial = 0 WHERE documentId = :documentId " +
            "AND characterCount = :characterCount AND isPartial = 1",
    )
    suspend fun promotePartialLayouts(documentId: String, characterCount: Long)
}
