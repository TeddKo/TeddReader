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

/**
 * The app's read/write access to stored preferences, one call per preference.
 *
 * Each write is `updateData` over the whole file rather than a keyed put: DataStore serialises those
 * updates, so two screens changing two different preferences at the same time cannot write a stale copy of
 * the whole object over each other.
 *
 * @property preferences the stored preferences and every later change, starting from what is on disk now.
 */
class ReaderPreferencesDataSource(
    private val dataStore: DataStore<ReaderPreferences>,
) {
    val preferences: Flow<ReaderPreferences> = dataStore.data

    /** @param style the reading style to store. */
    suspend fun updateStyle(style: ReaderStyle) {
        dataStore.updateData { it.copy(style = style) }
    }

    /** @param pageTurnMode the page-turn direction to store. */
    suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        dataStore.updateData { it.copy(pageTurnMode = pageTurnMode) }
    }

    /** @param pageAnimation the page-turn animation to store. */
    suspend fun updatePageAnimation(pageAnimation: PageAnimation) {
        dataStore.updateData { it.copy(pageAnimation = pageAnimation) }
    }

    /** @param autoScrollConfig the auto-scroll configuration to store; its speed is clamped on write. */
    suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        dataStore.updateData { it.copy(autoScrollConfig = autoScrollConfig) }
    }

    /** @param appLanguage the app language to store. */
    suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        dataStore.updateData { it.copy(appLanguage = appLanguage) }
    }
}

/**
 * Builds the preferences store the platform builders wrap, with the path supplied by the caller.
 *
 * Taking both the file system and the path as parameters is what lets a test point the store at a temporary
 * directory, and what keeps this function common to both platforms.
 *
 * @param fileSystem the file system to read and write through.
 * @param producePath where the preferences file lives, resolved lazily so a platform can consult its own
 * directories.
 * @return a store backed by [ReaderPreferencesSerializer].
 */
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
