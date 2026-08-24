package com.tedd.teddreader.feature.home.impl

import androidx.compose.ui.window.ComposeUIViewController

/**
 * The iOS entry point for the home feature: wraps [HomeRouteScreen] in a `UIViewController` so the
 * Swift host app can present it exactly like any other screen, without needing to know Compose
 * Multiplatform sits underneath.
 *
 * @return a `UIViewController` hosting the home screen.
 */
fun HomeViewController() = ComposeUIViewController {
    HomeRouteScreen()
}
