package com.tedd.teddreader.core.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * A library row: one imported document, and the parent every other table cascades from.
 *
 * Identity is the source URI (`id`), so handing the app the same file twice resolves to this row rather
 * than importing a second copy. The counts are nullable because they are not known until a book is fully
 * parsed — a null [characterCount] is how the library recognises an import that has not finished.
 *
 * Folder membership is validated in `init` rather than left to the caller: a row that named a folder it
 * could not label would render as a blank chip in the library.
 *
 * @property id the source URI the document was imported from, and the key every other table cascades from.
 * @property name the document's display name, as the library shows it.
 * @property sourceUri where the file lives; equal to [id] today, kept separately so identity and location
 * can diverge if the app ever re-keys documents.
 * @property format the document format's name, stored as text so an unknown value from a newer build
 * degrades instead of failing to read.
 * @property mimeType what the platform reported, or NULL when the picker supplied nothing.
 * @property sizeBytes the file's size as reported, or NULL when unknown.
 * @property addedAtEpochMillis when the document was imported.
 * @property lastOpenedAtEpochMillis when it was last opened, or NULL while it never has been — which is
 * why the library's ordering coalesces this with [addedAtEpochMillis].
 * @property pageCount pages as last measured, or NULL when nothing has measured it.
 * @property characterCount characters of text, or NULL while the import has not finished.
 * @property wordCount words of text, or NULL for the same reason.
 * @property isBookmarked whether the book is starred in the library.
 * @property folderId the folder this book is filed under, or NULL when unfiled.
 * @property folderName that folder's name, present exactly when [folderId] is.
 * @throws IllegalArgumentException if the folder pair is half-filled or either half is blank.
 */
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
    /**
     * When the import that produced this row finished, or NULL while it never did — which is what makes a
     * half-imported book recognisable after the app is killed mid-import.
     *
     * Added ahead of the code that reads it (TeddReaderMigration7To8) so shipping progressive import needed
     * no second schema bump. Rows that already existed were backfilled to their `addedAtEpochMillis`,
     * because a row that already existed was, by definition, imported completely.
     */
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
