package com.tedd.teddreader.app.reader

import androidx.compose.ui.window.ComposeUIViewController
import com.tedd.teddreader.app.reader.importer.GoogleDrivePickerBridge

/**
 * The iOS entry point for TeddReader, called from the Xcode/SwiftUI host app to obtain the single
 * `UIViewController` that wraps the entire Compose UI — the iOS counterpart to `androidApp`'s
 * `MainActivity`. Wires the app's [GoogleDrivePickerBridge] in from the Swift side, since the
 * native Google Sign-In UI and Drive OAuth flow are implemented outside this Kotlin module.
 *
 * @param googleDrivePickerBridge the Swift-side bridge to the native Google Drive picker, or null
 *   when Drive import is not configured for this build; forwarded unchanged to `TeddReaderApp`.
 * @return a `UIViewController` hosting the composed [TeddReaderApp] content, ready for the Swift
 *   host app to present.
 */
fun MainViewController(
    googleDrivePickerBridge: GoogleDrivePickerBridge? = null,
) = ComposeUIViewController {
    TeddReaderApp(googleDrivePickerBridge = googleDrivePickerBridge)
}
