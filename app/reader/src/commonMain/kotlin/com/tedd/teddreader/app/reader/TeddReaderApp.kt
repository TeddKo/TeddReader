package com.tedd.teddreader.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.app.reader.di.ProvidePlatformKoinInput
import com.tedd.teddreader.app.reader.di.ReaderAppModule
import com.tedd.teddreader.app.reader.importer.ExternalDocumentImportRequest
import com.tedd.teddreader.app.reader.importer.GoogleDrivePickerBridge
import com.tedd.teddreader.app.reader.importer.rememberDocumentImporter
import com.tedd.teddreader.app.reader.navigation.ReaderNavHost
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.resolveSystemTheme
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.readerColors
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.ui.ProvideTeddLocalization
import com.tedd.teddreader.core.ui.system.SystemBarsThemeEffect
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration
import org.koin.plugin.module.dsl.module

/**
 * TeddReader의 컴포지션 루트로, `androidApp`의 `MainActivity`와 iOS의 `MainViewController`가
 * 전체 앱을 구성하기 위해 호출하는 단일 Composable이다. 프로세스 전역 `startKoin()` 대신
 * [ReaderAppModule]이 스캔·편입한 모든 어노테이션 정의로만 구성된 이 Composable 전용
 * [org.koin.compose.KoinApplication]을 시작하므로 DI 그래프의 수명이 전체 프로세스가 아니라 이
 * Composable 자체의 수명에 묶인다. Android `Context`처럼 컴포지션에서만 얻을 수 있는 플랫폼 입력은
 * `KoinApplication`보다 먼저 호출하는 [ProvidePlatformKoinInput]이 플랫폼별 홀더에 채워 넣으며, 모듈
 * 집합 자체는 [ReaderAppModule] 하나로 정적으로 고정되어 컴파일러 플러그인이 전체 그래프를 컴파일
 * 타임에 검증한다. 저장된 [ReaderSettings]를 읽어 다크/라이트 모드와 현지화를 결정하고, 해석된
 * [DocumentImporter]와 대기 중인 외부 가져오기 요청을 이후의 내비게이션과 화면 콘텐츠를 소유하는
 * [ReaderNavHost]로 전달한다.
 *
 * @param initialExternalImportRequest 시작 시 한 번 처리할 문서 가져오기 요청으로, 일반적으로 OS가
 *   수신 인텐트나 공유 대상으로 앱에 전달한 파일이다. 가져와 열도록 [ReaderNavHost]에 그대로
 *   전달한다. null이면 첨부 문서 없이 일반적으로 앱을 시작했음을 뜻한다.
 * @param googleDrivePickerBridge Google Drive 파일 선택기를 열고 결과를 액세스 토큰으로 교환할 수 있는
 *   플랫폼 브리지이며, 현재 플랫폼/빌드에 Drive 연동이 구성되지 않았으면 null이다. 정상 동작하는
 *   브리지가 있을 때만 생성된 [DocumentImporter]가 Drive 가져오기를 사용 가능하다고 알리도록
 *   [rememberDocumentImporter]에 전달한다.
 * @param modifier [ReaderNavHost]를 담는 [Box]에 적용하여 호출자가 전체 앱 콘텐츠의 크기나 위치를
 *   정할 수 있게 하는 수정자다.
 * @param darkTheme 플랫폼의 시스템 다크 테마 신호다. 사용자가 저장한 테마 모드가
 *   [ReaderThemeMode.SYSTEM] 또는 [ReaderThemeMode.PUBLISHER]일 때 참조하며, 해당 설정과 결합하는
 *   방식은 [appUsesDarkTheme]을 참고한다. 호출자가 직접 값을 읽지 않아도 되도록 현재
 *   [isSystemInDarkTheme] 값이 기본값이다.
 */
@Composable
fun TeddReaderApp(
    initialExternalImportRequest: ExternalDocumentImportRequest? = null,
    googleDrivePickerBridge: GoogleDrivePickerBridge? = null,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    ProvidePlatformKoinInput()

    KoinApplication(
        configuration = koinConfiguration { module<ReaderAppModule>() },
    ) {
        val documentImporter = rememberDocumentImporter(googleDrivePickerBridge = googleDrivePickerBridge)
        val readerSettingsRepository = koinInject<ReaderSettingsRepository>()
        val settings by readerSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
        val appDarkTheme = appUsesDarkTheme(
            themeMode = settings.style.themeMode,
            systemInDarkTheme = darkTheme,
        )
        val systemBarBackground = appSystemBarBackground(
            style = settings.style,
            systemInDarkTheme = darkTheme,
        )
        ProvideTeddLocalization(appLanguage = settings.appLanguage) {
            TeddReaderTheme(
                darkTheme = appDarkTheme,
            ) {
                SystemBarsThemeEffect(backgroundColor = systemBarBackground)
                Box(
                    modifier = modifier.fillMaxSize().background(systemBarBackground),
                ) {
                    ReaderNavHost(
                        modifier = Modifier.fillMaxSize(),
                        documentImporter = documentImporter,
                        externalImportRequest = initialExternalImportRequest,
                    )
                }
            }
        }
    }
}

/** 저장된 전역 테마를 모든 시스템 바 뒤에 그릴 불투명 색상으로 해석한다. */
internal fun appSystemBarBackground(style: ReaderStyle, systemInDarkTheme: Boolean) =
    style.resolveSystemTheme(systemInDarkTheme).readerColors().background.copy(alpha = 1f)

/**
 * 사용자가 저장한 [ReaderThemeMode]와 플랫폼의 현재 시스템 설정을 [TeddReaderTheme]에 필요한 단일
 * 불리언 값으로 해석한다. [ReaderThemeMode.SYSTEM]과 [ReaderThemeMode.PUBLISHER]는
 * [systemInDarkTheme]을 참조한다. 출판사 모드는 문서 자체의 페이지 색상을 유지하고 별도의 앱 크롬
 * 팔레트가 없으므로 시스템을 따른다. 반면 [ReaderThemeMode.LIGHT], [ReaderThemeMode.SEPIA],
 * [ReaderThemeMode.CUSTOM]은 Material의 다크 색상표를 따르지 않고 디자인 시스템의 다른 위치에서
 * 자체 읽기 표면 팔레트를 제공하므로 시스템 설정과 관계없이 모두 라이트 크롬으로 해석된다.
 *
 * @param themeMode 사용자가 선택하여 [ReaderSettings]에 저장한 리더 테마다.
 * @param systemInDarkTheme 이 함수가 Composable이 아닌 순수 결정 함수로 유지되도록 호출자가 한 번
 *   읽어 전달하는 플랫폼의 현재 시스템 전역 다크 테마 플래그다.
 * @return 앱 크롬을 [TeddReaderTheme]의 다크 색상표로 렌더링해야 하면 true다.
 */
internal fun appUsesDarkTheme(themeMode: ReaderThemeMode, systemInDarkTheme: Boolean): Boolean =
    when (themeMode) {
        ReaderThemeMode.PUBLISHER,
        ReaderThemeMode.SYSTEM,
            -> systemInDarkTheme
        ReaderThemeMode.DARK -> true
        ReaderThemeMode.LIGHT, ReaderThemeMode.SEPIA, ReaderThemeMode.CUSTOM -> false
    }
