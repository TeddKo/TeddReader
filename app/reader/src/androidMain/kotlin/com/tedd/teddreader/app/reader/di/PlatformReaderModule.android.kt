package com.tedd.teddreader.app.reader.di

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import com.tedd.teddreader.core.data.storage.AndroidDocumentFileSource
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.datastore.ReaderPreferences
import com.tedd.teddreader.core.datastore.createReaderPreferencesDataStore
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.core.room.createTeddReaderDatabaseBuilder
import org.koin.core.module.Module
import org.koin.dsl.module

@Composable
internal actual fun rememberPlatformReaderModule(): Module {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        module {
            single<Context> { context }
            single { AndroidDocumentFileSource(get<Context>()) }
            single<DocumentFileSource> { get<AndroidDocumentFileSource>() }
            single<TeddReaderDatabase> { createTeddReaderDatabaseBuilder(get<Context>()).build() }
            single<DataStore<ReaderPreferences>> { createReaderPreferencesDataStore(get<Context>()) }
        }
    }
}
