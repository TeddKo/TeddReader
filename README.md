# TeddReader

*[한국어](README.ko.md)*

A reader for the documents you already have on your device. Open a TXT, EPUB, PDF, CBZ or a folder
of images, and it remembers where you stopped. Android and iOS share the same Kotlin and the same
Compose UI; nothing is uploaded anywhere, and there is no account to create.

## What it does

**Library.** Import files from local storage or pick them from Google Drive. Sort by recent, title
or format, filter by type, and group documents into folders you name yourself. The home screen leads
with whatever you were last reading.

**Reading.** Pages turn horizontally or vertically, or scroll continuously. On a tablet or an
unfolded foldable the reader lays out a two-page spread and puts the gutter on the hinge, so the
text never falls into the fold.

**Page turns.** Ten of them: none, slide, fade, scroll, fluid pager, curl, 3D curl, circle reveal,
movie carousel and page flip. Curl, 3D curl and page flip follow your finger and settle where you
release; in a spread they fold a single leaf on the spine rather than sliding the whole sheet. Page
flip uses front/back leaf lighting with a cast shadow on the page underneath.

**Type.** Font size, line height, family — the document's own, sans, serif or mono — and weight,
from 300 to 600. Emphasis is set relative to the weight you choose, so a heading or a bold run keeps
its contrast against the body at every setting. Text is repaginated against real measured line
boxes, so a larger size or a heavier weight reflows the book instead of clipping it.

**Comfort.** Page colours follow the document, the system, or a light, dark or sepia theme of their
own. A dimming overlay reads below the display's own minimum brightness, and auto-scroll advances by
pixel, line or page. The interface follows the system language, or can be pinned to English or
Korean.

**Finding your place.** Bookmarks, in-document search, a page jump, a progress slider, and a
document info sheet. Reading position is stored per document as a text anchor, so it survives a
font change that renumbers every page.

## Layout of the repository

| Path | What lives there |
| --- | --- |
| `androidApp/` | Android entry point and manifest |
| `iosApp/` | Xcode project, SwiftUI entry point, Google Drive picker bridge |
| `app/reader/` | Application composition: DI graph, navigation host, theme wiring |
| `core/common/` | Models and pure logic with no platform or framework dependency |
| `core/domain/` | Repository interfaces and use cases |
| `core/data/` | Repository implementations, importers, pagination engine |
| `core/room/`, `core/datastore/` | Room database and DataStore preferences |
| `core/designsystem/` | Theme, colors, typography, spacing, icons |
| `core/ui/` | Shared composables used by more than one feature |
| `feature/<name>/api/` | The public surface a feature exposes to others |
| `feature/<name>/impl/` | Screens, view models and components of that feature |
| `build-logic/` | Convention plugins; module build files are one `id(...)` line |

Features are split `api` / `impl` so nothing depends on another feature's internals. `home`,
`reader`, `search`, `bookmarks`, `document-info` and `settings` all follow that shape.

## Building

Gradle needs a JDK 17 or newer to run (the modules themselves target Java 11), plus Android SDK 37
and Xcode for the iOS side. Put your SDK path in `local.properties` — it is not committed.

```bash
./gradlew :androidApp:assembleDebug                  # Android debug APK
./gradlew :feature:reader:impl:testAndroidHostTest   # JVM unit tests for one module
./gradlew :core:data:iosSimulatorArm64Test           # the same tests on the iOS simulator target
```

For iOS, open `iosApp/iosApp.xcworkspace` in Xcode and run the `iosApp` scheme, or:

```bash
cd iosApp
xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Google Drive import needs a client ID per platform. The iOS one goes in
`iosApp/Configuration/Config.xcconfig`, which is gitignored — copy
`iosApp/Configuration/Config.xcconfig.template` to that path and fill in your own values first.

## Tests

Common logic is tested with `kotlin.test` in `commonTest` and runs on both the JVM and the iOS
simulator. Android-only pieces live in `androidHostTest`. The reader carries most of the coverage —
pagination, page-target math, spread geometry and the page-turn effect math are all pure functions
on purpose, so they can be tested without a device.

## Stack

Kotlin 2.4 · Compose Multiplatform 1.11 · Material 3 · Koin (annotations) · Room 3 with bundled
SQLite · DataStore over Okio · Coil 3 · Navigation 3 · kotlinx coroutines, serialization, datetime
and immutable collections. Android minSdk 24, compileSdk 37.
