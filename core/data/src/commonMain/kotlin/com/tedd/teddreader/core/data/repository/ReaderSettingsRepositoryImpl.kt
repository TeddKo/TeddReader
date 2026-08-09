package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.datastore.ReaderPreferences
import com.tedd.teddreader.core.datastore.ReaderPreferencesDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [ReaderSettingsRepository::class])
class ReaderSettingsRepositoryImpl(
    private val dataSource: ReaderPreferencesDataSource,
) : ReaderSettingsRepository {
    override val settings: Flow<ReaderSettings> = dataSource.preferences.map { it.toReaderSettings() }

    override suspend fun updateStyle(style: ReaderStyle) {
        dataSource.updateStyle(style)
    }

    override suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        dataSource.updatePageTurnMode(pageTurnMode)
    }

    override suspend fun updatePageAnimation(pageAnimation: PageAnimation) {
        dataSource.updatePageAnimation(pageAnimation)
    }

    override suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        dataSource.updateAutoScrollConfig(autoScrollConfig)
    }

    override suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        dataSource.updateAppLanguage(appLanguage)
    }
}

private fun ReaderPreferences.toReaderSettings(): ReaderSettings = ReaderSettings(
    style = style,
    pageTurnMode = pageTurnMode,
    pageAnimation = pageAnimation,
    autoScrollConfig = autoScrollConfig,
    appLanguage = appLanguage,
)
