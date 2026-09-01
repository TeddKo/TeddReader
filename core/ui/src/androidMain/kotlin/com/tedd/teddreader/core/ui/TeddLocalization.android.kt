package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * 어떤 인앱 재정의도 적용되기 전, [LocalAppLocale.provides]가 처음 실행될 때 캡처한 기기 자체의 기본
 * 로케일이다. 이 함수에 `value`로 null이 전달되면("재정의 해제"를 의미) 이전 재정의로 이미 변형된
 * `Locale.getDefault()`가 그 시점에 반환하는 값에 의존하는 대신, 기기가 원래 가지고 있던 값을 정확히
 * 복원할 수 있도록 이 값을 보관한다.
 */
private var default: Locale? = null

/**
 * [LocalAppLocale]의 Android 구현체. `stringResource`/Android 리소스 조회가 사용하는 로케일을 범위가
 * 한정된 Compose-local 방식으로 바꿀 방법이 없으므로, 재정의가 바뀔 때마다 JVM의 기본 [Locale]과
 * [LocalContext]의 [android.content.res.Resources] `Configuration`이라는 프로세스 전역 상태를 함께
 * 변형한다.
 */
actual object LocalAppLocale {
    /** [provides]가 실제로 변형하는 대상인, JVM의 프로세스 전역 기본 로케일을 읽는다. */
    actual val current: String
        @Composable get() = Locale.getDefault().toString()

    /**
     * 프로세스의 기본 [Locale]과 현재 [LocalContext]의 리소스 설정을 [value]로 변형한다([value]가
     * null이면 기기의 원래 [default] 로케일로 되돌린다). 이 앱이 지원하는 API 레벨에서 이미 로드된
     * 리소스를 안정적으로 다시 해석해 주는 유일한 API인, deprecated된 `updateConfiguration`을 통해
     * 이루어진다.
     */
    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = LocalConfiguration.current
        val resources = LocalContext.current.resources
        if (default == null) default = Locale.getDefault()
        val locale = if (value == null) {
            default ?: Locale.getDefault()
        } else {
            Locale(value)
        }
        Locale.setDefault(locale)
        configuration.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return LocalConfiguration.provides(configuration)
    }
}
