package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "reading_sessions",
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
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val activeMillis: Long,
    val startLocation: String,
    val endLocation: String? = null,
)
