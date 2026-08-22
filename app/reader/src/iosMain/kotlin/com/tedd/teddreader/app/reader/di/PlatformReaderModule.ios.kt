package com.tedd.teddreader.app.reader.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.data.storage.IosDocumentFileSource
import com.tedd.teddreader.core.datastore.ReaderPreferences
import com.tedd.teddreader.core.datastore.createReaderPreferencesDataStore
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.core.room.createTeddReaderDatabaseBuilder
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS's half of the composition root's Koin graph. Unlike the Android `actual`, none of these
 * bindings need a `Context`-equivalent handed in: [IosDocumentFileSource] reaches the sandbox
 * directly, and `createTeddReaderDatabaseBuilder`/`createReaderPreferencesDataStore` resolve the
 * app's own container paths themselves, so this module can be built with no composition-scoped
 * input at all.
 *
 * `remember`ed with no keys so it is built exactly once for as long as this composable stays in
 * the composition, keeping the Room database and DataStore instances inside it as the process-wide
 * singletons they are meant to be rather than reconstructing them on every recomposition.
 *
 * @return a [Module] providing every iOS-specific binding
 *   [com.tedd.teddreader.app.reader.di.readerAppModule]'s repositories depend on.
 */
@Composable
internal actual fun rememberPlatformReaderModule(): Module = remember {
    module {
        single { IosDocumentFileSource() }
        single<DocumentFileSource> { get<IosDocumentFileSource>() }
        single<TeddReaderDatabase> { createTeddReaderDatabaseBuilder().build() }
        single<DataStore<ReaderPreferences>> { createReaderPreferencesDataStore() }
    }
}
