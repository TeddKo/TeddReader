package com.tedd.teddreader.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Opens the preferences store on Android, under the app's private files directory.
 *
 * @param context any context; the application context is what the path is resolved from, so the store
 * outlives whatever activity created it.
 * @return the single store the app reads and writes preferences through.
 */
fun createReaderPreferencesDataStore(
    context: Context,
): DataStore<ReaderPreferences> = createReaderPreferencesDataStore(
    fileSystem = FileSystem.SYSTEM,
) {
    context.filesDir.resolve(ReaderPreferencesFileName).absolutePath.toPath()
}
