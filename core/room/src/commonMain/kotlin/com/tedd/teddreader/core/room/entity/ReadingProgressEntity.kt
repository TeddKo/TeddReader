package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Where one document is being read. The document id *is* the primary key, so there is exactly one row per
 * book and a page turn replaces it rather than appending history.
 *
 * [currentPageIndex] and [totalPageCount] are what the reader last displayed, kept beside the durable
 * [readerLocation] so a screen can show a number before it has laid anything out; the location is what a
 * resume actually uses. [totalPageCount] is nullable because a book still being measured has no total to
 * claim.
 *
 * @property documentId the book, and the primary key — one position per document, replaced in place.
 * @property readerLocation the durable position, as the reader's own compact `prefix:…` string.
 * @property currentPageIndex the page last displayed, for showing progress before anything is laid out.
 * @property totalPageCount pages known when that page was displayed, or NULL while nothing was measured.
 * @property updatedAtEpochMillis when the row was written; the reader passes 0 today.
 */
@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class ReadingProgressEntity(
    @PrimaryKey val documentId: String,
    val readerLocation: String,
    val currentPageIndex: Int,
    val totalPageCount: Int? = null,
    val updatedAtEpochMillis: Long,
)
