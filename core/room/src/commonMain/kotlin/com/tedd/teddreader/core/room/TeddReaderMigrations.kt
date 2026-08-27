package com.tedd.teddreader.core.room

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

/** Adds the library's favourite flag, which had no column before books could be starred. */
internal val TeddReaderMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN isBookmarked INTEGER NOT NULL DEFAULT 0",
    )
}

/**
 * Adds folder membership to the library. Both columns are nullable and are only ever written together —
 * a row in a folder knows the folder's name, a row outside one has neither.
 */
internal val TeddReaderMigration2To3 = Migration(2, 3) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN folderId TEXT",
    )
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN folderName TEXT",
    )
}

/**
 * Adds the per-section block structure that styles stored text. Defaulted to an empty array so books
 * imported by an earlier build stay readable as plain text until something re-parses them.
 */
internal val TeddReaderMigration3To4 = Migration(3, 4) { connection ->
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN blocksJson TEXT NOT NULL DEFAULT '[]'",
    )
}

/**
 * Adds the document title and table of contents to the search index, which is where a reader reads them
 * back from: they belong to the book as a whole, but the section rows are what an open already loads.
 */
internal val TeddReaderMigration4To5 = Migration(4, 5) { connection ->
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN documentTitle TEXT",
    )
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN navigationJson TEXT NOT NULL DEFAULT ''",
    )
}

/**
 * Creates the page-layout cache. Keyed by document, type and viewport together, because that whole tuple
 * is what a measurement is valid for; `ON DELETE CASCADE` ties the rows to their document so deleting a
 * book cannot leave its measurements behind.
 */
internal val TeddReaderMigration5To6 = Migration(5, 6) { connection ->
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `page_layouts` (`documentId` TEXT NOT NULL, `fontSizeSp` REAL NOT NULL, " +
            "`lineHeightMultiplier` REAL NOT NULL, `fontFamilyName` TEXT NOT NULL, `viewportWidthPx` INTEGER NOT NULL, " +
            "`viewportHeightPx` INTEGER NOT NULL, `characterCount` INTEGER NOT NULL, `pageStartsJson` TEXT NOT NULL, " +
            "`writtenAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`documentId`, `fontSizeSp`, `lineHeightMultiplier`, " +
            "`fontFamilyName`, `viewportWidthPx`, `viewportHeightPx`), FOREIGN KEY(`documentId`) REFERENCES " +
            "`documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_page_layouts_documentId` ON `page_layouts` (`documentId`)",
    )
}

/**
 * Adds the parser version stored text was written by. Comparing a number is how a later build knows that
 * stored text predates a parser change; the alternative was inspecting the blocks themselves, which on a
 * book whose first illustration sits in chapter 292 meant decoding 293 chapters just to ask.
 */
internal val TeddReaderMigration6To7 = Migration(6, 7) { connection ->
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN parserVersion INTEGER NOT NULL DEFAULT 0",
    )
}

/**
 * Two unrelated columns share one bump so the rest of the plan this migration was written for needs
 * only one v7->v8, not two.
 *
 * `page_layouts.pageStartsBlob` is the new little-endian Int32 encoding of the same page starts
 * `pageStartsJson` used to carry as a JSON array of longs — decoding that JSON was most of a large
 * book's restore cost. A row written before this migration has no blob to read, and a page layout is
 * only ever a cache: pagination is deterministic on identical input, so the next open re-measures the
 * book once and reproduces the exact same page boundaries. `DELETE FROM page_layouts` is therefore
 * safe — nothing is lost, only recomputed once per book.
 *
 * `documents.importCompletedAtEpochMillis` is unused until a later change ships progressive EPUB
 * import; existing rows are backfilled as complete because they already are.
 */
internal val TeddReaderMigration7To8 = Migration(7, 8) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN importCompletedAtEpochMillis INTEGER",
    )
    connection.execSQL(
        "UPDATE documents SET importCompletedAtEpochMillis = addedAtEpochMillis",
    )
    connection.execSQL(
        "ALTER TABLE page_layouts ADD COLUMN pageStartsBlob BLOB",
    )
    connection.execSQL(
        "DELETE FROM page_layouts",
    )
}

/**
 * Three columns that support the page-count finalization optimisation: an indexed font-href cache on
 * `documents`, a source-path column on `search_index` that lets `finishEpubImport` skip a full-text
 * scan, and a partial-layout flag on `page_layouts` that distinguishes a measurement made against an
 * incomplete import prefix from a complete one.
 *
 * All three are nullable/defaulted so existing rows stay valid without a data backfill: the font index
 * and source path are populated lazily or on the next import batch, and the `isPartial` column defaults
 * to `0` (false) which is correct for every row that existed before progressive layout caching was
 * introduced.
 */
internal val TeddReaderMigration8To9 = Migration(8, 9) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN embeddedFontHrefsJson TEXT",
    )
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN sourcePath TEXT",
    )
    connection.execSQL(
        "ALTER TABLE page_layouts ADD COLUMN isPartial INTEGER NOT NULL DEFAULT 0",
    )
}

/**
 * Every migration, in order, and the single source both platform builders register from.
 *
 * One list rather than a hand-written call per platform: Room's `RoomDatabase.Builder` keeps no way to
 * read migrations back out once they are added, so two duplicated lists could drift apart with nothing
 * able to catch it. TeddReaderMigrationListTest walks this list instead and fails if the chain skips a
 * version or stops short of the database's own.
 */
internal val TeddReaderMigrationList: List<Migration> = listOf(
    TeddReaderMigration1To2,
    TeddReaderMigration2To3,
    TeddReaderMigration3To4,
    TeddReaderMigration4To5,
    TeddReaderMigration5To6,
    TeddReaderMigration6To7,
    TeddReaderMigration7To8,
    TeddReaderMigration8To9,
)
