# Google Drive Picker setup

TeddReader uses Google Drive's picker flow on both Android and iOS with the minimal `drive.file` scope. That scope only grants access to files the user explicitly selected in the picker.

## Google Cloud setup

1. Enable **Google Drive API** and **Google Picker API** in the same Google Cloud project.
2. Configure the OAuth consent screen.
3. While the app is still unpublished, keep the consent screen in **Testing** and add every tester as an OAuth test user.

## Android OAuth client

1. Create an **Android** OAuth client for package `com.tedd.teddreader`.
2. Register both debug and release SHA-1 signing certificate fingerprints.
3. Verify the installed build matches one of those SHA-1 fingerprints before testing Google Drive import.

## iOS OAuth client

1. Create an **iOS** OAuth client whose bundle ID exactly matches the iOS app bundle identifier used by `iosApp`.
2. Copy that iOS client ID into `GOOGLE_DRIVE_IOS_CLIENT_ID` in `iosApp/Configuration/Config.xcconfig`.
3. Set `GOOGLE_DRIVE_IOS_CLIENT_ID` to the full iOS client ID:
   - `<client-id-prefix>.apps.googleusercontent.com`
4. Use the reversed prefix form for `GOOGLE_DRIVE_IOS_URL_SCHEME`:
   - `com.googleusercontent.apps.<client-id-prefix>`
5. Use this redirect URI format:
   - `<scheme>:/oauthredirect`
6. Keep `iosApp/iosApp/Info.plist` and `Config.xcconfig` aligned with that exact scheme and redirect URI.
7. Do not configure or ship a client secret for iOS.

## Verification flow

### Android

1. Install a build signed with a registered SHA-1.
2. Open **Library → Add documents → Google Drive**.
3. Select an account, grant consent, and pick one or more `txt`, `pdf`, or `epub` files.
4. Confirm the documents import into the library and a single successful import still opens Reader automatically.

### iOS

1. Fill in the iOS client ID, redirect URI, and URL scheme placeholders.
2. Install the app on a real device with those credentials configured.
3. Open **Library → Add documents → Google Drive**.
4. Complete the AppAuth browser flow, pick one or more `txt`, `pdf`, or `epub` files, and verify they import into the library.

## Notes

- The iOS Google Drive button is hidden while the `Config.xcconfig` placeholders are still present or otherwise invalid.
- Android and iOS both require real Google Cloud credentials and real device verification before this feature can be considered validated.

## Official references

- Google Picker for desktop and mobile apps: https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker
- Android authorization with `AuthorizationClient`: https://developer.android.com/identity/authorization
- Google OAuth 2.0 for native apps: https://developers.google.com/identity/protocols/oauth2/native-app
- AppAuth for iOS: https://github.com/openid/AppAuth-iOS
- Drive API `files.get`: https://developers.google.com/workspace/drive/api/reference/rest/v3/files/get
