package com.tedd.teddreader.app.reader

import androidx.compose.ui.window.ComposeUIViewController
import com.tedd.teddreader.app.reader.importer.GoogleDrivePickerBridge

/**
 * Xcode/SwiftUI 호스트 앱이 전체 Compose UI를 감싸는 단일 `UIViewController`를 얻기 위해 호출하는
 * TeddReader의 iOS 진입점이며, `androidApp`의 `MainActivity`에 대응한다. 네이티브 Google Sign-In
 * UI와 Drive OAuth 흐름이 이 Kotlin 모듈 밖에 구현되어 있으므로 앱의 [GoogleDrivePickerBridge]를
 * Swift 측에서 연결한다.
 *
 * @param googleDrivePickerBridge 네이티브 Google Drive 선택기로 연결하는 Swift 측 브리지이며, 이
 *   빌드에 Drive 가져오기가 구성되지 않았으면 null이다. `TeddReaderApp`에 변경 없이 전달한다.
 * @return Swift 호스트 앱이 표시할 준비가 된, 구성된 [TeddReaderApp] 콘텐츠를 호스팅하는
 *   `UIViewController`다.
 */
fun MainViewController(
    googleDrivePickerBridge: GoogleDrivePickerBridge? = null,
) = ComposeUIViewController {
    TeddReaderApp(googleDrivePickerBridge = googleDrivePickerBridge)
}
