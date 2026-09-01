package com.tedd.teddreader.feature.settings.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle

/**
 * [ReaderSettingsViewModel]이 게시하고 [ReaderSettingsRouteScreen]이 렌더링하는 리더 설정 화면의
 * 스냅샷이다. 아래의 모든 필드는 `ReaderSettings` 자체의 기본값과 같은 값으로 초기화된다.
 * 따라서 저장소가 첫 값을 방출하기 전에 화면이 표시되어도 실제 환경설정을 불러온 뒤 눈에 띄게
 * 바뀌는 임시 값 대신 실제 기본값을 렌더링한다.
 *
 * @property style 리더가 활자와 페이지 색상을 그릴 때 사용할 스타일.
 * @property pageTurnMode 페이지를 넘길 방향.
 * @property pageAnimation 페이지 넘김을 애니메이션으로 표현할 페이저 구현.
 * @property autoScrollConfig 자동 스크롤 활성화 여부, 이동 단위, 속도.
 * @property appLanguage 책과 무관하게 앱 자체 문자열을 표시할 언어.
 * @property isLoading 저장소의 설정 흐름이 한 번 이상 값을 방출할 때까지는 true다. 이후에는
 * 저장된 환경설정 자체를 다시 불러오는 상태가 아니므로 계속 false다.
 */
@Immutable
data class ReaderSettingsUiState(
    val style: ReaderStyle = ReaderStyle(),
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val isLoading: Boolean = true,
)
