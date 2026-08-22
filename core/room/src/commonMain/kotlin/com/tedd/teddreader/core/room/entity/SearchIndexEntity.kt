package com.tedd.teddreader.core.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * One section of a document's stored text, and the row both the reader and search read.
 *
 * Keyed by document and section index, so a progressive import can append sections as it parses them and
 * the reader can ask for exactly the ones it is about to draw. `startOffset`/`endOffset` place the section
 * in the whole document's flat text, which is what makes a search hit, a bookmark and a reading position
 * comparable across sections.
 *
 * The table name is historical: it began as a search index and became the document store. Everything about
 * a section lives here — its text, its block structure, and (on one row) the book's title and navigation —
 * so opening a book is one query.
 *
 * @property documentId the document this section belongs to; half of the primary key.
 * @property sectionIndex the section's position in document order; the other half of the key.
 * @property sectionTitle the section's heading, or NULL when it has none — later replaced from the book's
 * navigation on the import's last batch.
 * @property text the section's text, line-ending normalised at parse time.
 * @property startOffset where that text begins in the whole document.
 * @property endOffset one past where it ends, which is what a progressive import resumes from.
 * @property blocksJson the section's block structure, the column that dwarfs all the others on a large
 * book — read only through `getSectionBlocksJson`.
 * @property documentTitle the book's own title, written on one section's row rather than in a table of
 * its own so an open reads it with the text.
 * @property navigationJson the book's table of contents, stored the same way and for the same reason.
 */
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
    /**
     * Which build of the parser wrote this row, so the reader can tell stored text that predates a parser
     * change and re-parse it.
     *
     * A number, because the alternative was inspecting the blocks themselves for traces of the older code —
     * which on a book whose first illustration sits in chapter 292 meant decoding 293 chapters on every open
     * just to ask the question.
     */
    @ColumnInfo(defaultValue = "0")
    val parserVersion: Int = 0,
)
