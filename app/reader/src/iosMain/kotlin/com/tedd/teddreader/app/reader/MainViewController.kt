package com.tedd.teddreader.app.reader

import androidx.compose.ui.window.ComposeUIViewController
import com.tedd.teddreader.app.reader.importer.GoogleDrivePickerBridge

fun MainViewController(
    googleDrivePickerBridge: GoogleDrivePickerBridge? = null,
) = ComposeUIViewController {
    TeddReaderApp(googleDrivePickerBridge = googleDrivePickerBridge)
}
