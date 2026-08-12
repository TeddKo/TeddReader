import AppAuth
import Foundation
import UIKit
import TeddReaderApp

final class GoogleDrivePickerBridgeImpl: NSObject, GoogleDrivePickerBridge {
    static let shared = GoogleDrivePickerBridgeImpl()

    private let driveScope = "https://www.googleapis.com/auth/drive.file"
    private let authorizationEndpoint = URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!
    private let tokenEndpoint = URL(string: "https://oauth2.googleapis.com/token")!
    private var currentAuthorizationFlow: OIDExternalUserAgentSession?

    private override init() {}

    var isConfigured: Bool {
        guard let clientID = Bundle.main.object(forInfoDictionaryKey: "GoogleDriveClientID") as? String,
              let redirectURI = Bundle.main.object(forInfoDictionaryKey: "GoogleDriveRedirectURI") as? String,
              let urlScheme = Bundle.main.object(forInfoDictionaryKey: "GoogleDriveURLScheme") as? String,
              let redirectURL = URL(string: redirectURI) else {
            return false
        }
        let values = [clientID, redirectURI, urlScheme]
        return values.allSatisfy {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !$0.contains("YOUR_GOOGLE_DRIVE")
        } && redirectURL.scheme == urlScheme
    }

    func open(
        onPicked: @escaping (GoogleDrivePickerResult) -> Void,
        onCancelled: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) {
        guard isConfigured else {
            onError("Google Drive is not configured.")
            return
        }
        guard let presenter = topViewController() else {
            onError("Cannot open Google Drive right now.")
            return
        }
        guard let clientID = Bundle.main.object(forInfoDictionaryKey: "GoogleDriveClientID") as? String,
              let redirectURIString = Bundle.main.object(forInfoDictionaryKey: "GoogleDriveRedirectURI") as? String,
              let redirectURI = URL(string: redirectURIString) else {
            onError("Google Drive configuration is invalid.")
            return
        }

        currentAuthorizationFlow?.cancel()
        let configuration = OIDServiceConfiguration(
            authorizationEndpoint: authorizationEndpoint,
            tokenEndpoint: tokenEndpoint
        )
        let request = OIDAuthorizationRequest(
            configuration: configuration,
            clientId: clientID,
            clientSecret: nil,
            scopes: [driveScope],
            redirectURL: redirectURI,
            responseType: OIDResponseTypeCode,
            additionalParameters: [
                "prompt": "consent",
                "trigger_onepick": "true",
                "allow_multiple": "true",
                "mimetypes": "text/plain,application/pdf,application/epub+zip",
            ]
        )

        currentAuthorizationFlow = OIDAuthState.authState(byPresenting: request, presenting: presenter) { [weak self] authState, error in
            defer { self?.currentAuthorizationFlow = nil }

            if let error = error as NSError? {
                if error.code == OIDErrorCode.userCanceledAuthorizationFlow.rawValue {
                    onCancelled()
                } else {
                    onError(error.localizedDescription)
                }
                return
            }

            guard let authState,
                  let accessToken = authState.lastTokenResponse?.accessToken?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !accessToken.isEmpty else {
                onError("Google Drive authorization did not return an access token.")
                return
            }
            guard let rawIDs = authState.lastAuthorizationResponse.additionalParameters?["picked_file_ids"] as? String else {
                onError("Google Drive did not return any selected files.")
                return
            }

            let fileIDs = rawIDs
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .reduce(into: [String]()) { result, id in
                    if !result.contains(id) {
                        result.append(id)
                    }
                }

            guard !fileIDs.isEmpty else {
                onError("Google Drive did not return any selected files.")
                return
            }

            onPicked(GoogleDrivePickerResult(accessToken: accessToken, fileIds: fileIDs))
        }
    }

    func resume(url: URL) -> Bool {
        guard let authorizationFlow = currentAuthorizationFlow else {
            return false
        }
        let didResume = TeddResumeExternalUserAgentFlow(authorizationFlow, url)
        if didResume {
            currentAuthorizationFlow = nil
        }
        return didResume
    }

    private func topViewController(
        base: UIViewController? = UIApplication.shared
            .connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: { $0.isKeyWindow })?
            .rootViewController
    ) -> UIViewController? {
        if let navigationController = base as? UINavigationController {
            return topViewController(base: navigationController.visibleViewController)
        }
        if let tabBarController = base as? UITabBarController,
           let selected = tabBarController.selectedViewController {
            return topViewController(base: selected)
        }
        if let presented = base?.presentedViewController {
            return topViewController(base: presented)
        }
        return base
    }
}
