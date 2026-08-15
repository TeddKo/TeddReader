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
