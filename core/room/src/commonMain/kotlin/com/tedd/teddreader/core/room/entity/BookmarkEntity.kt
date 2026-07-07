package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

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
