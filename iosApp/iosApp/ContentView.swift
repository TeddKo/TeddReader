import UIKit
import SwiftUI
import TeddReaderApp

struct ComposeView: UIViewControllerRepresentable {
    let googleDrivePickerBridge: GoogleDrivePickerBridge?

    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(googleDrivePickerBridge: googleDrivePickerBridge)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    private let googleDrivePickerBridge = GoogleDrivePickerBridgeImpl.shared

    var body: some View {
        ComposeView(googleDrivePickerBridge: googleDrivePickerBridge)
            .ignoresSafeArea()
    }
}
