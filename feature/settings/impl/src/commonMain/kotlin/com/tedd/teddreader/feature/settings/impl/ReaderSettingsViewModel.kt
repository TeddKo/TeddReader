package com.tedd.teddreader.feature.settings.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class ReaderSettingsViewModel(
    private val readerSettingsRepository: ReaderSettingsRepository,
) : ViewModel() {
    /** Structured logger, tagged `"ReaderSettings"`, for preference writes that failed; see [persistSetting]. */
    private val logger = Logger.withTag("ReaderSettings")

    val uiState: StateFlow<ReaderSettingsUiState> = readerSettingsRepository.settings
        .map { settings ->
            ReaderSettingsUiState(
                style = settings.style,
                pageTurnMode = settings.pageTurnMode,
                pageAnimation = settings.pageAnimation,
                autoScrollConfig = settings.autoScrollConfig,
                appLanguage = settings.appLanguage,
                isLoading = false,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReaderSettingsUiState(),
        )

    /**
     * Writes one preference through [readerSettingsRepository], guarded so a storage failure cannot
     * crash the process.
     *
     * Every setter below is fire-and-forget from the UI's point of view, and an unguarded
     * `viewModelScope.launch` let a DataStore write failure — a corrupt preferences file, a full disk —
     * escape uncaught and terminate the app while the user was only changing a font size. The failure
     * is logged rather than published to [ReaderSettingsUiState], because that state is derived from
     * the repository's own settings flow: a write that failed produces no emission, so the screen keeps
     * showing the value that is actually stored instead of a value it only optimistically applied.
     *
     * @param description What was being saved, used as the log message when the write fails.
     * @param block The repository write to attempt.
     */
    private fun persistSetting(description: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            suspendRunCatching { block() }
                .onFailure { throwable -> logger.w(throwable) { "Failed to save $description" } }
        }
    }

    fun updateStyle(style: ReaderStyle) {
        persistSetting("reader style") { readerSettingsRepository.updateStyle(style) }
    }

    fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        persistSetting("page turn mode") { readerSettingsRepository.updatePageTurnMode(pageTurnMode) }
    }

    fun updatePageAnimation(pageAnimation: PageAnimation) {
        persistSetting("page animation") { readerSettingsRepository.updatePageAnimation(pageAnimation) }
    }

    fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        persistSetting("auto-scroll config") {
            readerSettingsRepository.updateAutoScrollConfig(
                autoScrollConfig.copy(speed = AutoScrollConfig.clampSpeed(autoScrollConfig.speed)),
            )
        }
    }

    fun updateAppLanguage(appLanguage: AppLanguage) {
        persistSetting("app language") { readerSettingsRepository.updateAppLanguage(appLanguage) }
    }
}
