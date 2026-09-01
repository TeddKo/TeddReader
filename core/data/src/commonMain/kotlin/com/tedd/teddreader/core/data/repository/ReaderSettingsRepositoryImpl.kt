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
 * 읽기 환경설정, preferences 파일을 백엔드로 한다.
 *
 * 도메인의 [ReaderSettings]와 저장되는 `ReaderPreferences`는 같은 필드를 가진 별개의 타입이다.
 * 이 클래스는 그 둘을 변환하기 위해 존재하며, 이는 도메인을 건드리지 않고 저장 포맷을 바꾸는 것을
 * (키 이름 변경, 필드 제거) 가능하게 하고, 그 반대도 가능하게 한다. 레거시 값과 범위를 벗어난
 * 숫자는 여기 도달하기 전에 이미 시리얼라이저가 정규화한다.
 *
 * @property dataSource 이 클래스가 읽고 쓰는 preferences 저장소.
 */
@Single(binds = [ReaderSettingsRepository::class])
class ReaderSettingsRepositoryImpl(
    private val dataSource: ReaderPreferencesDataSource,
) : ReaderSettingsRepository {
    /** 저장된 preferences가 바뀔 때마다 [dataSource]로부터 다시 매핑되는 현재 설정. */
    override val settings: Flow<ReaderSettings> = dataSource.preferences.map { it.toReaderSettings() }

    /** [style]을 리더의 현재 타입과 색상으로 저장한다. */
    override suspend fun updateStyle(style: ReaderStyle) {
        dataSource.updateStyle(style)
    }

    /** [pageTurnMode]를 페이지 넘김이 읽히는 방식으로 저장한다 — 가로, 세로, 또는 연속. */
    override suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        dataSource.updatePageTurnMode(pageTurnMode)
    }

    /** [pageAnimation]을 페이지 넘김이 애니메이션되는 방식으로 저장한다. */
    override suspend fun updatePageAnimation(pageAnimation: PageAnimation) {
        dataSource.updatePageAnimation(pageAnimation)
    }

    /**
     * 자동 스크롤을 저장하며, 들어오는 속도를 클램프한다.
     *
     * 이 클램프는 방어책이지 유일한 방어책은 아니다: 모든 호출자는 config를 만들기 전에 슬라이더
     * 값을 클램프할 것으로 기대되며, 시리얼라이저도 읽을 때 다시 클램프한다(
     * `ReaderPreferencesSerializerTest.outOfRangeAutoScrollSpeedReadBackWithinSupportedRange`로
     * 고정됨). 쓰기 경로에도 이를 두는 것은, 잊어버린 호출자가 있더라도 리더가 슬라이더로 절대
     * 되돌릴 수 없는 속도를 저장하지 못하게 하기 위해서다.
     *
     * 의도적으로 이 계층에서는 단위 테스트하지 않는다: `ReaderPreferencesDataSource`는 final이고
     * DataStore는 이 모듈의 테스트 클래스패스에 없으므로, 클램프된 대입 하나를 커버하는 것은
     * 시리얼라이저 자체의 테스트가 이미 화면이 결국 무엇을 읽어올 수 있는지에 대해 보장하는 바를
     * 다시 진술하기 위해 빌드 의존성을 추가하는 셈이 된다.
     *
     * @param autoScrollConfig 저장할 설정; 속도는 지원되는 범위로 클램프된다.
     */
    override suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        dataSource.updateAutoScrollConfig(
            autoScrollConfig.copy(speed = AutoScrollConfig.clampSpeed(autoScrollConfig.speed)),
        )
    }

    /** [appLanguage]를 앱 자체의 인터페이스 언어로 저장한다. 어떤 책의 언어와도 무관하다. */
    override suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        dataSource.updateAppLanguage(appLanguage)
    }
}

/**
 * @receiver 디스크에 저장된 그대로의 preferences.
 * @return 도메인 자체 타입과 같은 값들. 공유 타입이 아니라 필드 단위 복사이므로 마이그레이션 없이도
 * 둘이 갈라질 수 있다.
 */
private fun ReaderPreferences.toReaderSettings(): ReaderSettings = ReaderSettings(
    style = style,
    pageTurnMode = pageTurnMode,
    pageAnimation = pageAnimation,
    autoScrollConfig = autoScrollConfig,
    appLanguage = appLanguage,
)
