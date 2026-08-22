package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * The measured page starts for one document at one exact (font size, line height, font family,
 * viewport) combination — the only inputs that decide where the pagination engine breaks pages.
 *
 * [fontFamilyName] stores `""` for a `ReaderStyle.fontFamilyName` of `null` (the system default font).
 * A `NULL` column cannot carry that meaning here: this table's primary key is exactly the columns below,
 * and SQLite treats every `NULL` in a `PRIMARY KEY`/`UNIQUE` index as distinct from every other `NULL` —
 * so two rows measured for the same "no explicit family" style would never conflict, and upserting the
 * same layout twice would insert a second row instead of replacing the first. Empty string is not a
 * legal font family name, so it cannot collide with a real one.
 *
 * @property documentId the book these page starts were measured for.
 * @property fontSizeSp the type size they were measured at.
 * @property lineHeightMultiplier the line height they were measured at.
 * @property fontFamilyName the family they were measured with; `""` stands for the system default.
 * @property viewportWidthPx the width of the box they were measured into.
 * @property viewportHeightPx the height of that box.
 */
@Entity(
    tableName = "page_layouts",
    primaryKeys = [
        "documentId",
        "fontSizeSp",
        "lineHeightMultiplier",
        "fontFamilyName",
        "viewportWidthPx",
        "viewportHeightPx",
    ],
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
data class PageLayoutEntity(
    val documentId: String,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val fontFamilyName: String,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    /**
     * The document's character count when this measurement was made — the fingerprint that decides whether
     * the row can still be trusted.
     *
     * Re-parsing a document can move every offset in it, so page starts measured against an older text
     * would put the reader on the wrong passage. Comparing one number is cheap enough to do on every open,
     * and a mismatch means the row is discarded rather than trusted.
     */
    val characterCount: Long,
    /**
     * Legacy storage for the same offsets [pageStartsBlob] now carries, kept only because it is `NOT NULL`
     * and dropping a column means rewriting the whole table.
     *
     * Decoding this JSON array of longs was most of a large book's restore cost: ~110 KB of digits parsed on
     * every open of a 16,734-page book. New rows no longer encode a real array here.
     */
    val pageStartsJson: String = "[]",
    /**
     * Where every content page starts, as one absolute document offset per page, ascending, excluding the
     * cover page — the cover is always exactly the first section and needs no measurement to rebuild.
     *
     * A little-endian Int32 per offset rather than JSON digits: offsets fit comfortably inside `Int` (the
     * largest real book this reader opens is 3.5M characters), so a 16,734-page book is ~67 KB instead of
     * ~110 KB, and decoding is a loop over bytes instead of a JSON parse.
     *
     * Nullable because the migration that added the column could not backfill it — TeddReaderMigration7To8
     * deletes those rows instead, which costs one re-measurement and no data.
     */
    val pageStartsBlob: ByteArray? = null,
    /** When this row was measured, which is what orders newest-first resolution and bounds the table. */
    val writtenAtEpochMillis: Long,
)
