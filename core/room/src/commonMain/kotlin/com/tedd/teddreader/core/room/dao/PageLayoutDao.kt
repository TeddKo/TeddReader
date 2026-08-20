package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.PageLayoutEntity

@Dao
interface PageLayoutDao {
    @Upsert
    suspend fun upsertPageLayout(layout: PageLayoutEntity)

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

    // Ignores viewport entirely — a row measured for this style at any pane size is a real
    // measurement, and the newest one is the reader's best guess at what the pane is about to report
    // again, almost always on the very same physical screen. See DocumentRepositoryImpl.getPageWindows,
    // which resolves through this when asked with no viewport at all.
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

    @Query("DELETE FROM page_layouts WHERE documentId = :documentId")
    suspend fun deletePageLayouts(documentId: String)

    // Keeps only the [keep] most recently written rows for a document. A reader who tries a few font
    // sizes or line heights before settling measures a new layout each time, and without this the table
    // would grow by one row per combination ever tried instead of staying bounded.
    @Query(
        "DELETE FROM page_layouts WHERE documentId = :documentId AND rowid NOT IN " +
            "(SELECT rowid FROM page_layouts WHERE documentId = :documentId " +
            "ORDER BY writtenAtEpochMillis DESC LIMIT :keep)",
    )
    suspend fun trimPageLayouts(documentId: String, keep: Int)
}
