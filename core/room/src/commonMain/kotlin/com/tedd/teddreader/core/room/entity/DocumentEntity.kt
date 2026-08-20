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
    // NULL means the import that produced this row never finished. Unused by any code as of this
    // column's introduction (TeddReaderMigration7To8) — it exists so a later progressive-import change
    // needs no second schema bump. Existing rows are backfilled to their addedAtEpochMillis by that
    // migration, because a row that already exists was, by definition, imported completely.
    val importCompletedAtEpochMillis: Long? = null,
) {
    init {
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
    }
}
