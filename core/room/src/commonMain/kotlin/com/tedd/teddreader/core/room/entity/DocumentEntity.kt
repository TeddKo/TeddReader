package com.tedd.teddreader.core.room.entity

import androidx.room3.ColumnInfo
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
    @ColumnInfo(defaultValue = "0")
    val isBookmarked: Boolean = false,
    val folderId: String? = null,
    val folderName: String? = null,
) {
    init {
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
    }
}
