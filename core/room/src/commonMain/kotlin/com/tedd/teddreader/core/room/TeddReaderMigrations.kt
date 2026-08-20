package com.tedd.teddreader.core.room

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

internal val TeddReaderMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN isBookmarked INTEGER NOT NULL DEFAULT 0",
    )
}

internal val TeddReaderMigration2To3 = Migration(2, 3) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN folderId TEXT",
    )
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN folderName TEXT",
    )
}

internal val TeddReaderMigration3To4 = Migration(3, 4) { connection ->
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN blocksJson TEXT NOT NULL DEFAULT '[]'",
    )
}

internal val TeddReaderMigration4To5 = Migration(4, 5) { connection ->
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN documentTitle TEXT",
    )
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN navigationJson TEXT NOT NULL DEFAULT ''",
    )
}

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

// Both platform builders (android/ios) register migrations from this one list instead of each typing
// out its own — Room's `RoomDatabase.Builder` keeps no way to read migrations back out once added, so
// a hand-duplicated list on each platform could silently drift with no way to catch it later.
internal val TeddReaderMigrationList: List<Migration> = listOf(
    TeddReaderMigration1To2,
    TeddReaderMigration2To3,
    TeddReaderMigration3To4,
    TeddReaderMigration4To5,
    TeddReaderMigration5To6,
    TeddReaderMigration6To7,
    TeddReaderMigration7To8,
)
