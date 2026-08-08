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

@Composable
actual fun ReaderSystemBarsEffect(
    visible: Boolean,
    backgroundColor: Color,
    keepScreenOn: Boolean,
) {
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
        view.keepScreenOn = keepScreenOn
        window.setSystemBarsVisible(visible)
        window.setSystemBarIconAppearance(lightBackground)
        if (!visible) {
            view.post { window.setSystemBarsVisible(false) }
        }
    }

    SideEffect { apply() }
    LaunchedEffect(window, view, visible, backgroundColor, keepScreenOn) { apply() }

    DisposableEffect(window, view) {
        onDispose {
            view.keepScreenOn = false
            window.setSystemBarsVisible(true)
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
        decorView.systemUiVisibility = if (visible) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}

private fun Window.setSystemBarContrastEnforced(
    statusBar: Boolean,
    navigationBar: Boolean = statusBar,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = statusBar
        isNavigationBarContrastEnforced = navigationBar
    }
}

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
