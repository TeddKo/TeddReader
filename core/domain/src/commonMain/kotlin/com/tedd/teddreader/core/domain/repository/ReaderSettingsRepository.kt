package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlinx.coroutines.flow.Flow

/**
 * 모든 읽기 설정을 하나의 값으로 제공해 화면이 여러 번 읽어 스냅샷을 조립하지 않게 한다.
 *
 * 기본값은 의도적으로 리더가 아닌 여기에 둔다. 새 설정의 기본값을 한 곳에서 정할 수 있고, 이미 저장된 모든
 * 설정 객체는 마이그레이션 없이 계속 역직렬화된다.
 *
 * @property style 리더가 그릴 때 사용하는 글꼴 설정과 페이지 색상. 레이아웃에 영향을 주는 필드가 페이지
 * 재측정을 유발하는 유일한 구성원이다.
 * @property pageTurnMode 페이지 넘김 방향이며, 스와이프와 가장자리 탭을 해석하는 방식도 결정한다.
 * @property pageAnimation 페이지를 넘길 때 리더가 사용하는 페이저 구현.
 * @property autoScrollConfig 자동 스크롤 활성 여부, 이동 단위, 속도.
 * @property appLanguage 책과 무관하게 앱 자체 문자열을 표시할 언어.
 */
data class ReaderSettings(
    val style: ReaderStyle = ReaderStyle(),
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
)

/**
 * 문서별이 아니라 앱 전체에 한 번 저장하는 읽기 설정이다.
 *
 * 설정 화면의 변경이 두 화면이 서로를 알지 않고도 열린 리더에 도달해야 하므로 플로우로 노출한다. 리더는
 * [settings]를 수집하고 레이아웃에 영향을 주는 필드가 바뀌면 페이지를 다시 나눈다.
 *
 * 두 화면이 서로 다른 설정을 동시에 편집할 때 전체 객체의 오래된 복사본으로 상대 필드를 덮어쓰지 않도록
 * 하나의 `update(ReaderSettings)` 대신 관심사마다 하나의 쓰기 작업을 제공한다.
 */
interface ReaderSettingsRepository {
    /** 현재 설정과 이후 모든 변경을 제공하며, 지금 저장된 값부터 시작한다. */
    val settings: Flow<ReaderSettings>

    /**
     * 읽기 스타일을 저장한다.
     *
     * @param style 새 스타일. 글꼴 크기, 줄 높이, 글꼴 모음이 바뀌면 이전 글꼴 설정으로 측정한 페이지 레이아웃이
     * 무효화되지만 색상만 바뀌면 무효화되지 않는다.
     */
    suspend fun updateStyle(style: ReaderStyle)

    /**
     * 페이지 넘김 방향을 저장한다.
     *
     * @param pageTurnMode 새 방향. `CONTINUOUS`는 저장하지 않는다. [PageTurnMode]를 참고한다.
     */
    suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode)

    /**
     * 페이지 넘김을 애니메이션으로 표현할 페이저를 저장한다.
     *
     * @param pageAnimation 새 애니메이션. 레거시 값은 저장하지 않는다. [PageAnimation]을 참고한다.
     */
    suspend fun updatePageAnimation(pageAnimation: PageAnimation)

    /**
     * 속도는 단위 없이 의미가 없으므로 자동 스크롤의 스위치, 단위, 속도를 함께 저장한다.
     *
     * @param autoScrollConfig 새 자동 스크롤 설정. 슬라이더에서 왔다면 이미 [AutoScrollConfig.clampSpeed]로
     * 범위를 제한한 값이다.
     */
    suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig)

    /**
     * 앱 언어를 저장한다.
     *
     * @param appLanguage 새 언어. 컴포지션 루트가 다음 컴포지션에 적용한다.
     */
    suspend fun updateAppLanguage(appLanguage: AppLanguage)
}
