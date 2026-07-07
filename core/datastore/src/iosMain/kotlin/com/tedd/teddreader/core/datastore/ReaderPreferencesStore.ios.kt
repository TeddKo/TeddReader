package com.tedd.teddreader.core.datastore

import androidx.datastore.core.DataStore
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

fun createReaderPreferencesDataStore(): DataStore<ReaderPreferences> =
    createReaderPreferencesDataStore {
        "${NSHomeDirectory()}/Documents/$ReaderPreferencesFileName".toPath()
    }
