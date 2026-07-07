package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceUri: String,
    val format: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val addedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long? = null,
    val pageCount: Int? = null,
    val characterCount: Long? = null,
    val wordCount: Long? = null,
)
