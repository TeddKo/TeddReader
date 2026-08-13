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
