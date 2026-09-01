package com.tedd.teddreader.core.ui.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

/**
 * 앱 컴포지션이 살아있는 동안 전역 리더 테마를 Android의 상태 표시줄/내비게이션 바에 적용한다. 앱
 * 루트가 이 이펙트를 소유하므로, 저장된 테마가 바뀔 때 모든 대상 화면이 동일한 색상 및 아이콘 대비
 * 업데이트를 받는다.
 */
@Composable
actual fun SystemBarsThemeEffect(backgroundColor: Color) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    val lightBackground = backgroundColor.luminance() > 0.5f

    val originalStatusBarColor = remember(window) { window.statusBarColor }
    val originalNavigationBarColor = remember(window) { window.navigationBarColor }
    val originalDecorBackground = remember(window) { window.decorView.background }
    val originalStatusBarContrastEnforced = remember(window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced else false
    }
    val originalNavigationBarContrastEnforced = remember(window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else false
    }
    val originalSystemBarIconAppearance = remember(window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.systemBarsAppearance ?: 0
        } else {
            window.decorView.systemUiVisibility
        }
    }

    fun apply() {
        val color = backgroundColor.toArgb()
        window.statusBarColor = color
        window.navigationBarColor = color
        window.decorView.setBackgroundColor(color)
        window.setSystemBarContrastEnforced(false)
        window.setSystemBarIconAppearance(lightBackground)
    }

    SideEffect { apply() }
    LaunchedEffect(window, view, backgroundColor) { apply() }

    DisposableEffect(window) {
        onDispose {
            window.restoreSystemBarIconAppearance(originalSystemBarIconAppearance)
            window.statusBarColor = originalStatusBarColor
            window.navigationBarColor = originalNavigationBarColor
            window.decorView.background = originalDecorBackground
            window.setSystemBarContrastEnforced(
                statusBar = originalStatusBarContrastEnforced,
                navigationBar = originalNavigationBarContrastEnforced,
            )
        }
    }
}

/** 리더 전용 몰입형 가시성 및 화면 꺼짐 방지 동작이며, 전역 테마 색상은 건드리지 않는다. */
@Composable
actual fun ReaderSystemBarsEffect(
    visible: Boolean,
    keepScreenOn: Boolean,
) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return

    fun apply() {
        view.keepScreenOn = keepScreenOn
        window.setSystemBarsVisible(visible)
        if (!visible) {
            view.post { window.setSystemBarsVisible(false) }
        }
    }

    SideEffect { apply() }
    LaunchedEffect(window, view, visible, keepScreenOn) { apply() }

    DisposableEffect(window, view) {
        onDispose {
            view.keepScreenOn = false
            window.setSystemBarsVisible(true)
        }
    }
}

/**
 * Compose [LocalView]의 [Context]를 그 [Window]를 소유한 [Activity]로 풀어낸다. Compose는 뷰의
 * context가 액티비티로 이어지는 *어떤* [ContextWrapper] 체인이라는 것만 보장할 뿐, 그 자체가
 * 액티비티라는 것은 보장하지 않기 때문이다.
 *
 * @receiver 래핑되어 있을 수 있는(예: `ContextThemeWrapper`) 뷰 컨텍스트.
 * @return 소유한 [Activity], 또는 래퍼 체인 어디에도 액티비티가 없으면 null(실제로 액티비티 창에
 * 붙어 있는 뷰라면 발생하지 않아야 하지만, 방어적으로 처리한다).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 상태 표시줄과 내비게이션 바를 표시하거나 숨긴다. API 30 이상에서는 [WindowInsetsController]를
 * 사용하고, [WindowInsetsController]가 API 30 이전에는 존재하지 않으므로 이전 릴리스에서는
 * deprecated된 `systemUiVisibility` 플래그로 대체한다. 숨길 때는
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`도 함께 설정해, 리더 자체 크롬이 숨겨진 동안에도 스와이프로
 * 바를 일시적으로 드러낼 수 있게 함으로써 리더가 원하는 몰입형이면서도 복구 가능한 동작을 맞춘다.
 *
 * @receiver 시스템 바를 표시하거나 숨길 대상 창.
 * @param visible 바를 표시할지(true) 숨길지(false).
 */
@Suppress("DEPRECATION")
private fun Window.setSystemBarsVisible(visible: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val bars = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
        if (visible) {
            insetsController?.show(bars)
        } else {
            insetsController?.hide(bars)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    } else {
        val iconAppearance = decorView.systemUiVisibility and (
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        )
        val visibility = if (visible) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        decorView.systemUiVisibility = visibility or iconAppearance
    }
}

/**
 * 상태 표시줄/내비게이션 바 뒤에 있는 플랫폼의 자동 스크림 — Android가 임의의 콘텐츠 위에서도 바
 * 아이콘을 읽을 수 있게 기본으로 적용하는 어둡게 처리 — 을 켜고 끈다. 이 기능은 API 29 이상에서만
 * 제공되며 그 이하에서는 아무 동작도 하지 않는다. 리더는 이미 페이지 배경에 맞춰 바 색상을 지정하고
 * 그 색상으로부터 자체 아이콘 모양을 계산하므로, 플랫폼 자체 스크림을 끈다. 그렇지 않으면 리더가
 * 의도적으로 이미 잘 읽히도록 만든 배경과 플랫폼 스크림이 서로 충돌하게 된다.
 *
 * @receiver 대비 강제 적용 여부를 바꿀 대상 창.
 * @param statusBar 상태 표시줄 뒤에 플랫폼 자체 대비 스크림을 강제 적용할지 여부.
 * @param navigationBar 내비게이션 바 뒤에 강제 적용할지 여부. 둘을 독립적으로 설정하지 않을 때는
 * [statusBar] 값을 기본값으로 사용한다.
 */
private fun Window.setSystemBarContrastEnforced(
    statusBar: Boolean,
    navigationBar: Boolean = statusBar,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = statusBar
        isNavigationBarContrastEnforced = navigationBar
    }
}

/**
 * [lightBackground]에 대해 계속 읽히도록 상태 표시줄/내비게이션 바의 아이콘과 텍스트를 밝은 스타일과
 * 어두운 스타일 사이에서 전환한다. API 30 이상에서는 [WindowInsetsController]의 appearance 플래그를
 * 사용하고, 그 이전 릴리스에서는 오래된 `SYSTEM_UI_FLAG_LIGHT_*`/`systemUiVisibility` 플래그를
 * 사용한다(상태 표시줄 밝은 아이콘은 API 23부터, 내비게이션 바 밝은 아이콘은 API 26부터 — 그 이하
 * 레벨에서는 아이콘 색상을 아예 바꿀 수 없으므로 이 경우 조용히 아무 시각적 효과도 내지 않는다).
 *
 * @receiver 시스템 바 아이콘 스타일을 다시 지정할 대상 창.
 * @param lightBackground 바가 이제 밝은 배경 위에 놓여 있어 대비를 위해 어두운 아이콘이 필요한지
 * 여부.
 */
private fun Window.setSystemBarIconAppearance(lightBackground: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val appearance = if (lightBackground) {
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        } else {
            0
        }
        insetsController?.setSystemBarsAppearance(
            appearance,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        return
    }

    var flags = decorView.systemUiVisibility
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        flags = if (lightBackground) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        flags = if (lightBackground) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
    }
    decorView.systemUiVisibility = flags
}

/**
 * dispose 시점에 [setSystemBarIconAppearance]를 되돌려, 앱의 기본값이 "어두운 아이콘"이라고 가정하는
 * 대신 이 화면이 시작되기 전에 원래 존재하던 아이콘 모양 비트(`originalSystemBarIconAppearance`로
 * 캡처됨)를 정확히 복원한다. 그렇지 않으면 이미 스스로 밝은 아이콘을 설정해 둔 호출자가 리더를 닫는
 * 순간 그 선택을 조용히 덮어써 버리게 된다.
 *
 * @receiver 시스템 바 아이콘 모양을 복원할 대상 창.
 * @param originalAppearance 이펙트 적용 전의 appearance 비트마스크. 현재 API 레벨에서
 * [setSystemBarIconAppearance]와 이 앱의 창이 원래 사용하던 것과 같은 인코딩을 따른다(API 30부터는
 * `WindowInsetsController`의 appearance 플래그, 그 이하에서는 `systemUiVisibility` 플래그).
 */
private fun Window.restoreSystemBarIconAppearance(originalAppearance: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insetsController?.setSystemBarsAppearance(
            originalAppearance,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        return
    }

    var flags = decorView.systemUiVisibility
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        flags = flags or (originalAppearance and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        flags = flags or (originalAppearance and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }
    decorView.systemUiVisibility = flags
}
