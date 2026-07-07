# Reader Platform Import & APK Reference Hardening Plan

**For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` task-by-task. Keep checkboxes updated.

**Goal:** Harden TXT/PDF/EPUB import/open flows on Android and iOS, then apply safe APK reference findings as prioritized reader-service work without copying APK code, assets, or text.

**Architecture:** Keep Android/iOS apps as platform shells. `:app:reader` owns platform import/open entry points and and calls `OpenDocumentUseCase`; repository interfaces stay in `:core:domain`; implementations stay in `:core:data`; feature impl modules do not depend on `:core:data`. Advanced reader UX remains Compose Multiplatform state-driven UI with `data class` UiState.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Android SAF/intent filters, iOS UIKit document picker/PDFKit, Nav3 typed routes, Koin, Room, DataStore, kotlinx serialization/datetime/immutable.

---

## APK Reference Analysis Summary

**Artifact analyzed:** `/Users/kominhyuk/Downloads/reader.apk`

**Static-analysis tools used:**

- `jadx 1.5.5` full decompile to `build/apk-analysis/reader/jadx`.
- `apktool 3.0.2` resource/manifest decode to `build/apk-analysis/reader/apktool`.
- Android SDK `aapt` and `apkanalyzer` for manifest/package inspection.
- SHA-256: `72d3ebca08e304770769afaef4d496c77cd92aeede9f0e3d4f49785efe13febd`.

**Important correction:** JADX reported 51 decompile errors, but produced 13,766 Java source files. Treat this as broad static evidence, not a source-of-truth implementation. No APK code or resource should be copied.

**High-signal APK evidence:**

- Manifest exposes `VIEW` file open, `SEND` text, `PROCESS_TEXT`, `TTS_SERVICE`, media-button receiver, app widget, file provider, Dropbox auth, foreground media playback, and many MIME types.
- File formats visible in manifest/classes/resources: TXT, PDF, EPUB, DJVU, CBR/CBZ, MOBI/AZW/AZW3, FB2, DOCX, RTF, CHM, MHTML, ODT, images.
- Reader surface evidence: `ActivityTxt`, `txt_top.xml`, `txt_bottom.xml`, `txt_font.xml`, `pref_theme.xml`, `func_search.xml`, `chapters.xml`, `book_info.xml`, `bookmarks_options.xml`.
- PDF evidence: `com.radaee.*`, `PDFViewHorz`, `PDFViewVert`, `PDFViewCurl`, `PDFViewReflow`, `PDFThumbView`, `PDFVFinder`, `pdf_password.xml`, `pdf_nav.xml`, `pref_pdf.xml`, annotation drawables.
- Library evidence: `SelectFileAct`, `main_files.xml`, `main_shelf.xml`, `shelf_filter.xml`, `PrefShelf`, `PrefThumbnails`, import folder resources.
- Reading enhancement evidence: `BookTtsService`, `tts_panel.xml`, `tts_options.xml`, dictionary layouts, statistics/calendar layouts, sync/cloud classes.

---

## Current TeddReader Gap Snapshot

- Android in-app document picker exists, but Android manifest only supports launcher entry; external `ACTION_VIEW` / share-open is missing.
- iOS document picker is a no-op and iOS PDF surface is a placeholder.
- `DocumentRepositoryImpl.importDocument()` treats `DocumentFormat.UNKNOWN` like TXT, so unsupported files can be imported incorrectly.
- `PdfDocumentParser` estimates page count by scanning decoded PDF bytes; this is acceptable only as a temporary fallback and should not drive long-term PDF UX.
- Core business features already exist for recent documents, reader settings, progress restore, search, bookmarks, document info, statistics, and Android PDF bitmap rendering.
- Existing UI already has menus, bottom sheets, switches, sliders, radio rows, loading indicators, and reader option sheets; next work should connect missing platform capabilities before adding new UI surfaces.

---

## Non-Goals

- Do not copy APK source, resources, strings, layout XML, or visual assets.
- Do not add broad reader formats in this plan beyond TXT/PDF/EPUB.
- Do not add cloud sync, widgets, paid-feature logic, DRM, or biometric locking in this plan.
- Do not add feature module dependencies on `:core:data`.

---

## Phase 1: Supported Format Boundary

**Result:** TeddReader imports only supported formats, fails unsupported formats cleanly, and exposes one small supported-format contract reused by picker/open flows.

**Files:**

- Create: `core/common/src/commonMain/kotlin/com/tedd/teddreader/core/common/model/SupportedDocumentTypes.kt`
- Modify: `core/data/src/commonMain/kotlin/com/tedd/teddreader/core/data/parser/DocumentFormatDetector.kt`
- Modify: `core/data/src/commonMain/kotlin/com/tedd/teddreader/core/data/repository/DocumentRepositoryImpl.kt`
- Modify: `core/data/src/commonTest/kotlin/com/tedd/teddreader/core/data/repository/DocumentRepositoryImplTest.kt`
- Modify: `core/data/src/commonTest/kotlin/com/tedd/teddreader/core/data/parser/DocumentFormatDetectorTest.kt`

**Steps:**

- [x] Add top-level supported values in `SupportedDocumentTypes.kt`: formats `TXT`, `PDF`, `EPUB`; MIME aliases `text/plain`, `application/pdf`, `application/epub`, `application/epub+zip`; extensions `txt`, `pdf`, `epub`. Use top-level immutable values, not companion objects.
- [x] Add detector tests for TXT by MIME and extension, PDF by MIME/header/extension, EPUB by MIME/extension, and ZIP/DOCX/MOBI as `UNKNOWN`.
- [x] Add repository test that importing `UNKNOWN` throws `IllegalArgumentException` before DAO writes.
- [x] Change `DocumentRepositoryImpl.importDocument()` so `UNKNOWN` throws `Unsupported document format: <displayName>` before parsing or persistence.
- [x] Narrow Android picker MIME list from broad `text/*` to supported MIME aliases unless a supported extension can still be inferred from `OpenableColumns.DISPLAY_NAME`.

**Verify:**

```bash
./gradlew :core:common:allTests :core:data:allTests
```

Expected: `BUILD SUCCESSFUL`.

---

## Phase 2: Android External Open/Share Import

**Result:** Android can open supported files from file managers, downloads, and share sheets, matching the APK's open-file behavior at the supported-format level.

**Files:**

- Modify: `androidApp/src/main/AndroidManifest.xml`
- Modify: `androidApp/src/main/kotlin/com/tedd/teddreader/MainActivity.kt`
- Modify: `app/reader/src/commonMain/kotlin/com/tedd/teddreader/app/reader/TeddReaderApp.kt`
- Modify: `app/reader/src/commonMain/kotlin/com/tedd/teddreader/app/reader/navigation/ReaderNavHost.kt`
- Create: `app/reader/src/androidMain/kotlin/com/tedd/teddreader/app/reader/importer/AndroidExternalDocumentIntent.kt`

**Steps:**

- [x] Add `ACTION_VIEW` intent filters for `text/plain`, `application/pdf`, `application/epub`, `application/epub+zip` with `DEFAULT` and `BROWSABLE` categories.
- [x] Add `ACTION_SEND` for `text/plain` and URI payloads only when Android gives a readable `EXTRA_STREAM`.
- [x] In `MainActivity`, parse `intent` and `onNewIntent()` into a small `AndroidExternalDocumentIntent` model containing `uri`, `displayName`, `mimeType`, `sizeBytes`, and `grantFlags`.
- [x] Pass that model into `TeddReaderApp` as an initial import request; keep normal launch path unchanged.
- [x] Reuse `OpenDocumentUseCase`; persist Android read permission when grant flags allow it.
- [x] Route successful external imports to `ReaderRoute(documentId.value)`; show a clear unsupported-format error on Home when the use case fails.

**Verify:**

```bash
./gradlew :androidApp:assembleDebug
adb shell am start -a android.intent.action.VIEW -d 'content://example/unsupported.zip' -t application/zip com.tedd.teddreader/.MainActivity
```

Expected: build succeeds; unsupported intent does not create a document and surfaces an error. Use a real TXT/PDF/EPUB URI on device/emulator for success-path manual verification.

---

## Phase 3: iOS Document Picker and Stable File Access

**Result:** iOS can import TXT/PDF/EPUB from Files and keep a stable app-container copy for reopening/rendering after app restart.

**Files:**

- Modify: `app/reader/src/iosMain/kotlin/com/tedd/teddreader/app/reader/importer/DocumentImporter.ios.kt`
- Modify: `core/data/src/iosMain/kotlin/com/tedd/teddreader/core/data/storage/IosDocumentFileSource.kt`
- Modify: `app/reader/src/iosMain/kotlin/com/tedd/teddreader/app/reader/di/PlatformReaderModule.ios.kt`

**Steps:**

- [x] Replace the no-op importer with `UIDocumentPickerViewController` using UTTypes for plain text, PDF, and EPUB.
- [x] Use security-scoped resource access while reading selected files.
- [x] Reuse the existing `IosDocumentFileSource.copyIntoAppContainer()` path so imported files reopen from the app sandbox.
- [x] Build `DocumentImportSource` from the sandbox `DocumentLocation`, not the temporary picker URL.
- [x] Keep callback contract unchanged: `onImported(DocumentId)` on success, `onError(String)` on failure.

**Verify:**

```bash
./gradlew :app:reader:linkDebugFrameworkIosSimulatorArm64
```

Expected: iOS framework link succeeds. Manual simulator check: import one TXT/PDF/EPUB from Files, close app, reopen, and confirm reader starts from stored document metadata.

---

## Phase 4: PDF Platform Minimum Parity

**Result:** PDF behavior no longer depends on binary text scanning or iOS placeholder rendering.

**Files:**

- Modify: `core/data/src/commonMain/kotlin/com/tedd/teddreader/core/data/parser/PdfDocumentParser.kt`
- Create: `core/data/src/commonMain/kotlin/com/tedd/teddreader/core/data/parser/PdfMetadataReader.kt`
- Create: `core/data/src/androidMain/kotlin/com/tedd/teddreader/core/data/parser/AndroidPdfMetadataReader.kt`
- Create: `core/data/src/iosMain/kotlin/com/tedd/teddreader/core/data/parser/IosPdfMetadataReader.kt`
- Modify: `feature/reader/impl/src/iosMain/kotlin/com/tedd/teddreader/feature/reader/impl/pdf/PdfPageSurface.ios.kt`
- Modify: `feature/reader/impl/src/androidMain/kotlin/com/tedd/teddreader/feature/reader/impl/pdf/PdfPageSurface.android.kt`

**Steps:**

- [x] Add a tiny `PdfMetadataReader` with `pageCount(location, bytes): Int`; keep it in `:core:data` because it is parser infrastructure, not domain policy.
- [x] Android implementation uses platform `PdfRenderer` against URI/file descriptor or a temporary file from bytes.
- [x] iOS implementation uses PDFKit `PDFDocument` against the app-container file URL or bytes.
- [x] Keep a fallback page count of `1` only when platform metadata cannot open the PDF; surface renderer error in UI.
- [x] Replace iOS placeholder with PDFKit page rendering for current page.
- [x] Add pinch zoom and rotation only as reader UI state fields if Android and iOS rendering both support them through native APIs.

**Verify:**

```bash
./gradlew :core:data:allTests :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64
```

Expected: common tests and both platform builds succeed. Manual check: same PDF reports same page count on Android and iOS.

---

## Phase 5: APK-Inspired Reader UX Enhancements Already Supported by Current Architecture

**Result:** Add high-value reader features visible in APK analysis using existing Compose components and repositories.

**Files:**

- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderMenuAction.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderOptionSheet.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderUiState.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderViewModel.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderScreen.kt`
- Modify: `feature/home/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/home/impl/HomeScreen.kt`

**Steps:**

- [x] Add a `TableOfContents` reader action backed by existing `ReaderDocument.sections` titles for TXT/EPUB and by PDF page numbers for PDF.
- [x] Add a `GoToPage` bottom sheet with bounded numeric input; route to `ReaderViewModel.moveToPage()`.
- [x] Add a `Brightness` reader option using an in-app dim overlay first; platform window brightness can be added only after the overlay proves insufficient.
- [x] Add Home sorting/filtering by recent opened date, title, and format using existing `DocumentMetadata`; do not create a full file manager.
- [x] Add import error/empty states with existing `TeddEmptyState`, `TeddButton`, and loading components.

**Verify:**

```bash
./gradlew :feature:reader:impl:allTests :feature:home:impl:allTests :androidApp:assembleDebug
```

Expected: tests and Android build succeed. Manual check: menu opens bottom sheets, page jumps stay within bounds, Home sorting changes list order.

---

## Phase 6: Deliberate Backlog from APK Analysis

These are confirmed gaps from APK analysis, but they are outside the import hardening scope. Create separate plans when starting each item.

1. **TTS reader panel:** APK has service/panel/options evidence. TeddReader should use platform TTS expect/actual plus foreground service on Android.
2. **Annotations/highlights/notes:** APK has bookmark/highlight/note/PDF annotation evidence. TeddReader needs a new domain model before UI work.
3. **Dictionary/process-text integration:** APK supports search/process text. TeddReader needs selected-text state first.
4. **Thumbnails/page grid:** APK has PDF thumbnail/grid evidence. TeddReader should add this after stable PDF rendering.
5. **Extended formats:** MOBI/FB2/DOCX/RTF/CHM/DJVU/CBR/CBZ require separate parser decisions and should not be mixed with TXT/PDF/EPUB hardening.
6. **Cloud sync/widgets:** APK has Dropbox/WebDAV/GDrive/widget evidence. TeddReader needs local import/reader stability before network/account surfaces.

---

## Acceptance Criteria

- Unsupported files cannot be persisted as TXT.
- Android supports in-app picker and external open/share for TXT/PDF/EPUB only.
- iOS document picker imports TXT/PDF/EPUB and stores stable app-container file locations.
- PDF page count and iOS PDF rendering use platform PDF APIs instead of placeholder/binary text scan as primary behavior.
- New reader UX uses existing `core:ui` Material3 components and `ReaderUiState` data classes.
- No feature impl module depends on `:core:data`.
- No APK code, assets, strings, or XML are copied.

## Final Verification Commands

```bash
./gradlew :core:common:allTests :core:data:allTests
./gradlew :feature:reader:impl:allTests :feature:home:impl:allTests
./gradlew :androidApp:assembleDebug
./gradlew :app:reader:linkDebugFrameworkIosSimulatorArm64
```

Expected: every command completes with `BUILD SUCCESSFUL`.
