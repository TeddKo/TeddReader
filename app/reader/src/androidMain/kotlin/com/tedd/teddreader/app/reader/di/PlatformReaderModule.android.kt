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

/**
 * Android's half of the composition root's Koin graph, built from the application [Context] the
 * Compose tree runs in: exposes that `Context` itself for anything else in the graph that needs
 * it, the SAF-backed [AndroidDocumentFileSource] (both as its concrete type — the Android importer
 * calls its Android-specific methods directly — and bound to the shared [DocumentFileSource]
 * interface that [com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl] depends on),
 * the Room database, and the reader-preferences DataStore.
 *
 * `remember`ed on the application context rather than rebuilt every recomposition, so the database
 * and DataStore — both expensive to open and meant to be process-wide singletons — are constructed
 * exactly once for as long as this composable stays in the composition; keying on `context` rather
 * than `Unit` still rebuilds correctly in the rare case the application context itself changes.
 *
 * @return a [Module] providing every Android-specific binding
 *   [com.tedd.teddreader.app.reader.di.readerAppModule]'s repositories depend on.
 */
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
