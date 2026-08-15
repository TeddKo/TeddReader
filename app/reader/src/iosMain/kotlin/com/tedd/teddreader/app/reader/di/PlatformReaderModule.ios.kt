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

@Composable
internal actual fun rememberPlatformReaderModule(): Module = remember {
    module {
        single { IosDocumentFileSource() }
        single<DocumentFileSource> { get<IosDocumentFileSource>() }
        single<TeddReaderDatabase> { createTeddReaderDatabaseBuilder().build() }
        single<DataStore<ReaderPreferences>> { createReaderPreferencesDataStore() }
    }
}
