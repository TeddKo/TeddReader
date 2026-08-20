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
    // The document's character count at measurement time. Re-parsing a document can move every offset
    // in it, so a stored row is only safe to trust while the document's length has not changed since it
    // was written; this is the cheap fingerprint that lets a caller refuse a stale row instead of
    // trusting it blindly.
    val characterCount: Long,
    // Legacy storage for the same offsets [pageStartsBlob] now carries. Decoding this JSON array of
    // longs was most of a large book's restore cost — on a 16,734-page book, ~110 KB of digits parsed
    // on every open. New rows no longer encode a real array here (see storePageWindows); this column
    // stays only because it is `NOT NULL` and dropping it would mean rewriting the whole table.
    val pageStartsJson: String = "[]",
    // One absolute document offset per content page, ascending, excluding the cover page (which is
    // always exactly the first section and never needs measuring to rebuild) — the same contract
    // [pageStartsJson] used to carry, now as a little-endian Int32 per offset instead of JSON digits.
    // Offsets fit comfortably inside `Int` (the largest real book this reader opens is 3.5M characters),
    // so a 16,734-page book is ~67 KB instead of ~110 KB, and decoding is a loop over bytes instead of a
    // JSON parse. Nullable because the column was added by a migration that could not backfill it for
    // existing rows — see TeddReaderMigration7To8, which deletes those rows instead.
    val pageStartsBlob: ByteArray? = null,
    val writtenAtEpochMillis: Long,
)
