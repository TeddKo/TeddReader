# Reader Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Moon+ Reader-class local document reader for TXT, PDF, and EPUB with reading, search, pagination, styling, bookmarks, progress restore, document info, statistics, and auto-scroll.

**Architecture:** Keep `androidApp/` and `iosApp/` as platform shells. Shared CMP/KMP code lives in `:core:*`, `:feature:*`, plus small app assembly KMP module so features remain screen-level modules instead of becoming app entry points. Use Android Architecture with optional usecases: repository interfaces live in `:core:domain`, implementations live in `:core:data`; features inject domain repository interfaces for simple reads or domain usecases for business rules.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, AGP 9.x Android-KMP plugin, Room KMP, DataStore KMP, Android SAF/PdfRenderer, iOS UIDocumentPicker/PDFKit, build-logic convention plugins.

---


## 0. Current Decisions and Progress

- **Module split:** keep `androidApp/` and `iosApp/` platform shells. Shared code lives in `:core:*`, `:feature:*`, and `:app:reader` app assembly.
- **Build control:** every Gradle module stays thin and uses `build-logic` convention plugins. Version aliases live in `gradle/libs.versions.toml`; current pins include AGP `9.2.1`, Kotlin `2.4.0`, Compose Multiplatform `1.11.1`, Navigation 3 `1.2.0-alpha02`, Koin `4.2.2`, Room `3.0.0`, DataStore `1.2.1`.
- **Required libraries:** kotlinx serialization, datetime, collections immutable, coroutines, Koin annotation compile plugin, Navigation 3 serialization routes, DataStore with kotlinx serialization, Room KMP, Coil Compose, lifecycle/viewmodel Compose.
- **Feature rule:** screen features split as `:feature:<screen>:api` and `:feature:<screen>:impl`. `api` exposes serializable routes/contracts only. `impl` owns Compose UI/ViewModel/state/Koin bindings, depends on `:core:domain`, and must not depend directly on `:core:data`.
- **State rule:** screen state is a single immutable `data class <Screen>UiState`; composables receive state and event lambdas.
- **Architecture rule:** repository interfaces live in `:core:domain`; implementations live in `:core:data`. Simple reads may inject domain repository interfaces. Non-trivial business rules use domain usecases.
- **Companion object rule:** do not use companions for simple constants, default instances, or parsing helpers. Prefer constructor defaults, top-level `const val`, top-level functions, enums, or sealed interfaces. Companion objects are allowed only for real type-tied factories and must not hold large data, platform `Context`, caches, or mutable state.
- **Already implemented:** `:core:common`, `:core:designsystem`, `:core:ui`, `:core:datastore`, `:core:room`, `:core:domain` repository contracts/usecase foundations, `:core:data` storage source, document import, parsers, repository implementations, pagination, Search/Stats foundations, `:feature:home:api` / `:feature:home:impl`, and `:app:reader` assembly.
- **Latest verification:** `./gradlew :core:domain:allTests :core:data:allTests :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64` passed after Search/Stats repository + usecase foundation and feature convention guard.

## 1. Competitor Analysis: Moon+ Reader

Sources reviewed:
- Moon+ Reader official site: https://www.moondownload.com/
- Moon+ Reader FAQ: https://www.moondownload.com/faq.html
- Moon+ Reader Pro Google Play listing: https://play.google.com/store/apps/details?id=com.flyersoft.moonreaderp
- Android SAF docs: https://developer.android.com/training/data-storage/shared/documents-files
- Android PdfRenderer docs: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
- Apple PDFKit PDFView docs: https://developer.apple.com/documentation/pdfkit/pdfview
- Readium Kotlin Toolkit guide/release: https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/getting-started.md, https://blog.readium.org/release-note-kotlin-toolkit-version-3-2-0/
- Room/DataStore docs: https://developer.android.com/jetpack/androidx/releases/room, https://developer.android.com/topic/libraries/architecture/datastore

Moon+ Reader positions itself around three strengths:
1. **Format breadth:** TXT, HTML, EPUB, PDF, DJVU, MOBI/AZW3, FB2, DOCX, comics, archives, OPDS.
2. **Reading control density:** visual options, day/night modes, gestures/hardware keys, page animations, auto-scroll, orientation, dual page, PDF annotations, TTS, dictionary/translation.
3. **Library continuity:** bookshelf, tags/authors, import/search, bookmarks/notes/highlights, reading statistics, cloud backup/sync.

Important product lesson: power users accept complexity, but first-run UX must stay simple. MVP should not copy every option. Ship the core loop first: import → parse/render → read → search → bookmark → resume → customize.

## 2. Scope Decision

### MVP formats
- `TXT`: full reflow, pagination, style customization, search, word/character count.
- `EPUB`: reflow by extracting spine XHTML into text/blocks; search and styling supported. DRM is not supported.
- `PDF`: fixed-layout page rendering first. Text search uses platform extractor only when available; full PDF annotation/form support is later.

### Explicit tradeoff
PDF is fixed-layout. Font size, font family, paragraph spacing, and text color cannot be faithfully applied to arbitrary PDFs without text extraction/reflow or OCR. MVP supports PDF page rendering, zoom/fit, night filter, page navigation, bookmarks, progress, and metadata. Full PDF text reflow is a later feature.

## 3. Target Module Structure

Add one assembly module because `feature` modules are screen units, not app roots:

```text
androidApp/                      Android shell only
iosApp/                          SwiftUI shell only
app/reader/                      KMP/CMP app assembly, navigation, exported iOS framework
core/common/                     Result, ids, time, text ranges, dispatchers
core/datastore/                  Reader preferences and style settings
core/room/                       Library DB, progress, bookmarks, search index, stats
core/data/                       Repository implementations, mappers, parsers, data sources
core/domain/                     Repository contracts and optional business usecases
core/designsystem/               Theme tokens, reader typography, colors
core/ui/                         Shared reader UI components
feature/home/api/                Home route/contracts
feature/home/impl/               Library/home screen implementation
feature/reader/api/              Reader route/contracts
feature/reader/impl/             Reading screen implementation
feature/search/api/              Search route/contracts
feature/search/impl/             Search screen/dialog implementation
feature/settings/api/            Settings route/contracts
feature/settings/impl/           Reader/app settings screen implementation
feature/document-info/api/       Document info route/contracts
feature/document-info/impl/      Metadata/statistics screen implementation
feature/bookmarks/api/           Bookmarks route/contracts
feature/bookmarks/impl/          Bookmarks/highlights screen implementation
build-logic/                     All Gradle conventions
```

Dependency rule:

```text
androidApp, iosApp -> app:reader
app:reader -> feature:*:api + feature:*:impl
feature:*:impl -> feature:*:api, core:ui, core:designsystem, core:domain
feature:*:api -> core:common only when route/contract models need shared types
feature modules never depend on another feature impl; use another feature api only
core:domain -> core:common
core:data -> core:domain, core:common, core:room, core:datastore
core:room/datastore/designsystem/ui -> core:common as needed
```

## 4. Core Data Model

Create in `core/common/src/commonMain/kotlin/com/tedd/teddreader/core/common/model/`:

- `DocumentId(value: String)`
- `DocumentFormat`: `TXT`, `PDF`, `EPUB`, `UNKNOWN`
- `DocumentLocation`: stable source URI/path + display name + MIME + size
- `ReaderLocation`: canonical location independent of page count
  - TXT: character offset
  - EPUB: spine index + character offset
  - PDF: page index
- `PageIndex(current: Int, total: Int)`
- `TextRange(start: Long, end: Long)`
- `ReaderStyle`: font size, font family, line height, text color, background color/image, theme mode
- `PageTurnMode`: horizontal, vertical, continuous
- `PageAnimation`: none, slide, fade, scroll
- `AutoScrollConfig`: enabled, mode, speed

Create in `core/room`:

- `DocumentEntity`: id, name, uri, format, size, addedAt, lastOpenedAt, pageCount, charCount, wordCount
- `ReadingProgressEntity`: documentId, readerLocation, pageIndex, updatedAt
- `BookmarkEntity`: documentId, readerLocation, label, note, createdAt
- `ReadingSessionEntity`: documentId, startedAt, endedAt, activeMillis, startLocation, endLocation
- `SearchIndexEntity`: documentId, sectionIndex, text, startOffset, endOffset

Use Room for relational/queryable data. Use DataStore for small preferences such as theme, default style, animation, auto-scroll defaults. DataStore is explicitly recommended for small key-value/typed settings; Room is better for complex datasets and referential integrity.

## 5. Reader Engine Design

### Parsing pipeline

```text
DocumentSource -> FormatDetector -> DocumentParser -> ReaderDocument -> PageResolver/SearchIndexer
```

Repository contracts in `core:domain`; implementations in `core:data`:

- `DocumentRepository`
  - import/open document
  - get metadata
  - observe recent documents
- `ReaderRepository`
  - load parsed document
  - save/restore progress
  - resolve page for location
- `SearchRepository`
  - index document text
  - find text and map result to location/page
- `BookmarkRepository`
  - add/remove/list bookmarks
- `ReadingStatsRepository`
  - record sessions and calculate speed/time
- `ReaderSettingsRepository`
  - observe/update style, theme, animation, auto-scroll

Usecases in `core:domain` only when logic is non-trivial:

- `OpenDocumentUseCase`
- `BuildSearchIndexUseCase`
- `FindInDocumentUseCase`
- `RestoreReadingProgressUseCase`
- `RecordReadingSessionUseCase`
- `CalculateReadingStatsUseCase`

Simple screen reads can inject repository interfaces from `:core:domain`; DI binds them to `:core:data` implementations.

### Pagination

For TXT/EPUB, store progress by canonical text location, not page number. Page numbers change when font size, orientation, margin, or viewport changes. Use a `PageLayoutEngine` that accepts:

```text
ReaderDocument + ReaderStyle + ViewportSize + PageTurnMode -> PageWindow
```

Use lazy pagination:
- paginate current chapter/window first;
- cache neighboring pages;
- invalidate cache when style/viewport changes;
- remap previous canonical location to new page.

For PDF, page count comes from the PDF renderer. Progress is page index.

### Search

TXT/EPUB:
- extract text into sections;
- build Room FTS/search table;
- return matches as `SearchResult(documentId, snippet, location, sectionTitle)`.

PDF:
- Android: use platform PDF APIs where available. `PdfRenderer` supports rendering; newer APIs expose more PDF text/search features but must be guarded by API level.
- iOS: use PDFKit for PDF display/search.
- If text extraction fails, show “search unavailable for this PDF” rather than fake results.

## 6. UI/UX Screens

### `feature/home`
Library screen:
- recent documents
- open file button
- file import status
- quick resume last document

### `feature/reader`
Reader screen:
- page viewport
- tap center opens reader bar
- horizontal/vertical/continuous navigation
- page animation selection
- bookmark toggle
- search entry
- style quick controls
- auto-scroll control
- orientation lock control

### `feature/settings`
Global reader defaults:
- theme mode: system/light/dark
- font size/family
- text/background color
- background image
- default page mode and animation
- auto-scroll mode/speed defaults

### `feature/document-info`
Document info:
- name, location, size, format
- total/current page
- char/word count where extractable
- reading time
- reading speed
- reading history dates

### `feature/bookmarks`
Bookmarks list and jump.


## 7. Recommended Execution Order

Yes: implement foundations before app assembly or feature screens. The app shell can compile with a placeholder, but real reader work depends on stable shared UI contracts.

```text
1. core:common        shared reader models and value types
2. core:designsystem  reader theme tokens, colors, typography, style mapping
3. core:ui            reusable reader components and controls
4. core:datastore     persisted visual/reader preferences
5. core:room          document/progress/bookmark/stat schemas
6. core:data          repositories and parsers
7. core:domain        usecases only where logic is non-trivial
8. app:reader         app assembly/navigation/iOS framework
9. feature:*:api/impl home, reader, search, settings, info, bookmarks
```

Do not build every setting screen before the engine exists. First make `core:designsystem` expose reader style primitives and `core:ui` expose dumb reusable controls. Features wire them later.

## 8. Implementation Tasks / Phase Status

Scope for this checkpoint: **finish non-screen reader business logic**. Screen-level reader/home/search/settings/bookmark/document-info implementation remains out of scope.

### Phase 0: Design System and Shared UI Foundations

**Status:** Done.

- [x] Reader color tokens: light, dark, sepia, custom text/background.
- [x] Reader typography mapping from `ReaderStyle`.
- [x] Reusable dumb UI primitives: page surface, controls, progress bar.
- [x] No parser/repository/navigation/screen state inside `:core:ui`.

### Phase 0.5: Feature API/Impl Boundary

**Status:** Done for foundation.

- [x] Feature modules use `:feature:<screen>:api` and `:feature:<screen>:impl` shape.
- [x] `UiState` is a data class in impl modules.
- [x] Feature impl depends on feature api + core UI/design/domain, not `:core:data`.
- [x] Build-logic convention plugins own feature Gradle setup.

### Phase 1: App Assembly

**Status:** Done.

- [x] `:app:reader` KMP/CMP assembly module added.
- [x] Android shell delegates to `TeddReaderApp()`.
- [x] iOS shell delegates to exported `MainViewController()` framework.

### Phase 2: Reader Domain Models

**Status:** Done.

- [x] Serializable document/location/page/style/animation/auto-scroll models.
- [x] `kotlinx.serialization`, `kotlinx.datetime`, immutable collection support reflected in shared models where needed.
- [x] Companion object overuse avoided; top-level constants/functions, enums, sealed interfaces used instead.

### Phase 3: Storage Access Business Layer

**Status:** Done.

- [x] Common `DocumentFileSource` contract reads selected document bytes.
- [x] Android SAF/content URI implementation with persistable read permission helper.
- [x] iOS file path implementation with app-container copy helper.
- [x] Source URI/path, display name, MIME, and size flow through `DocumentLocation`.

### Phase 4: Metadata Persistence

**Status:** Done.

- [x] Room entities for documents, progress, bookmarks, sessions, and search index.
- [x] DAO contracts for document/progress/bookmark/session/search operations.
- [x] Android/iOS Room database builders with bundled SQLite driver.
- [x] Repository contracts stay in `:core:domain`; implementations stay in `:core:data`.

### Phase 5: Reader Preferences

**Status:** Done.

- [x] JSON DataStore stores `ReaderStyle`, page turn mode, animation, and auto-scroll defaults.
- [x] Android/iOS DataStore factories added.
- [x] `ReaderSettingsRepository` exposes settings as domain `Flow<ReaderSettings>`.
- [x] Settings screen remains deferred to feature UI phase.

### Phase 6: Document Import, TXT Parser, Search, Stats

**Status:** Done.

- [x] Format detection for TXT/PDF/EPUB from MIME, extension, and magic bytes.
- [x] TXT parser builds `ReaderDocument` with character/word counts.
- [x] `DocumentRepository.importDocument()` parses bytes, persists metadata, and refreshes search index.
- [x] `SearchRepositoryImpl` indexes/searches TXT/EPUB text and maps matches to canonical locations.
- [x] `ReadingStatsRepositoryImpl` records sessions and calculates reading stats.

### Phase 6.5: Domain Usecases

**Status:** Done.

- [x] `OpenDocumentUseCase` imports/opens documents and marks last opened time.
- [x] Search index/find usecases added for non-trivial search actions.
- [x] Save/restore reading progress usecases added.
- [x] Record/calculate reading session usecases added.
- [x] Active reading time calculator excludes inactive/background ranges.
- [x] Simple feature reads can inject domain repository interfaces directly.

### Phase 7: EPUB Business Parser

**Status:** Done.

- [x] DRM-free EPUB ZIP parsing via Okio.
- [x] `META-INF/container.xml` OPF root resolution.
- [x] OPF manifest/spine order resolution.
- [x] XHTML to readable text sections.
- [x] Chapter title/spine index preserved for canonical `ReaderLocation.EpubOffset`.
- [x] TXT/EPUB share pagination/search path.

### Phase 8: PDF Business Parser

**Status:** Done for business core; renderer deferred.

- [x] Fixed-layout PDF metadata imports as `ReaderDocument(format = PDF)`.
- [x] PDF page object count persisted as page-count metadata.
- [x] PDF progress/bookmarks use `ReaderLocation.PdfPage`.
- [x] PDF text search reports unsupported unless extracted text sections exist.
- [ ] Actual PDF bitmap rendering/zoom/fit is deferred to platform reader UI phase.

### Phase 9: Text Pagination Business

**Status:** Done.

- [x] `TextPageLayoutEngine` paginates TXT/EPUB from `ReaderStyle` + `ViewportSize`.
- [x] Returns `PageWindow`, `PageIndex`, `TextRange`, and canonical `ReaderLocation`.
- [x] Pagination is pure/testable and independent from Compose scroll state.
- [x] Style/viewport changes remap from canonical location instead of persisted page number.

### Phase 10: Verification

**Status:** Done for non-screen business logic.

- [x] Core tests pass for common/domain/data/datastore/room.
- [x] Android app shell assembles.
- [x] iOS simulator framework links.
- [x] Feature/screen implementation remains deferred except minimal home foundation.

Verification command:

```bash
./gradlew :core:common:allTests :core:domain:allTests :core:data:allTests :core:datastore:allTests :core:room:allTests :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64
```

## 9. Business Logic Acceptance

Completed for this checkpoint:

- [x] TXT documents import, parse, paginate, search, and report word/character counts.
- [x] DRM-free EPUB documents import, parse spine XHTML text, paginate, and search.
- [x] PDF documents import as fixed-layout metadata with page count and page-based progress/bookmarks.
- [x] PDF search capability is honest: unsupported unless text sections exist.
- [x] Bookmarks/progress/session/search index schemas and repositories exist.
- [x] Last-read progress can be saved/restored through domain usecases.
- [x] Reader settings persist through typed JSON DataStore.
- [x] Reading time/speed statistics are calculated from active reading sessions.

Deferred to feature/platform UI phases:

- [ ] Reader screen horizontal/vertical/continuous page controls.
- [ ] Page animations: slide, fade, scroll.
- [ ] PDF page bitmap rendering, zoom, fit, and night filter.
- [ ] File picker UI wiring and permission UX.
- [ ] Settings, document-info, bookmark, search, and reader screens.

## 10. Risks and Mitigations

- **PDF customization mismatch:** Fixed PDFs cannot behave like reflowable text. Mitigate with clear UI: “PDF view mode” vs “text reflow mode” later.
- **Large file memory use:** Use lazy parsing/pagination and page caches; never load/render every PDF page bitmap.
- **Storage permission churn:** Use SAF/UIDocumentPicker first. Avoid Android All Files Access until a full file manager is truly required.
- **Bad EPUBs:** Start with DRM-free EPUB 2/3 text spine. Add compatibility cases from real samples.
- **Reading speed inaccuracy:** Track active sessions and progress deltas only; ignore idle/background duration.

## 11. Deferred Features

- PDF annotations/forms/handwriting.
- TTS.
- Dictionary/translation.
- OPDS/Calibre server.
- Cloud sync/backup.
- Widgets/home-screen shortcuts.
- MOBI/AZW3/DOCX/comic/archive support.
- AI character image/description generation.

