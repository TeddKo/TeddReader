package com.tedd.teddreader.core.room

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Caps how large the write-ahead log is allowed to stay.
 *
 * Importing a book writes every section in one transaction, and the log grows to hold all of it —
 * on a 528-chapter book that was 19.9 MB. Committing hands those pages back to the database but
 * leaves the file at its high-water mark, so the space is never returned. SQLite trims the log to
 * this limit at each checkpoint instead, which needs nothing scheduled on the app's side.
 *
 * The journal mode itself stays WAL. Switching to a rollback journal would cap the file too, but it
 * makes every write fsync twice and leaves a single connection for readers and the writer to share —
 * and this reader writes a progress row on every page turn.
 */
internal fun RoomDatabase.Builder<TeddReaderDatabase>.withWalSizeLimit(): RoomDatabase.Builder<TeddReaderDatabase> =
    addCallback(
        object : RoomDatabase.Callback() {
            override suspend fun onOpen(connection: SQLiteConnection) {
                connection.execSQL("PRAGMA journal_size_limit = $WalSizeLimitBytes")
            }
        },
    )

private const val WalSizeLimitBytes = 4 * 1024 * 1024
