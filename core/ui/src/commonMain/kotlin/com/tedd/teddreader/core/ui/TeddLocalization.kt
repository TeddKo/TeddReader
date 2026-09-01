package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.key
import com.tedd.teddreader.core.common.model.AppLanguage

/**
 * 기기의 시스템 로케일과 무관하게 앱 표시 언어를 재정의하기 위한 플랫폼 훅으로, 사용자가 OS 설정을
 * 바꾸지 않고도 앱 안에서 영어나 한국어를 선택할 수 있게 한다. 이 재정의가 실제로 로드된 문자열
 * 리소스에 반영되게 하려면 Android와 iOS가 전혀 다른 메커니즘을 필요로 한다 — Android는
 * `Configuration`/`Resources` 로케일을 제자리에서 변형하고, iOS는 `AppleLanguages`와
 * `CompositionLocal`을 교체한다 — 그래서 공유 구현이 아니라 `expect`/`actual` object로 되어 있다.
 */
expect object LocalAppLocale {
    /** 리소스 조회에 현재 적용 중인 로케일 태그. 예: `"en"`, `"ko"`, 또는 시스템 태그. */
    val current: String
        @Composable get

    /**
     * 반환된 provider 아래에서 컴포즈되는 모든 것에 대해 [value]를 활성 로케일로 설정한다.
     *
     * @param value `"en"`/`"ko"`와 같은 리소스 로케일 태그, 또는 기기 자체 로케일로 되돌리려면 null.
     * @return [CompositionLocalProvider]에 사용할 [ProvidedValue].
     */
    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/**
 * 앱 자체 설정인 [AppLanguage]를 [LocalAppLocale.provides]와 Compose Multiplatform 리소스 시스템이
 * 기대하는 로케일 태그로 매핑한다. 각 호출자가 `"en"`/`"ko"` 태그를 직접 명시하게 하는 대신 이 매핑을
 * 한 곳에 모아 둔다.
 *
 * @receiver 사용자가 선택한 앱 언어.
 * @return 명시적인 언어 선택에 대해서는 `"en"`/`"ko"`, [AppLanguage.SYSTEM]에 대해서는 "기기가 이미
 * 사용 중인 로케일을 그대로 쓴다"는 의미로 null.
 */
fun AppLanguage.resourceLocaleTag(): String? = when (this) {
    AppLanguage.SYSTEM -> null
    AppLanguage.ENGLISH -> "en"
    AppLanguage.KOREAN -> "ko"
}

/**
 * 사용자의 [appLanguage] 선택을 [content] 아래 모든 요소에 적용하고, 해석된 태그가 바뀔 때마다
 * `key(localeTag)`를 통해 해당 서브트리의 재컴포지션을 강제한다 — Compose Multiplatform의
 * `stringResource` 호출은 [LocalAppLocale]이 바뀌었다는 것만으로 자동 재컴포지션되지 않으므로 이
 * 작업이 필요하다. `key` 래핑이 없으면, 언어를 전환해도 다른 무언가가 재컴포지션을 일으키기 전까지는
 * 이미 컴포즈된 텍스트가 이전 언어를 그대로 보여주게 된다.
 *
 * @param appLanguage 앱 설정에서 선택된, [content]에 적용할 언어.
 * @param content [appLanguage] 적용을 받아야 하는 서브트리.
 */
@Composable
fun ProvideTeddLocalization(
    appLanguage: AppLanguage,
    content: @Composable () -> Unit,
) {
    val localeTag = appLanguage.resourceLocaleTag()
    CompositionLocalProvider(LocalAppLocale provides localeTag) {
        key(localeTag) {
            content()
        }
    }
}
