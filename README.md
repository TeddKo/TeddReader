# TeddReader

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.11.1-4285F4)
![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS-lightgrey)
![minSdk](https://img.shields.io/badge/minSdk-24-3DDC84)

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
text never falls into the fold — see [Two-page spread](#two-page-spread).

**Page turns.** Ten of them, three of which follow your finger and settle where you release — see
[Page turns](#page-turns).

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

## Formats

| Format | What one page is | Parsed by |
| --- | --- | --- |
| TXT | measured text, reflowed on every type change | `TxtDocumentParser`, `TxtTextDecoder` |
| EPUB | measured text with inline structure, one spine item at a time | `EpubDocumentParser`, `EpubXhtmlParser`, `EpubCssEngine`, `EpubNavigationParser` |
| PDF | a page rendered by the platform PDF engine | `PdfDocumentParser`, `PdfMetadataReader` |
| CBZ | one entry of the archive, in sorted order | `ComicBookDocumentParser` |
| Images | one file of the folder you picked | `ImageDocumentParser`, `ImageDimensionSniffer` |

`DocumentFormatDetector` weighs three things, because none of them is reliable alone: the display
name, the MIME type the source reports, and — for PDF and raster images only — the file's leading
bytes. A cloud provider may report `application/octet-stream`, and a document opened from a content
URI may carry no extension at all. CBZ is deliberately matched by MIME type or a literal `.cbz`
extension and never by signature: every CBZ shares the ZIP signature with `.docx` and plain `.zip`,
so sniffing would misfile them.

## How a document becomes pages

1. **Import.** `DocumentImporter` copies the file into app storage — SAF and a Drive intent sender
   on Android, `UIDocumentPickerViewController` and the `GoogleDrivePicker.swift` bridge on iOS —
   and `DocumentFileSource` is the only thing that touches the filesystem afterwards.
2. **Parse into sections.** A format parser turns bytes into `ReaderSection`s: flat text plus
   `ReaderBlock` structure (paragraph, heading, quote, list item, table cell, image, …) expressed as
   character ranges over that text. Structure never owns the text, so search, bookmarks and reading
   position all index the same flat string.
3. **Break into pages.** `TextPageLayoutEngine` is the only place a page boundary is decided, and it
   holds one invariant: **a page never spans two sections.** An EPUB spine item is a document of its
   own, so a chapter title lands at the top of a new page instead of halfway down the last one — and
   any single section can be measured, stored, restored or appended without disturbing its
   neighbours.
4. **Measure for real.** Pagination runs against line boxes measured by Compose text layout, not
   estimated character counts, on `ReaderPageMeasureDispatcher`. Change the size or the weight and
   the book reflows.
5. **Remember the place.** Position is stored as a text anchor — a character offset into the
   section — never as a page number, so it survives every repagination.

## Page turns

Ten to choose from, all built on one Foundation pager.

| Effect | Driven by |
| --- | --- |
| None, Slide, Fade | pager offset |
| Scroll | a continuous list, with no page boundary at all |
| Fluid pager, Circle reveal | pager offset, with the reveal shape originating at your touch point |
| Movie carousel | pager offset, with a depth dim on the leaving page |
| Curl, 3D curl, Page flip | your finger, settling where you release |

In a two-page spread the pointer-driven three fold a **single leaf on the spine** rather than
sliding the whole sheet, and the leaf is drawn in one node that is allowed to cross the gutter — a
leaf split across the two pane nodes leaves the gutter empty and reads as a seam down the middle.

### The 3D curl

The sheet wraps a cylinder rather than rippling. Three regions along the leaf: flat from the spine
to the crease, wrapped over a cylinder of radius `r` for an arc of `PI * r`, then flat again running
out past the spine. The crease is placed so the sheet's tip travels linearly and crosses the spine
at exactly half progress, which is what makes the outgoing page cover the incoming one instead of
shrinking away. `r` ramps to zero at both ends of the turn, so both rest states are an identity
mapping and the leaf lands flat.

The surface normal rotates with the wrap angle, so columns past `PI / 2` are back-facing and get
drawn as the leaf's reverse — their destination runs opposite their source, which is why the verso
reads mirrored mid-turn and why its texture is sampled from the far edge. The cast shadow sits just
beyond the sheet's leading edge, which is the wrap edge for the front face and the tip for the back
face; anchoring both to the same edge makes the shadow look different depending on which way you
turn.

All of this is pure functions over `Float`s — crease travel, strip geometry, lighting, spread pane
widths, mirror parity — so the geometry is tested without a device, and the drawing code only
consumes what those functions return.

## Two-page spread

| Rule | Value |
| --- | --- |
| Two panes when | the shortest side is at least `600.dp`, or the fold is a book spine |
| Gutter | `max(16.dp, hinge thickness)` for a book spine, otherwise `16.dp` |
| Left pane share | derived from the fold position, clamped to `0.2 .. 0.8` |

A fold counts as a book spine only when it is both vertical and separating — a foldable that reports
a flat or horizontal hinge is not one. Both the pane count and the gutter gate on that, so a device
lying open flat never gets a spread it did not ask for, and the weight clamp keeps an off-centre
hinge from crushing a pane to nothing.

`rememberDisplayFold()` subscribes to the window's layout info and reports the first folding feature
in dp; everything downstream is a pure function of width, height and that fold.

## Architecture

Dependencies point one way only: `app` → `feature` → `core:ui` / `core:designsystem` →
`core:domain` → `core:common`, with `core:data` bound to `core:domain`'s interfaces and reachable
only through the DI graph. `core:common` has no platform or framework dependency at all.

Every feature is split `api` / `impl`, so no feature can reach another feature's internals — `home`,
`reader`, `search`, `bookmarks`, `document-info` and `settings` all follow that shape. That boundary
is enforced by the build rather than by review: a feature's build file carries no dependency block
at all, and the `teddreader.feature.impl` convention plugin wires exactly its own `api`,
`core:common`, `core:domain`, `core:designsystem` and `core:ui`. Only `app:reader` ever sees
`core:data`. Koin annotations build the object graph.

Shared code lives in `commonMain` and only drops into `androidMain` / `iosMain` where the platform
genuinely differs:

| `expect` declaration | Android | iOS |
| --- | --- | --- |
| `rememberDisplayFold()` | `WindowInfoTracker` folding features, converted to dp | `null` — no foldable iOS device exists yet |
| `PlatformPdfPageSurface` | `android.graphics.pdf.PdfRenderer` | PDFKit inside a `UIKitView` |
| `decodeLegacyKoreanText` | JVM charset decoding | `kCFStringEncodingDOSKorean` |
| `ReaderPageMeasureDispatcher` | `Dispatchers.Default` | `Dispatchers.Main` — text measurement is main-thread only |
| `foundationPagerRenderProfile` | 25 curl mesh columns, 1 shadow layer | 12 columns, 4 stacked layers |
| `drawFoundationPagerCurlShadow` | one native blur via `Paint.setShadowLayer` | translucent paths stacked into a soft edge |
| `rememberDocumentImporter` | SAF plus a Drive intent sender | `UIDocumentPickerViewController` plus the Swift Drive bridge |

## Repository layout

| Path | What lives there |
| --- | --- |
| `androidApp/` | Android entry point and manifest |
| `iosApp/` | Xcode project, SwiftUI entry point, Google Drive picker bridge |
| `baselineprofile/` | Macrobenchmark that generates the Android baseline profile |
| `app/reader/` | Application composition: DI graph, navigation host, theme wiring, importers |
| `core/common/` | Models and pure logic with no platform or framework dependency |
| `core/domain/` | Repository interfaces and use cases |
| `core/data/` | Repository implementations, format parsers, pagination engine |
| `core/room/`, `core/datastore/` | Room database and DataStore preferences |
| `core/designsystem/` | Theme, colors, typography, spacing, icons |
| `core/ui/` | Shared composables used by more than one feature |
| `feature/<name>/api/` | The public surface a feature exposes to others |
| `feature/<name>/impl/` | Screens, view models and components of that feature |
| `build-logic/` | Convention plugins; module build files are one `id(...)` line |

## Tests

Common logic is tested with `kotlin.test` in `commonTest` and runs on both the JVM and the iOS
simulator; Android-only pieces live in `androidHostTest`. Around 800 test cases, weighted towards the
parts where a wrong number is invisible until you look at a screen:

| Module | Cases | What they cover |
| --- | --- | --- |
| `core/data` | 316 | format parsers, EPUB CSS and navigation, pagination and section distribution |
| `feature/reader/impl` | 228 | page-target math, spread geometry, page-turn effect math, view model state |
| `core/common` | 109 | models, block structure, reading position, validation |
| `core/ui` | 51 | shared reader composable logic |
| `core/domain` | 24 | use cases against fake repositories |
| `app/reader`, `feature/home/impl` | 41 | navigation and library list behaviour |
| others | 27 | datastore, room mappers, design tokens, search, settings, document info |

Pagination, page-target math, spread geometry and the page-turn effect math are pure functions on
purpose, so they can be tested without a device.

## Building

Gradle needs a JDK 17 or newer to run (the modules themselves target Java 11), plus Android SDK 37
and Xcode for the iOS side. Put your SDK path in `local.properties` — it is not committed.

```bash
./gradlew :androidApp:assembleDebug                  # Android debug APK
./gradlew :feature:reader:impl:testAndroidHostTest   # JVM unit tests for one module
./gradlew :core:data:iosSimulatorArm64Test           # the same tests on the iOS simulator target
./gradlew :androidApp:generateBaselineProfile        # regenerate the baseline profile on a device
```

For iOS, open `iosApp/iosApp.xcworkspace` in Xcode and run the `iosApp` scheme, or:

```bash
cd iosApp
xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Google Drive import needs a client ID per platform. The iOS one goes in
`iosApp/Configuration/Config.xcconfig`, which is gitignored — copy
`iosApp/Configuration/Config.xcconfig.template` to that path and fill in your own values first.

## Stack

| Area | What is used |
| --- | --- |
| Language, UI | Kotlin 2.4.0, Compose Multiplatform 1.11.1, Material 3 |
| Navigation, DI | Navigation 3, Koin 4.2 with annotations (KSP) |
| Storage | Room 3 with bundled SQLite, DataStore over Okio |
| Async, data | kotlinx coroutines, serialization, datetime, immutable collections |
| Images, logging | Coil 3, Kermit |
| Platform | androidx.window for folding features; Android minSdk 24, compileSdk 37, AGP 9.2 |
