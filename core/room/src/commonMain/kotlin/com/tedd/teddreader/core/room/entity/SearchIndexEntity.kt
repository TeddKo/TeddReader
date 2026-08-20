package com.tedd.teddreader.core.room.entity

import androidx.room3.ColumnInfo
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
    val blocksJson: String = "[]",
    val documentTitle: String? = null,
    val navigationJson: String = "",
    // Which build of the parser wrote this row. Comparing a number is how the reader knows stored text
    // predates a parser change; the alternative was inspecting the blocks themselves for traces of the
    // older code, which on a book whose first illustration sits in chapter 292 meant decoding 293
    // chapters on every open just to ask the question.
    @ColumnInfo(defaultValue = "0")
    val parserVersion: Int = 0,
)
