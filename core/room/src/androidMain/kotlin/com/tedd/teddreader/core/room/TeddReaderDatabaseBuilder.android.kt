package com.tedd.teddreader.core.room

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * The Android database builder, opened on the application context so it outlives any activity.
 *
 * The bundled SQLite driver is deliberate: it ships one SQLite build with the app, so a query that works
 * on a modern device works on an old one, and the parameter and expression limits are the bundled
 * library's rather than the platform's. Migrations come from [TeddReaderMigrationList] instead of being
 * listed here, so the two platforms cannot drift apart.
 */
fun createTeddReaderDatabaseBuilder(
    context: Context,
): RoomDatabase.Builder<TeddReaderDatabase> =
    Room.databaseBuilder<TeddReaderDatabase>(
        context = context.applicationContext,
        name = TeddReaderDatabaseName,
    )
        .addMigrations(*TeddReaderMigrationList.toTypedArray())
        .setDriver(BundledSQLiteDriver())
        .withWalSizeLimit()
