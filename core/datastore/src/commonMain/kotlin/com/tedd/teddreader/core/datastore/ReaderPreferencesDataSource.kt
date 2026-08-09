package com.tedd.teddreader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.Path

class ReaderPreferencesDataSource(
    private val dataStore: DataStore<ReaderPreferences>,
) {
    val preferences: Flow<ReaderPreferences> = dataStore.data

    suspend fun updateStyle(style: ReaderStyle) {
        dataStore.updateData { it.copy(style = style) }
    }

    suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        dataStore.updateData { it.copy(pageTurnMode = pageTurnMode) }
    }

    suspend fun updatePageAnimation(pageAnimation: PageAnimation) {
        dataStore.updateData { it.copy(pageAnimation = pageAnimation) }
    }

    suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        dataStore.updateData { it.copy(autoScrollConfig = autoScrollConfig) }
    }

    suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        dataStore.updateData { it.copy(appLanguage = appLanguage) }
    }
}

fun createReaderPreferencesDataStore(
    fileSystem: FileSystem,
    producePath: () -> Path,
): DataStore<ReaderPreferences> = DataStoreFactory.create(
    storage = OkioStorage(
        fileSystem = fileSystem,
        serializer = ReaderPreferencesSerializer,
        producePath = producePath,
    ),
)
