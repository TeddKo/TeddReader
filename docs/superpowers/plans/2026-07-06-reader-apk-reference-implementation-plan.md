# Reader APK Reference Implementation Plan

**For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans implement plan task-by-task. Steps use checkbox (`- [ ]`) syntax tracking.

**Goal:** `/Users/kominhyuk/Downloads/reader.apk`를 기능/UX 벤치마크로만 분석해 TeddReader의 Compose 화면 구현 플랜을 만든다.

**Architecture:** APK 구현을 복제하지 않는다. APK에서 확인한 manifest, resource, asset, class-name level evidence로 기능 우선순위를 뽑고, TeddReader의 기존 `:core:*` business foundation 위에 `:feature:<screen>:api` / `:feature:<screen>:impl` 화면 단위 모듈을 추가한다. Feature는 `:core:domain`, `:core:ui`, `:core:designsystem`만 직접 의존한다.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3, Nav3 typed routes, Koin annotation, Room KMP, DataStore KMP.

---

## 1. APK Static Analysis Evidence

Analyzed file:

```text
/Users/kominhyuk/Downloads/reader.apk
sha256: 72d3ebca08e304770769afaef4d496c77cd92aeede9f0e3d4f49785efe13febd
package: com.flyersoft.moonreaderp
versionName: 10.6
minSdk: 21
targetSdk: 36
```

Commands used:

```bash
$ANDROID_HOME/build-tools/36.0.0/aapt dump badging /Users/kominhyuk/Downloads/reader.apk
$ANDROID_HOME/build-tools/36.0.0/aapt dump permissions /Users/kominhyuk/Downloads/reader.apk
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer manifest print /Users/kominhyuk/Downloads/reader.apk
zipinfo -1 /Users/kominhyuk/Downloads/reader.apk
strings classes*.dex | grep selected reader keywords
```

Safe scope:

- [x] Manifest / permission / component names
- [x] Resource and asset filenames
- [x] Package/class names and keyword strings
- [ ] No implementation copy
- [ ] No credential/secret file content inspection
- [ ] No license, patch, or bypass analysis

## 2. Feature Signals From APK

### Supported input surface

Manifest MIME filters indicate broad document support:

- `text/*`, `image/*`
- `application/pdf`
- `application/epub`, `application/epub+zip`
- `application/mobi`, `application/x-mobipocket-ebook`, `application/azw`, `application/azw3`
- `application/djvu`
- comic archives: CBR/CBZ variants
- FB2 variants
- DOCX, RTF, CHM, ODT, `message/rfc822`

TeddReader MVP keeps current `TXT/PDF/EPUB` first. Non-MVP formats should import as unsupported states with clear copy until real parsers exist.

### Main app surfaces

Manifest/components show these app-level surfaces:

- `ActivityMain`: library/home shell
- `ActivityTxt`: text reader screen
- `SelectFileAct`: file picker/import UI
- `BookTtsService`: foreground TTS/media playback
- `BookDownloadService`: network/catalog downloads
- `BookViewProvider` / widget provider/service: recent reading widget
- `FileProvider`: external file/share flow
- Dropbox auth activity: cloud sync integration

TeddReader should implement only local reader surfaces first: home/library, reader, search, settings, bookmarks, document info. TTS/cloud/widget/catalog are later phases.

### UI/resource signals

Resource/layout filenames show useful UX patterns:

- Library: `main_shelf`, `main_files`, `recent_files_item`, `shelf_grid_item`, `shelf_list_item`, `shelf_filter`, `shelf_import`
- Reader bars: `txt_top`, `txt_bottom`, `bar_seek`, `bar_find`, `bar_thumb_view`, `statusbar_setting`
- Reader settings: `txt_font`, `pref_theme`, `pref_visual`, `pref_control`, `pref_misc`, `auto_theme`, `image_options`, `edge_options`
- Material-style option UI: `mtrl_search_view`, `mtrl_search_bar`, `bottom_bar_sort`, `merged_popup`, `pref_bottom`
- Bookmarks/annotations: `bookmark_row`, `main_bookmarks`, `bookmarks_book_item`, `bookmarks_annot_item`, `dlg_note`, `highlight_pop`, `note_images`
- Search: `txt_search`, `search_result`, `search_options`, search engine icons
- PDF: `pdf_layout`, `pdf_nav`, `pdf_pages`, `pdf_password`, `pdf_fragment`, `pdf_gllayout`, annotation icons
- Stats: `statistics_book`, `statistics_calendar`, `statistics_year`
- Auto-scroll/TTS: `tips_autoscroll.png`, `tts_panel`, `tts_options`, `tts_notification`, `tts_filter`, `speed_read`
- Sync/catalog: `sync_lay`, `dropbox_options`, `webdav_options`, `add_catalog`, network XML assets

### Asset strategy signals

APK bundles:

- `assets/background/*`: reader background textures/images
- `assets/themes/*.xml`: named day/night/pro themes
- `assets/fonts/*.ttf`: bundled reading fonts
- `assets/hyphenation/*`: language hyphenation dictionaries
- `assets/network/*.xml`: OPDS/catalog presets
- native libs: PDF/DJVU/MOBI/image pipeline

TeddReader should not bundle copied assets. Use generated Compose tokens first. Add user-selected background image later. Add bundled fonts only if open-license assets are explicitly chosen.

## 3. Current TeddReader Baseline

Already present:

- `:core:common`: document/reader models, style, page mode, animation, stats models
- `:core:data`: TXT/EPUB/PDF import foundations, parser, pagination, repositories
- `:core:domain`: repository contracts and usecases
- `:core:datastore`: reader settings persistence
- `:core:room`: document/progress/bookmark/session/search schema
- `:core:designsystem`: theme/color/typography/spacing tokens
- `:core:ui`: common dumb components, reader page/controls/progress components
- `:feature:home:api` / `:feature:home:impl`: placeholder home foundation
- `:app:reader`: app assembly shell

Missing for APK-inspired reader UX:

- Real library/home UI bound to repository data
- Platform file picker/import action
- Reader screen overlay bars and paging controls
- Material3 menus for reader actions
- Material3 bottom sheets for actual option editing
- Reusable loading/menu/switch/checkbox/slider/radio option components
- Search overlay and results jump
- Bookmark/annotation UI
- Document info/statistics UI
- PDF rendering UI beyond metadata import
- Auto-scroll UI/runtime loop

## 4. Target Feature Modules

Add screen modules incrementally:

```text
feature/reader/api
feature/reader/impl
feature/search/api
feature/search/impl
feature/settings/api
feature/settings/impl
feature/bookmarks/api
feature/bookmarks/impl
feature/document-info/api
feature/document-info/impl
```

Optional later:

```text
feature/catalog/api|impl
feature/sync/api|impl
feature/tts/api|impl
feature/widget/android
```

Dependency rule:

```text
feature:*:impl -> feature:*:api, core:domain, core:ui, core:designsystem
feature:*:api -> core:common only if route args need common ids/types
feature modules never depend on core:data directly
app:reader wires navigation and DI bindings
```

## 5. Material3 Reader Option UX Decision

### Decision

Use **menus for choosing an option category** and **ModalBottomSheet for editing the selected option**.

```text
Reader screen
 ├─ Top bar actions: search / bookmark / document info / more
 ├─ Bottom controls: progress / font / theme / page mode / auto-scroll
 └─ Active sheet
      ├─ ViewOptions
      ├─ FontOptions
      ├─ ThemeOptions
      ├─ PageTurnOptions
      ├─ AutoScrollOptions
      └─ ControlOptions
```

Why:

- Menus are fast for selecting where to go.
- Bottom sheets are better for sliders, switches, radio groups, previews, and multi-control editing.
- This matches reader app UX without copying XML layouts.
- This keeps `core/ui` generic and feature state inside feature modules.

### Explicit non-goals

- No custom menu framework.
- No option registry DSL until settings exceed the first reader screen needs.
- No copied APK icons/assets.
- No global settings screen before reader-specific sheets work.

## 6. Core UI Material3 Component Plan

Add thin wrappers only. They should not store state, know repositories, or know reader business rules.

### Phase 2.5: Material3 common components

Files:

- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddLoadingIndicator.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddModalBottomSheet.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddDropdownMenu.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddSwitchRow.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddCheckboxRow.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddSliderRow.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddRadioRow.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddOptionGroup.kt`

Tasks:

- [ ] `TeddLoadingIndicator`: full-screen and inline loading variants.
- [ ] `TeddModalBottomSheet`: title, optional description, dismiss, content slot.
- [ ] `TeddDropdownMenu`: Material3 `DropdownMenu` + standardized `DropdownMenuItem` wrapper.
- [ ] `TeddSwitchRow`: title, optional description, checked, enabled, onCheckedChange.
- [ ] `TeddCheckboxRow`: title, optional description, checked, enabled, onCheckedChange.
- [ ] `TeddSliderRow`: title, current value label, range, steps, onValueChange.
- [ ] `TeddRadioRow`: single radio row for option groups.
- [ ] `TeddOptionGroup`: section title + list content slot with consistent spacing.
- [ ] Add previews for each component.

Verification:

```bash
./gradlew :core:ui:allTests :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64
```

Acceptance:

- [ ] All components compile in commonMain.
- [ ] Every component has preview.
- [ ] No component depends on `:core:data`, ViewModel, or repository.
- [ ] No stateful option registry is introduced.

## 7. Implementation Phases

### Phase 0: Keep reference ethical and bounded

- [ ] Save this plan as the only APK-derived artifact in repo.
- [ ] Do not copy APK code/resources/assets.
- [ ] Do not inspect or commit sensitive asset contents such as `credentials.json`.
- [ ] Treat APK evidence as feature prioritization, not source material.

Verification:

```bash
grep -R "reader.apk\|Moon+ Reader" core feature app androidApp iosApp || true
```

Expected: no copied implementation/resource references in source modules.

### Phase 1: App navigation shell with typed routes

Files:

- Modify: `app/reader/src/commonMain/kotlin/com/tedd/teddreader/app/reader/TeddReaderApp.kt`
- Create: `app/reader/src/commonMain/kotlin/com/tedd/teddreader/app/reader/navigation/ReaderNavHost.kt`
- Create: route contracts in each `feature:<screen>:api`

Tasks:

- [ ] Add typed routes for Home, Reader(documentId), Search(documentId), Settings, Bookmarks(documentId), DocumentInfo(documentId).
- [ ] Keep route models kotlinx-serializable.
- [ ] Wire placeholder destinations first.

Verification:

```bash
./gradlew :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64
```

### Phase 2: Home/library screen MVP

APK reference: `main_shelf`, `main_files`, `recent_files_item`, `shelf_grid_item`, `shelf_filter`, `SelectFileAct`.

Files:

- Modify: `feature/home/impl/src/commonMain/.../HomeScreen.kt`
- Modify: `feature/home/impl/src/commonMain/.../HomeUiState.kt`
- Create: `feature/home/impl/src/commonMain/.../HomeViewModel.kt`
- Create: `feature/home/impl/src/commonMain/.../component/DocumentListItem.kt`
- Create platform file picker bridge later in `androidMain` / `iosMain`

Tasks:

- [ ] Replace greeting placeholder with library state: recent documents, empty state, open-file CTA.
- [ ] Use `DocumentRepository.observeRecentDocuments()` via domain contract.
- [ ] Add loading state via `TeddLoadingIndicator` for import/refresh.
- [ ] Add grid/list toggle only if cheap; default list is enough for MVP.
- [ ] Open imported document route to Reader.
- [ ] Show unsupported format message for non TXT/PDF/EPUB.

Verification:

```bash
./gradlew :feature:home:impl:allTests :androidApp:assembleDebug
```

### Phase 3: Reader screen MVP

APK reference: `ActivityTxt`, `txt_top`, `txt_bottom`, `bar_seek`, `bar_find`, `page_hori`, `page_vert`.

Files:

- Create: `feature/reader/api/src/commonMain/.../ReaderRoute.kt`
- Create: `feature/reader/impl/src/commonMain/.../ReaderScreen.kt`
- Create: `feature/reader/impl/src/commonMain/.../ReaderUiState.kt`
- Create: `feature/reader/impl/src/commonMain/.../ReaderViewModel.kt`
- Reuse: `core/ui/reader/ReaderPageSurface.kt`, `ReaderControls.kt`, `ReaderProgressBar.kt`

Tasks:

- [ ] `ReaderUiState` data class contains document title, page text, `PageIndex`, `ReaderStyle`, controls visibility, loading/error.
- [ ] Restore last reading progress on open.
- [ ] Render current `PageWindow` from core pagination for TXT/EPUB.
- [ ] Add top/bottom overlay bars: back, title, bookmark, search, settings, page label/progress.
- [ ] Add tap center toggles controls.
- [ ] Add basic horizontal/vertical page navigation buttons/gestures. Continuous scroll can be separate subphase.
- [ ] Save progress on page change.

Verification:

```bash
./gradlew :feature:reader:impl:allTests :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64
```

### Phase 3.5: Reader Material3 menus and active sheet state

APK reference: `txt_top`, `txt_bottom`, `bar_cmd`, `bar_find`, `bar_seek`, `pref_bottom`, `bottom_bar_sort`, `merged_popup`.

Files:

- Modify: `feature/reader/impl/src/commonMain/.../ReaderUiState.kt`
- Modify: `feature/reader/impl/src/commonMain/.../ReaderScreen.kt`
- Create: `feature/reader/impl/src/commonMain/.../ReaderMenuAction.kt`
- Create: `feature/reader/impl/src/commonMain/.../ReaderOptionSheet.kt`
- Create: `feature/reader/impl/src/commonMain/.../component/ReaderActionMenu.kt`
- Create: `feature/reader/impl/src/commonMain/.../component/ReaderBottomActionBar.kt`

Model:

```kotlin
enum class ReaderMenuAction {
    Search,
    Bookmark,
    ViewOptions,
    FontOptions,
    ThemeOptions,
    PageTurnOptions,
    AutoScrollOptions,
    ControlOptions,
    DocumentInfo,
}

sealed interface ReaderOptionSheet {
    data object View : ReaderOptionSheet
    data object Font : ReaderOptionSheet
    data object Theme : ReaderOptionSheet
    data object PageTurn : ReaderOptionSheet
    data object AutoScroll : ReaderOptionSheet
    data object Controls : ReaderOptionSheet
}
```

Tasks:

- [ ] Add `activeSheet: ReaderOptionSheet? = null` to `ReaderUiState`.
- [ ] Top action menu uses `TeddDropdownMenu` for Search, Bookmarks, Document info, Reader settings.
- [ ] Bottom action bar uses direct buttons for progress/font/theme/page mode/auto-scroll.
- [ ] Menu item click only changes `activeSheet` or navigates. It does not mutate settings directly.
- [ ] Back press or scrim dismiss clears `activeSheet`.

Verification:

```bash
./gradlew :feature:reader:impl:allTests :androidApp:assembleDebug
```

Acceptance:

- [ ] Menus select categories only.
- [ ] Bottom sheets perform actual option edits.
- [ ] `ReaderUiState` remains a data class.
- [ ] No `core:data` dependency in reader feature.

### Phase 4: Reader visual settings bottom sheets

APK reference: `txt_font`, `pref_theme`, `pref_visual`, `pref_control`, `auto_theme`, `image_options`.

Files:
- Create: `feature/settings/api/src/commonMain/.../SettingsRoute.kt`
- Create: `feature/settings/impl/src/commonMain/.../ReaderSettingsSheet.kt`
- Create: `feature/settings/impl/src/commonMain/.../ReaderSettingsUiState.kt`
- Create: `feature/settings/impl/src/commonMain/.../ReaderSettingsViewModel.kt`
- Or keep reader-only sheets in `feature/reader/impl/.../sheet/*` first, then extract when a global settings screen exists.

#### Sheet: View options

Controls:
- [ ] Theme mode: system/light/dark/sepia/custom via radio rows.
- [ ] Keep screen on via switch.
- [ ] Fullscreen reader via switch.
- [ ] Status/progress display via switch.
- [ ] Background image row opens later picker; MVP show disabled row.

#### Sheet: Font options

Controls:
- [ ] Font size slider.
- [ ] Line height slider.
- [ ] Font family radio: Sans / Serif / Mono.
- [ ] Preview text block using `ReaderPageSurface` style.

#### Sheet: Theme options

Controls:
- [ ] Light/dark/sepia preset cards or radio rows.
- [ ] Text color/background color rows; MVP can use preset choices, full color picker later.
- [ ] Save custom theme later, not MVP.

#### Sheet: Page turn options

Controls:
- [ ] Page mode radio: horizontal / vertical / continuous.
- [ ] Animation radio: none / slide / fade / scroll.
- [ ] Apply immediately and persist through `ReaderSettingsRepository`.

#### Sheet: Auto-scroll options

Controls:
- [ ] Enabled switch.
- [ ] Mode radio: pixel / page.
- [ ] Speed slider.
- [ ] Start/stop button.
- [ ] Pause auto-scroll on user gesture, app background, or document end.

#### Sheet: Control options

Controls:
- [ ] Use `TeddModalBottomSheet`, `TeddSwitchRow`, `TeddCheckboxRow`, `TeddSliderRow`, `TeddRadioRow`, `TeddOptionGroup`.
- [ ] Persist reader style/page mode/animation/auto-scroll through `ReaderSettingsRepository`.
- [ ] Do not add bundled copyrighted fonts/backgrounds.
- [ ] Add preview states for light/dark/sepia and each sheet.

Verification:

```bash
./gradlew :feature:settings:impl:allTests :core:datastore:allTests :androidApp:assembleDebug
```

Acceptance:
- [ ] Settings are edited in bottom sheets, not dropdown menus.
- [ ] Every async save has loading/disabled handling.
- [ ] Reader page preview updates current sheet state.
### Phase 5: Search overlay

APK reference: `txt_search`, `bar_find`, `search_result`, `search_options`, `mtrl_search_view`.

Files:
- Create: `feature/search/api/src/commonMain/.../SearchRoute.kt`
- Create: `feature/search/impl/src/commonMain/.../SearchScreen.kt`
- Create: `feature/search/impl/src/commonMain/.../SearchUiState.kt`
- Create: `feature/search/impl/src/commonMain/.../SearchViewModel.kt`

Tasks:
- [ ] Search opens from reader top action menu.
- [ ] Query `FindInDocumentUseCase`.
- [ ] Show loading via `TeddLoadingIndicator` while indexing/searching.
- [ ] Show result count, snippets, section title.
- [ ] Result click returns `ReaderLocation` to Reader screen.
- [ ] PDF unsupported text search shows honest unavailable state.
- [ ] Search options menu can hold case sensitivity/whole word later; MVP skips actual filters.

Verification:

```bash
./gradlew :feature:search:impl:allTests :core:data:allTests
```

### Phase 6: Bookmarks notes MVP

APK reference: `bookmark_row`, `main_bookmarks`, `bookmarks_book_item`, `dlg_note`, `highlight_pop`, `note_images`.

Files:
- Create: `feature/bookmarks/api/src/commonMain/.../BookmarksRoute.kt`
- Create: `feature/bookmarks/impl/src/commonMain/.../BookmarksScreen.kt`
- Create: `feature/bookmarks/impl/src/commonMain/.../BookmarksUiState.kt`
- Create: `feature/bookmarks/impl/src/commonMain/.../BookmarksViewModel.kt`

Tasks:
- [ ] Reader bookmark action available from top bar action menu.
- [ ] Reader bookmark toggle saves/removes bookmark at current `ReaderLocation`.
- [ ] Bookmarks screen lists document bookmarks.
- [ ] Bookmark click jumps to location.
- [ ] Note editing opens `TeddModalBottomSheet` with text field save/delete actions.
- [ ] Notes are plain text only for MVP.
- [ ] Highlights/annotation shapes/images are deferred.

Verification:

```bash
./gradlew :feature:bookmarks:impl:allTests :core:room:allTests
```

### Phase 7: Document info reading statistics

APK reference: `book_info`, `statistics_book`, `statistics_calendar`, `statistics_year`.

Files:
- Create: `feature/document-info/api/src/commonMain/.../DocumentInfoRoute.kt`
- Create: `feature/document-info/impl/src/commonMain/.../DocumentInfoScreen.kt`
- Create: `feature/document-info/impl/src/commonMain/.../DocumentInfoUiState.kt`
- Create: `feature/document-info/impl/src/commonMain/.../DocumentInfoViewModel.kt`

Tasks:
- [ ] Open from reader action menu.
- [ ] Show name, location, size, format, page count, current page.
- [ ] Show character/word count where available.
- [ ] Show active reading time words per minute.
- [ ] Show latest sessions list. Calendar/year statistics later.

Verification:

```bash
./gradlew :feature:document-info:impl:allTests :core:domain:allTests
```

### Phase 8: PDF reader UI

APK reference: `pdf_layout`, `pdf_nav`, `pdf_pages`, `pdf_password`, `pdf_gllayout`, PDF annotation icons, `librdpdf.so`.

Files:
- Create common interface in `feature/reader/impl/src/commonMain/.../pdf/PdfPageRenderer.kt`.
- Android actual: use Android `PdfRenderer` for page bitmaps.
- iOS actual: use PDFKit through platform wrapper.

Tasks:
- [ ] Separate fixed-layout PDF reader mode from reflowable TXT/EPUB mode.
- [ ] Render current PDF page bitmap.
- [ ] Add page nav, zoom fit-width/fit-page, rotate display.
- [ ] Save progress as `ReaderLocation.PdfPage`.
- [ ] PDF menu page thumbnails/password/fit options disabled or later placeholders until implemented.
- [ ] Password-protected PDF: show unsupported/password-required state first; actual password unlock later.
- [ ] PDF annotations are deferred.

Verification:

```bash
./gradlew :feature:reader:impl:allTests :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64
```

### Phase 9: Auto-scroll MVP

APK reference: `tips_autoscroll.png`, `scroll_event_confirm`, `speed_read`.

Files:
- Modify: `feature/reader/impl/.../ReaderScreen.kt`
- Modify: `feature/reader/impl/.../ReaderUiState.kt`
- Reuse: `AutoScrollConfig` and `ReaderSettingsRepository`

Tasks:
- [ ] Add auto-scroll start/stop button in bottom action bar.
- [ ] Add Auto-scroll bottom sheet from Phase 4.
- [ ] Implement timer-driven page/offset advancement in UI layer.
- [ ] Speed comes from `AutoScrollConfig`.
- [ ] Pause auto-scroll on user gesture, app background, or document end.

Verification:

```bash
./gradlew :feature:reader:impl:allTests
```

### Phase 10: Later, not MVP

APK evidence exists, but skip until local reader is solid:
- TTS foreground service notification controls
- OPDS/catalog downloads
- Dropbox/WebDAV/Google Drive sync
- Widget/recent reading provider
- DJVU/MOBI/AZW3/FB2/CBR/CBZ/CHM/DOCX support
- PDF annotations/handwriting/signature/stamps
- Readwise integration
- Hyphenation dictionaries
- Bundled fonts/background packs
- Full color picker theme export/import
- Gesture zone editor

## 8. Acceptance Criteria

- [ ] No copied APK code/resources/assets in TeddReader.
- [ ] Feature modules follow `api/impl` split.
- [ ] Feature impl modules do not depend on `:core:data`.
- [ ] UI state classes are data classes.
- [ ] `core/ui` provides Material3 wrappers for loading, dropdown menus, modal bottom sheets, switch rows, checkbox rows, slider rows, radio rows, option groups.
- [ ] Menus select option categories; bottom sheets edit actual values.
- [ ] Reader screen can open TXT/EPUB through current core parser/pagination path.
- [ ] Reader options persist through DataStore-backed `ReaderSettingsRepository`.
- [ ] PDF opens in fixed-layout mode with page-based progress once Phase 8 is implemented.
- [ ] Search works for TXT/EPUB and reports unavailable for unsupported PDF text extraction.
- [ ] Bookmarks and last progress persist across app restart.
- [ ] Android debug APK builds and iOS simulator framework links per phase.

## 9. Verification Commands

Run after implemented phase:

```bash
./gradlew :core:common:allTests :core:domain:allTests :core:data:allTests :core:datastore:allTests :core:room:allTests :core:designsystem:allTests :core:ui:allTests
./gradlew :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64
```

Dependency guard:

```bash
find feature -path '*/build/*' -prune -o -name '*.gradle.kts' -type f -print0 | xargs -0 grep -n "projects.core.data\|:core:data" && exit 1 || true
find feature -path '*/build/*' -prune -o -name '*.kt' -type f -print0 | xargs -0 grep -n "com.tedd.teddreader.core.data" && exit 1 || true
```

## 10. Recommended Build Order

```text
1. Material3 core UI wrappers
2. Navigation route shell
3. Home/library real UI + file import
4. Reader screen TXT/EPUB MVP
5. Reader menus + active bottom sheet state
6. Reader option bottom sheets
7. Search overlay
8. Bookmarks/notes MVP
9. Document info/statistics
10. PDF renderer mode
11. Auto-scroll
12. TTS/cloud/widget/extra formats later
```

---

## 11. Implementation Status — 2026-07-06

Implemented in TeddReader source modules:

- [x] Phase 0 ethical boundary kept: APK used only as feature/UX reference.
- [x] Phase 1 typed navigation route shell.
- [x] Phase 2 home/library MVP with recent documents, empty/open-file state, and Android `OpenDocument` import-to-reader routing.
- [x] Phase 2.5 reusable Material3 core UI wrappers.
- [x] Phase 3 reader screen MVP with restored progress and real TXT/EPUB `PageWindow` loading through `DocumentRepository` domain contract.
- [x] Phase 3.5 reader action menu and active bottom sheet state.
- [x] Phase 4 reader visual settings bottom sheets with async save loading/disabled state.
- [x] Phase 5 search overlay foundation with TXT/EPUB result jump and honest PDF text-search unsupported state.
- [x] Phase 6 bookmarks/notes MVP.
- [x] Phase 7 document info/statistics screen.
- [x] Phase 8 fixed-layout PDF mode: Android uses platform `PdfRenderer`; iOS currently keeps explicit PDFKit-not-connected placeholder.
- [x] Phase 9 auto-scroll MVP: start/stop, speed-based timer page advance, stop on tap/navigation/document end.
- [x] App assembly runtime DI bootstrap: Koin modules wire Room/DataStore/data repositories/usecases/ViewModels in `:app:reader`; feature modules still do not depend on `:core:data`.

Known follow-up after this plan:

- [ ] iOS PDFKit bitmap rendering actual.
- [ ] Advanced PDF zoom/rotate/thumbnails/password unlock.
- [ ] iOS document picker/import actual and broader Android picker hardening.
