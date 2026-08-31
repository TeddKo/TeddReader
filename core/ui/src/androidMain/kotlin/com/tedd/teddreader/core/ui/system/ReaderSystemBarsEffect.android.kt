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
 * Applies the global reader theme to Android's status/navigation bars for the lifetime of the app
 * composition. The app root owns this effect, so every destination receives the same colour and
 * icon-contrast update when the persisted theme changes.
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

/** Reader-only immersive visibility and keep-awake behavior; global theme colours stay untouched. */
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
 * Unwraps a Compose [LocalView]'s [Context] to the [Activity] that owns its [Window], since Compose
 * only guarantees the view's context is *some* [ContextWrapper] chain leading to an activity, not
 * that it is the activity itself directly.
 *
 * @receiver A view context, potentially wrapped (e.g. by a `ContextThemeWrapper`).
 * @return The owning [Activity], or null if none of the wrapper chain is one (which should not
 * happen for a view actually attached to an activity window, but is handled defensively).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Shows or hides the status and navigation bars, using [WindowInsetsController] from API 30 onward
 * and falling back to the deprecated `systemUiVisibility` flags on older releases, since
 * [WindowInsetsController] does not exist before API 30. Hiding also sets
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` so a swipe can still reveal the bars temporarily, matching
 * the immersive-but-recoverable behavior the reader wants while its own chrome is hidden.
 *
 * @receiver The window whose system bars should be shown or hidden.
 * @param visible Whether the bars should be shown (true) or hidden (false).
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
 * Toggles the platform's automatic scrim behind the status/navigation bars — the dimming Android
 * applies by default to keep bar icons legible over arbitrary content — which is only available from
 * API 29 onward and is a no-op below that. The reader turns this off because it already colors the
 * bars to match the page background and computes its own icon appearance from that color, so the
 * platform's own scrim would fight with a background the reader has already made legible on purpose.
 *
 * @receiver The window to change contrast enforcement on.
 * @param statusBar Whether the platform's own contrast scrim is enforced behind the status bar.
 * @param navigationBar Whether it is enforced behind the navigation bar; defaults to [statusBar]'s
 * value when the two are not being set independently.
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
 * Switches the status/navigation bar icons and text between light and dark styling to stay legible
 * against [lightBackground], using [WindowInsetsController]'s appearance flags from API 30 onward
 * and the older `SYSTEM_UI_FLAG_LIGHT_*`/`systemUiVisibility` flags on earlier releases (status-bar
 * light icons from API 23, navigation-bar light icons from API 26 — below those levels the icon
 * color simply cannot be changed, so this silently has no visible effect there).
 *
 * @receiver The window to restyle system bar icons on.
 * @param lightBackground Whether the bars now sit over a light background and therefore need dark
 * icons for contrast.
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
 * Reverses [setSystemBarIconAppearance] on dispose, restoring the exact icon-appearance bits this
 * screen found in place before it started (captured as `originalSystemBarIconAppearance`), rather
 * than assuming the app's default is "dark icons" — a caller that had already set light icons of its
 * own would otherwise have that choice silently overwritten once the reader closes.
 *
 * @receiver The window to restore system bar icon appearance on.
 * @param originalAppearance The pre-effect appearance bitmask, in the same encoding
 * [setSystemBarIconAppearance] and this app's window originally used for the current API level
 * (`WindowInsetsController` appearance flags from API 30, `systemUiVisibility` flags below it).
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
