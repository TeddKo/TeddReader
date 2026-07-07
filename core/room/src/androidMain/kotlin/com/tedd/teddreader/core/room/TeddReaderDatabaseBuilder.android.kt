package com.tedd.teddreader.core.room

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

fun createTeddReaderDatabaseBuilder(
    context: Context,
): RoomDatabase.Builder<TeddReaderDatabase> =
    Room.databaseBuilder<TeddReaderDatabase>(
        context = context.applicationContext,
        name = TeddReaderDatabaseName,
    ).setDriver(BundledSQLiteDriver())
