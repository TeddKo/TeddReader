package com.tedd.teddreader.core.datastore

import androidx.datastore.core.DataStore
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/**
 * Opens the preferences store on iOS, under `Documents/` — the container directory iOS backs up and does not
 * reclaim, so a reader's settings survive a restore rather than being treated as a cache.
 *
 * @return the single store the app reads and writes preferences through.
 */
fun createReaderPreferencesDataStore(): DataStore<ReaderPreferences> =
    createReaderPreferencesDataStore(
        fileSystem = FileSystem.SYSTEM,
    ) {
        "${NSHomeDirectory()}/Documents/$ReaderPreferencesFileName".toPath()
    }
