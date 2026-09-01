package com.tedd.teddreader.feature.home.impl

import androidx.compose.ui.window.ComposeUIViewController

/**
 * 홈 기능의 iOS 진입점이다. [HomeRouteScreen]을 `UIViewController`로 감싸므로 Swift 호스트 앱은
 * 내부에서 Compose Multiplatform이 동작한다는 사실을 알 필요 없이 다른 화면과 동일하게 표시할 수 있다.
 *
 * @return 홈 화면을 호스팅하는 `UIViewController`.
 */
fun HomeViewController() = ComposeUIViewController {
    HomeRouteScreen()
}
