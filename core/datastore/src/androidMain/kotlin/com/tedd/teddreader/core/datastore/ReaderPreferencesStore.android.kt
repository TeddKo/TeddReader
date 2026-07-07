package com.tedd.teddreader.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import okio.Path.Companion.toPath

fun createReaderPreferencesDataStore(
    context: Context,
): DataStore<ReaderPreferences> = createReaderPreferencesDataStore {
    context.filesDir.resolve(ReaderPreferencesFileName).absolutePath.toPath()
}
