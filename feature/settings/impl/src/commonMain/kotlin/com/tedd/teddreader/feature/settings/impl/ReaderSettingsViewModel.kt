package com.tedd.teddreader.feature.settings.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.AppLanguage
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

    fun updateAppLanguage(appLanguage: AppLanguage) {
        viewModelScope.launch {
            readerSettingsRepository.updateAppLanguage(appLanguage)
        }
    }
}
