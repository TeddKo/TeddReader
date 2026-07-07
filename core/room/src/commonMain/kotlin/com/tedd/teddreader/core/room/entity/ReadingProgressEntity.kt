package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

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
