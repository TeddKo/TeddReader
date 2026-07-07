package com.tedd.teddreader.core.room

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import platform.Foundation.NSHomeDirectory

fun createTeddReaderDatabaseBuilder(): RoomDatabase.Builder<TeddReaderDatabase> =
    Room.databaseBuilder<TeddReaderDatabase>(
        name = "${NSHomeDirectory()}/Documents/$TeddReaderDatabaseName",
    ).setDriver(BundledSQLiteDriver())
