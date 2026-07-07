package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "search_index",
    primaryKeys = ["documentId", "sectionIndex"],
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
data class SearchIndexEntity(
    val documentId: String,
    val sectionIndex: Int,
    val sectionTitle: String? = null,
    val text: String,
    val startOffset: Long,
    val endOffset: Long,
)
