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

/**
 * Reading preferences, backed by the preferences file.
 *
 * The domain's [ReaderSettings] and the stored `ReaderPreferences` are separate types with the same fields,
 * so this class exists to convert between them — which is what lets the storage format change (a renamed
 * key, a dropped field) without touching the domain, and vice versa. Legacy values and out-of-range numbers
 * are already normalised by the serializer before they reach here.
 *
 * @property dataSource the preferences store this reads and writes.
 */
@Single(binds = [ReaderSettingsRepository::class])
class ReaderSettingsRepositoryImpl(
    private val dataSource: ReaderPreferencesDataSource,
) : ReaderSettingsRepository {
    /** The current settings, re-mapped from [dataSource] every time the stored preferences change. */
    override val settings: Flow<ReaderSettings> = dataSource.preferences.map { it.toReaderSettings() }

    /** Persists [style] as the reader's current type and colours. */
    override suspend fun updateStyle(style: ReaderStyle) {
        dataSource.updateStyle(style)
    }

    /** Persists [pageTurnMode] as how a page turn is read — horizontal, vertical, or continuous. */
    override suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        dataSource.updatePageTurnMode(pageTurnMode)
    }

    /** Persists [pageAnimation] as how a page turn is animated. */
    override suspend fun updatePageAnimation(pageAnimation: PageAnimation) {
        dataSource.updatePageAnimation(pageAnimation)
    }

    /**
     * Stores auto-scroll, clamping the speed on the way in.
     *
     * The clamp is a defence, not the only one: every caller is expected to clamp a slider value before
     * constructing the config, and the serializer clamps again on read (pinned by
     * `ReaderPreferencesSerializerTest.outOfRangeAutoScrollSpeedReadBackWithinSupportedRange`). Putting it on
     * the write path as well means a caller that forgets cannot persist a speed the reader can never undo from
     * the slider.
     *
     * Not unit-tested at this layer on purpose: `ReaderPreferencesDataSource` is final and DataStore is not on
     * this module's test classpath, so covering one clamped assignment would mean adding a build dependency to
     * restate what the serializer's own test already guarantees about what a screen can ever read back.
     *
     * @param autoScrollConfig the configuration to store; its speed is clamped into the supported range.
     */
    override suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        dataSource.updateAutoScrollConfig(
            autoScrollConfig.copy(speed = AutoScrollConfig.clampSpeed(autoScrollConfig.speed)),
        )
    }

    /** Persists [appLanguage] as the app's own interface language, independent of any book's language. */
    override suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        dataSource.updateAppLanguage(appLanguage)
    }
}

/**
 * @receiver preferences as stored on disk.
 * @return the same values as the domain's own type. A field-by-field copy rather than a shared type, so the
 * two can diverge without a migration.
 */
private fun ReaderPreferences.toReaderSettings(): ReaderSettings = ReaderSettings(
    style = style,
    pageTurnMode = pageTurnMode,
    pageAnimation = pageAnimation,
    autoScrollConfig = autoScrollConfig,
    appLanguage = appLanguage,
)
