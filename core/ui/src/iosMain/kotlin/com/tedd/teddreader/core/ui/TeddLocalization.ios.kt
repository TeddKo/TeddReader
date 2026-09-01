package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

/** iOS `AppleLanguages` user-defaults 설정이 저장되는 키. */
private const val LANG_KEY = "AppleLanguages"

/** [LocalAppLocale.provides]가 처음 실행될 때 캡처한 기기 자체의 선호 언어. */
private var default: String? = null

/**
 * [LocalAppLocale.current]를 뒷받침하는 `CompositionLocal`. Android와 달리 iOS에는 Compose 읽기가
 * 관찰할 수 있는 단일한 변경 가능 "현재 로케일"이 없으므로, 재정의 값은 시스템에서 다시 읽어오는
 * 대신 이 `CompositionLocal`을 통해 전달된다.
 */
private val LocalAppLocaleValue = staticCompositionLocalOf { "en" }

/**
 * [LocalAppLocale]의 iOS 구현체. Kotlin/Native의 Foundation interop은 `NSLocale.preferredLanguages`를
 * 읽는 것만 노출할 뿐 Compose 리소스 해석만을 위해 범위가 한정된 재정의 방법을 제공하지 않으므로,
 * 이 구현은 재정의 값을 `NSUserDefaults`의 `AppleLanguages` 키에 기록하고(iOS 설정에서 언어를 바꾸는
 * 것과 같은 효과를 내어, 같은 키를 읽는 네이티브 코드도 동기화 상태를 유지한다) [current]가 다시
 * 읽을 수 있도록 [LocalAppLocaleValue]를 통해서도 제공한다.
 */
@OptIn(InternalComposeUiApi::class)
actual object LocalAppLocale {
    /** [LocalAppLocaleValue]에서 읽은, [provides]를 통해 가장 최근에 제공된 로케일 태그. */
    actual val current: String
        @Composable get() = LocalAppLocaleValue.current

    /**
     * [value]를 [LANG_KEY] 아래 `NSUserDefaults`에 영속화하고([value]가 null이면 그 키를 제거하여
     * 기기 자체의 [default] 선호 언어로 되돌린다), [LocalAppLocaleValue]를 통해 이를 제공한다.
     */
    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (default == null) {
            default = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
        }
        val new = value ?: default ?: "en"
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(LANG_KEY)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(listOf(new), forKey = LANG_KEY)
        }
        return LocalAppLocaleValue.provides(new)
    }
}
