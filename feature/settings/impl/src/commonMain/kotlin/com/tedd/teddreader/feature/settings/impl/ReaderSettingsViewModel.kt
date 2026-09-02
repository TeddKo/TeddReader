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

/**
 * [ReaderSettingsRepository]에 저장된 읽기 환경설정을 [uiState]로 노출하고, 화면에서 들어오는
 * 각 변경을 [persistSetting]을 통해 저장소에 기록한다. [uiState]는 저장소의 설정 흐름을 그대로
 * 매핑한 값이므로 실제로 저장에 성공한 값만 표시하며, 아래 update 함수들은 그 결과를 기다리지
 * 않는 fire-and-forget 호출이다.
 *
 * @property readerSettingsRepository 읽기 환경설정이 저장되고 관찰되는 저장소.
 */
@KoinViewModel
class ReaderSettingsViewModel(
    private val readerSettingsRepository: ReaderSettingsRepository,
) : ViewModel() {
    /** 실패한 환경설정 쓰기를 기록하는 `"ReaderSettings"` 태그의 구조화된 로거이며, 자세한 내용은 [persistSetting]을 참고한다. */
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
     * 저장소 오류가 프로세스를 중단시키지 않도록 보호하면서 [readerSettingsRepository]를 통해
     * 환경설정 하나를 기록한다.
     *
     * 아래의 모든 설정 함수는 UI 관점에서 실행 후 결과를 기다리지 않는다. 보호하지 않은
     * `viewModelScope.launch`를 사용하면 손상된 환경설정 파일이나 가득 찬 디스크로 인한 DataStore
     * 쓰기 오류가 잡히지 않은 채 전파되어, 사용자가 글꼴 크기만 변경했는데도 앱이 종료될 수 있다.
     * 이 오류는 [ReaderSettingsUiState]에 게시하지 않고 로그로 남긴다. 해당 상태는 저장소 자체의
     * 설정 흐름에서 파생되며, 실패한 쓰기는 값을 방출하지 않으므로 화면에는 낙관적으로만 적용한
     * 값이 아니라 실제 저장된 값이 계속 표시된다.
     *
     * @param description 저장하려던 항목으로, 쓰기 실패 시 로그 메시지에 사용한다.
     * @param block 시도할 저장소 쓰기 작업.
     */
    private fun persistSetting(description: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            suspendRunCatching { block() }
                .onFailure { throwable -> logger.w(throwable) { "Failed to save $description" } }
        }
    }

    /**
     * 리더 스타일을 [style]로 저장한다. 쓰기는 [persistSetting]이 보호하므로 실패해도 조용히
     * 로그만 남고 [uiState]에는 실제로 저장된 값만 계속 반영된다.
     *
     * @param style 저장할 새 리더 스타일.
     */
    fun updateStyle(style: ReaderStyle) {
        persistSetting("reader style") { readerSettingsRepository.updateStyle(style) }
    }

    /**
     * 페이지 넘김 방식을 [pageTurnMode]로 저장한다. 나머지 쓰기 보호 동작은 [persistSetting]과
     * 같다.
     *
     * @param pageTurnMode 저장할 새 페이지 넘김 방식.
     */
    fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        persistSetting("page turn mode") { readerSettingsRepository.updatePageTurnMode(pageTurnMode) }
    }

    /**
     * 페이지 전환 애니메이션을 [pageAnimation]으로 저장한다. 나머지 쓰기 보호 동작은
     * [persistSetting]과 같다.
     *
     * @param pageAnimation 저장할 새 페이지 전환 애니메이션.
     */
    fun updatePageAnimation(pageAnimation: PageAnimation) {
        persistSetting("page animation") { readerSettingsRepository.updatePageAnimation(pageAnimation) }
    }

    /**
     * 자동 스크롤 설정을 [autoScrollConfig]로 저장하되, 속도는 저장 전에
     * [AutoScrollConfig.clampSpeed]로 유효 범위에 맞춰 보정한다. 호출자가 범위를 벗어난 속도를
     * 넘기더라도 저장소에는 항상 유효한 값만 기록되도록 하기 위해서이며, 나머지 쓰기 보호 동작은
     * [persistSetting]과 같다.
     *
     * @param autoScrollConfig 저장할 자동 스크롤 설정. [AutoScrollConfig.speed]는 클램프된 뒤
     * 저장된다.
     */
    fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        persistSetting("auto-scroll config") {
            readerSettingsRepository.updateAutoScrollConfig(
                autoScrollConfig.copy(speed = AutoScrollConfig.clampSpeed(autoScrollConfig.speed)),
            )
        }
    }

    /**
     * 앱 언어를 [appLanguage]로 저장한다. 나머지 쓰기 보호 동작은 [persistSetting]과 같다.
     *
     * @param appLanguage 저장할 새 앱 언어.
     */
    fun updateAppLanguage(appLanguage: AppLanguage) {
        persistSetting("app language") { readerSettingsRepository.updateAppLanguage(appLanguage) }
    }
}
