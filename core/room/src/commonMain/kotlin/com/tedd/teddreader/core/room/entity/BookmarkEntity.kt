package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A saved place, stored with its position as the compact `prefix:…` string the reader's own location type writes.
 *
 * The primary key is that composed id rather than a generated one, so saving the same place twice upserts
 * one row. The location is stored as text because it has to hold three different shapes — text offset,
 * spine plus offset, page number — and comparing or grepping one column beats three nullable ones.
 *
 * @property id the composed `"<documentId>:<location>"` key, which makes re-saving a place idempotent.
 * @property documentId the document the place is in.
 * @property readerLocation the position, as the reader's own compact `prefix:…` string.
 * @property label the reader's name for the place, or NULL when saved by the toggle alone.
 * @property note the reader's note about the passage, or NULL.
 * @property createdAtEpochMillis when it was saved, which orders the bookmarks screen.
 */
@Entity(
    tableName = "bookmarks",
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
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val readerLocation: String,
    val label: String? = null,
    val note: String? = null,
    val createdAtEpochMillis: Long,
)
