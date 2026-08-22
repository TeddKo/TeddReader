package com.tedd.teddreader.core.room

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import platform.Foundation.NSHomeDirectory

/**
 * The iOS database builder. The file goes under `Documents/`, which is the container directory iOS backs
 * up and does not reclaim — a reading library must survive a device restore, unlike a cache.
 *
 * Same bundled driver and same [TeddReaderMigrationList] as Android, for the same reasons.
 */
fun createTeddReaderDatabaseBuilder(): RoomDatabase.Builder<TeddReaderDatabase> =
    Room.databaseBuilder<TeddReaderDatabase>(
        name = "${NSHomeDirectory()}/Documents/$TeddReaderDatabaseName",
    )
        .addMigrations(*TeddReaderMigrationList.toTypedArray())
        .setDriver(BundledSQLiteDriver())
        .withWalSizeLimit()
