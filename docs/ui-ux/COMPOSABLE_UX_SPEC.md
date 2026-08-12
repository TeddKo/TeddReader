# TeddReader Composable UX/UI Specification

## 1. Purpose and scope

This document is the implementation-facing UX contract for every production Composable in the app shell, shared UI, feature screens, reader chrome, pager/effects, and platform adapters. DESIGN.md remains the product/design source of truth; this file maps that contract to concrete symbols and states.

Baseline evidence:

- App shell and navigation: app/reader/src/commonMain/.../TeddReaderApp.kt:19 and navigation/ReaderNavHost.kt:74
- Theme/tokens: core/designsystem/src/commonMain/.../TeddReaderTheme.kt:20-154
- Shared components: core/ui/src/commonMain/.../component and core/ui/src/commonMain/.../reader
- Screens: feature/home, search, bookmarks, document-info, settings, reader
- Platform UI adapters: core/ui/src/androidMain|iosMain and feature/reader/impl/src/androidMain|iosMain

Preview-only Composables are test fixtures, not production surfaces. They still receive coverage requirements in section 12.

## 2. Global layout and behavior rules

### 2.1 Breakpoints

| Class | Available width | Required behavior |
| --- | ---: | --- |
| Compact | 240-359 dp | One column, actions wrap/stack, no reduced touch targets |
| Regular | 360-599 dp | One column, inline actions only when they fit |
| Medium | 600-839 dp | Centered/bounded content; Reader may use two panes only if each pane is at least 280 dp |
| Expanded | 840+ dp | Adaptive grids for independent cards; max readable widths remain bounded |

Window width means actual content constraints after safe insets, not device model or orientation. Landscape alone must never force two Reader panes.

### 2.2 Edge-to-edge and insets

- Root surfaces draw behind status and navigation bars.
- Each destination consumes safe insets once. No parent-level global systemBarsPadding.
- App-bar backgrounds extend behind the status bar; only app-bar content is inset.
- Scroll containers receive top/bottom inset as content padding so content can scroll behind bars without clipping.
- Search, Go to page, bookmark notes, and other text inputs must remain visible above the IME. Android MainActivity uses enableEdgeToEdge; AndroidManifest must use adjustResize.
- Reader system bars use the active ReaderStyle background and luminance-derived icon appearance.

### 2.3 Modifier ordering

Interactive container order:

1. size/fill/shape boundary,
2. clickable/selectable/toggleable and ripple,
3. internal padding,
4. child layout.

External screen spacing must not shrink a row's click/ripple region. A nested visual control must not create a second focus target when the parent row owns the action.

### 2.4 Accessibility

- Minimum touch target: 48 dp.
- Production icon buttons require a non-empty content description.
- Selected filters use selected semantics; checked controls expose checked state; progress exposes range/current state.
- Dynamic loading, error, and result-count changes use live-region semantics where appropriate.
- Focus order follows reading order and remains stable when content wraps.
- At font scale 1.3, no action label, setting title, or value clips at 240 dp.
- Gesture shortcuts never replace visible/focusable actions.

### 2.5 Content, motion, and state

- One state exposes one primary CTA.
- Loading, empty, error, and populated content are mutually exclusive where possible.
- Motion uses 120/200/300 ms tokens, remains interruptible, and never delays input.
- Screen/route Composables collect state and own only ephemeral UI state. Reusable content Composables receive state plus callbacks.
- Scroll positions, active sheet, query/filter drafts, and document location survive configuration/restoration where meaningful.

## 3. App shell and navigation

| Composable | Current location | Target contract |
| --- | --- | --- |
| TeddReaderApp | app/reader/.../TeddReaderApp.kt:19 | Own theme selection and full-window fallback background only. Do not add content insets. Propagate app light/dark state and let Reader override its palette locally. |
| ReaderNavHost | app/reader/.../navigation/ReaderNavHost.kt:74 | Keep Navigation 3 back stack as the source of navigation state. Preserve pending ReaderLocation handoff. Supply destination-specific chrome/inset policy instead of wrapping NavDisplay in padding. |
| PlaceholderDestination | ReaderNavHost.kt:183 | Use standard top bar, concise unavailable/unknown message, and one Back recovery action. Remove placeholder-only decorative layout. |

Navigation acceptance:

- Back behavior is identical for system back, top-bar back, keyboard Escape where supported, and accessibility action.
- Search/bookmark result selection pops to the existing Reader and moves to the location without adding a duplicate Reader destination.
- SettingsRoute either connects to the existing settings UI or is removed; it must not remain a dead placeholder.

## 4. Standard screen shell

Evolve TeddScaffold and TeddTopBar at core/ui/.../TeddWrappers.kt:218-266 rather than adding another scaffold abstraction.

Required variants:

- Home: title plus optional populated-state Open file action; no back action.
- Context screen: back, title, optional subtitle/count, optional secondary actions.
- Scroll behavior: top bar remains stable for compact task screens; large decorative headings may scroll, but navigation never disappears.
- Content max width: 720 dp for search/details/forms, 960 dp for collections.
- Insets: app bar handles status bar; content handles navigation bar and IME.

## 5. Screen specifications

### 5.1 Home

Symbols:

- HomeRouteScreen — feature/home/.../HomeScreen.kt:40
- HomeScreen — HomeScreen.kt:64
- HomeSortFilterControls — HomeScreen.kt:173
- HomeAddDocumentsDialog — HomeScreen.kt
- DocumentListItem — feature/home/.../component/DocumentListItem.kt:19

Target hierarchy:

1. Top bar: TeddReader title; Open file action appears only when documents exist.
2. Import/error banner when present.
3. Empty state or populated library, never both.
4. Populated library: compact sort/filter control, result heading/count, adaptive document collection.

State contract:

- Loading: skeleton/indicator on the same screen background; no empty CTA flashes.
- Empty: one Open file primary button inside empty state; top-bar Open file omitted.
- Populated: top-bar Open file is the single primary action; empty CTA absent.
- Import failure: inline error with retry/open action only if actionable.
- Filter returns zero items: “No matching documents,” Clear filters action; do not reuse first-run empty copy.

Import choice dialog:

- Group file and folder actions under “On this device.”
- Show Google Drive in a separate “Cloud” group only when the platform bridge is available.
- Each source is a full-width row with a title, concise supporting text, clipped ripple, and a 48 dp minimum target; do not present three equal-emphasis buttons in one stack.
- Keep focus order local files, local folder, then Google Drive.

Adaptive contract:

- Compact/regular: one LazyColumn with full-width DocumentListItem rows.
- Medium: bounded single column.
- Expanded: LazyVerticalGrid using adaptive cells with 280 dp minimum; item semantics and full-card ripple remain intact.
- Filter chips wrap with horizontal and vertical gaps. Selected state is not encoded as a bullet in text.

DocumentListItem:

- Entire row opens the document.
- Leading format is a non-clickable badge.
- Title: maximum two lines; filename extension may remain visible.
- Supporting text: human-readable size and page count once.
- Optional reading progress replaces duplicated trailing “Open” text.
- Ripple reaches the full row boundary; content padding remains internal.

### 5.2 Search

Symbols:

- SearchRouteScreen — feature/search/.../SearchScreen.kt:39
- SearchScreen — SearchScreen.kt:65

Target hierarchy:

1. Standard back top bar titled “Find in document.”
2. Search field with clear action.
3. Search results/status.

Behavior:

- IME Search and visible Search button call the same callback.
- Visible Search button is disabled for blank/whitespace-only input.
- Submitting trims the query but does not mutate user-visible text unexpectedly.
- Query blank: short instructional state without a card.
- Loading: progress near results; field stays usable and retains focus.
- No results: query-specific copy and optional Select all/clear is not added.
- Results: LazyColumn, result count announced, snippet emphasizes the match, location remains supporting text.
- Selecting a result returns to Reader and preserves query when navigating back later.

Adaptive:

- Search stays a bounded single column at all widths because snippets need line length.
- Search field and action stack at compact width; action may become trailing field action only if the 48 dp target remains.
- IME and navigation bar never cover the last result.

### 5.3 Bookmarks

Symbols:

- BookmarksRouteScreen — feature/bookmarks/.../BookmarksScreen.kt:52
- BookmarksScreen — BookmarksScreen.kt:89
- BookmarkCard — BookmarksScreen.kt:212

Target hierarchy:

1. Back top bar, title, saved-count subtitle.
2. Empty explanation or bookmark collection.
3. Bookmark edit sheet when requested.

Behavior:

- Empty state has no duplicate Back to reader CTA because the top bar already provides navigation.
- Entire bookmark row opens the bookmark.
- Secondary overflow action opens edit/delete actions; do not place equal-emphasis Edit/Delete buttons inside every card.
- Note edit keeps draft state until save/dismiss.
- Delete uses destructive emphasis and a confirmation step containing the bookmark label/location.
- Save is disabled when unchanged; successful save dismisses or reports progress without losing draft.

Adaptive:

- Compact through medium: one bounded list.
- Expanded: optional two-column grid only if note/location text still has at least 280 dp.
- Sheet actions wrap with gaps; primary Save remains first in focus order, destructive Delete visually separated.

### 5.4 Document info

Symbols:

- DocumentInfoRouteScreen — feature/document-info/.../DocumentInfoScreen.kt:46
- DocumentInfoScreen — DocumentInfoScreen.kt:69
- StatGrid — DocumentInfoScreen.kt:221
- StatCard — DocumentInfoScreen.kt:243

Target hierarchy:

1. Back top bar plus document name.
2. Overview metadata.
3. Reading statistics.
4. Recent sessions.

Behavior:

- Format, size, pages, and current page appear once; remove duplicate chip-plus-row repetition.
- Long file names and URIs wrap. URI is selectable/copyable but not treated as a navigation action.
- Size uses B/KB/MB; reading time uses hours/minutes; rates include units.
- Missing data uses “Not available,” not ambiguous zero unless zero is meaningful.
- Recent sessions use clear date/duration/location hierarchy and stable keys.

Adaptive:

- TeddInfoRow stacks label/value at compact width or when value is long.
- Stats: one column compact, two regular/medium, up to four expanded.
- Overview and sessions remain bounded to 720 dp for readability.

### 5.5 Reader settings summary

Symbols:

- ReaderSettingsSheet — feature/settings/.../ReaderSettingsSheet.kt:18
- SettingSummaryRow — ReaderSettingsSheet.kt:82

Contract:

- Connect to SettingsRoute or make it an explicit Reader action; otherwise delete the unused destination and surface.
- Summary rows are navigable only when they lead to an editor; read-only rows use information semantics, not click affordance.
- Values use user-facing labels and formatted units.
- Current ReaderOptionPreview remains above grouped summaries and updates with persisted state.
- At compact width, value wraps below title; at wider widths it may align trailing.

### 5.6 Reader

Symbols:

- ReaderRouteScreen — feature/reader/.../ReaderScreen.kt:85
- ReaderScreen — ReaderScreen.kt:199
- ReaderContent — ReaderScreen.kt:304
- ReaderPagePane — ReaderScreen.kt:533
- ReaderAutoScrollEffect — ReaderScreen.kt:589
- ReaderError — ReaderScreen.kt:1069

Layer order:

1. ReaderStyle background covering the entire window and system bars.
2. Pager/page content.
3. Top and bottom chrome when visible.
4. Reader modal sheet.
5. Brightness/dim overlay that must not block required controls or accessibility focus.

Gesture contract:

- Center tap toggles chrome.
- Previous/next tap zones navigate when enabled.
- Drag crossing touch slop hides visible chrome once.
- Chrome observer listens on PointerEventPass.Initial, never consumes, uses stable pointerInput key, and uses updated callbacks.
- Pager receives the same uninterrupted gesture and animation.
- Drag beginning on top/bottom chrome operates the control and does not trigger page navigation.
- Any manual page move stops auto-scroll.

Adaptive:

- One page below 600 dp available width.
- Two pages only when both panes retain at least 280 dp plus gutter; orientation alone is insufficient.
- Text page line length is capped; outer margins grow on large panes instead of stretching lines.
- Bottom controls: two rows below 360 dp; one row otherwise.
- Top title ellipsizes to one line while back/bookmark/menu keep 48 dp targets.

State:

- Loading/error backgrounds match the Reader/theme surface and use safe insets.
- Page content preserves position through chrome visibility changes.
- Sheet open state forces system bars visible and restores prior chrome state on dismissal.
- Dim overlay does not change reader theme/system-bar color calculation.

## 6. Reader sheets and action menu

### 6.1 ReaderActiveSheet and TeddModalBottomSheet

Locations:

- ReaderActiveSheet — ReaderScreen.kt:618
- TeddModalBottomSheet — core/ui/.../TeddModalBottomSheet.kt:25

Contract:

- Sheet title comes from the selected option.
- Sheet content is navigation-bar and IME safe.
- Long content scrolls; title/drag handle remain stable.
- Saving state disables only mutable controls and announces “Saving.”
- Dismiss keeps persisted settings and discards uncommitted drafts deliberately.

### 6.2 Sheet matrix

| Composable | Location | Target UX |
| --- | --- | --- |
| TableOfContentsSheet | ReaderScreen.kt:732 | Lazy keyed outline list; current section selected; empty state without a disabled-looking card; tap moves and dismisses. |
| GoToPageSheet | ReaderScreen.kt:756 | Numeric keyboard, range/error supporting text, IME Go action, visible Go button disabled until valid. |
| BrightnessOptionsSheet | ReaderScreen.kt:787 | Live preview while dragging; commit once on finish; explain app-only dimming; retain 20-100% range. |
| ViewOptionsSheet | ReaderScreen.kt:814 | Keep screen on, fullscreen, and progress only. Remove disabled “Background image” until implemented. |
| FontOptionsSheet | ReaderScreen.kt:836 | Preview first, then font family and size/line-height controls; formatted values; drafts survive rotation. |
| ThemeOptionsSheet | ReaderScreen.kt:901 | Show light/dark/sepia preview swatches; custom option omitted until a picker exists; system-bar preview follows selection. |
| PageTurnOptionsSheet | ReaderScreen.kt:934 | Separate direction/mode from visual animation; incompatible combinations disabled with explanation; human-readable labels. |
| AutoScrollOptionsSheet | ReaderScreen.kt:965 | Enabled switch is the single start/stop control; remove duplicate Start/Stop button unless it becomes the sheet primary action. Mode and speed disabled when off. |
| ControlOptionsSheet | ReaderScreen.kt:1003 | Keep only meaningful reader-chrome preferences; avoid a one-item group if the setting fits View options. |
| ReaderActionMenu | feature/reader/.../ReaderActionMenu.kt:20 | Group navigation, reading appearance, and automation; use real icons, concise labels, selected/current hints, and dismiss after selection. |
| ReaderMenuSection | ReaderActionMenu.kt:82 | Section title plus stable focus order; no decorative empty section. |

## 7. Shared component specifications

### 7.1 Foundations

| Composable | Location | Required change/contract |
| --- | --- | --- |
| TeddReaderTheme and token accessors | core/designsystem/.../TeddReaderTheme.kt:20-154 | Preserve one token source. Default darkTheme should follow app/system state at app boundary. Add semantic color roles only when a real component needs them. |
| TeddScaffold | TeddWrappers.kt:218 | Own contentWindowInsets and pass consumed PaddingValues. Do not apply global padding outside it. |
| TeddTopBar | TeddWrappers.kt:233 | Replace text Back buttons; handle status-bar inset, title ellipsis, subtitle variant, actions, and proper navigation icon semantics. |
| TeddCard | TeddWrappers.kt:27 | Use only for meaningful grouping; no card inside card; caller owns content padding. |
| TeddPreviewSurface | core/ui/.../TeddPreviewSurface.kt | Provide predictable background/padding only for previews; no production use. |
| Dp.dpToPx, Dp.dpToSp, Float.pxToDp, Float.pxToSp | core/ui/.../extension/ConvertUtils.kt:19-28 | Density-aware conversion helpers only; remain side-effect free, read LocalDensity once per call, and stay out of layout policy decisions. |

### 7.2 Actions and selection

| Composable | Location | Required change/contract |
| --- | --- | --- |
| TeddButton | core/ui/.../TeddButton.kt:15 | Primary/secondary/text/destructive emphasis; loading/disabled states; 48 dp minimum; compact layouts may fill width. |
| TeddIconButton | core/ui/.../TeddIconButton.kt:11 | Require description in production, 48 dp target, vector icon slot, selected/toggled state where relevant. |
| TeddChip | TeddWrappers.kt:42 | Use Material selectable surface behavior and selected semantics; preserve the minimum touch target while clipping ripple to the visible pill; no punctuation-based state. |
| Material DropdownMenu | Direct call site | Use directly rather than through a pass-through wrapper; constrain width/height, safe edge offset, predictable dismissal and focus restoration. |
| TeddDropdownMenuItem | TeddDropdownMenu.kt:34 | 48 dp minimum, concise text, optional icon/checkmark, disabled explanation outside the row. |
| TeddSwitchRow | core/ui/.../TeddSwitchRow.kt:23 | Whole row is one switch semantic; title/value wrap; visual switch is not a second focus target. |
| TeddCheckboxRow | core/ui/.../TeddCheckboxRow.kt:23 | Same single-target rule; use only for independent multi-select choices. |
| TeddRadioRow | core/ui/.../TeddRadioRow.kt:23 | Same single-target rule; parent option group supplies selectable-group semantics. |

### 7.3 Data, input, and feedback

| Composable | Location | Required change/contract |
| --- | --- | --- |
| TeddListItem | TeddWrappers.kt:73 | Full-width ripple, internal padding, stable leading/trailing slots, two-line title support, disabled/selected semantics. |
| TeddInfoRow | TeddWrappers.kt:128 | Adaptive row/stack layout, selectable long values, no value clipping. |
| TeddTextField | TeddWrappers.kt:173 | Label, placeholder, supporting/error text, keyboard options/actions, single/multiline contract, IME safety. |
| TeddSearchField | TeddWrappers.kt:196 | Search semantics, clear action, ImeAction.Search callback, loading/disabled state. |
| TeddSliderRow | core/ui/.../TeddSliderRow.kt:33 | Compact vertical layout, formatted value semantics, live draft plus commit callback, no clipping. |
| TeddSlider | TeddSliderRow.kt:80 | Preserve platform slider behavior and minimum interaction bounds; expose value range/steps semantics. |
| TeddOptionGroup | core/ui/.../TeddOptionGroup.kt:17 | Heading, optional description, selectable-group semantics when applicable, consistent gaps without nested cards. |
| TeddEmptyState | core/ui/.../TeddEmptyState.kt:20 | One explanation and zero/one action; bounded width; centered only when it improves scanning. |
| TeddErrorBanner | TeddWrappers.kt:147 | Error live region, concise message, optional one recovery action, destructive color only for actual errors. |
| TeddLoadingIndicator | core/ui/.../TeddLoadingIndicator.kt:20 | Inline progress with optional message and live-region semantics. |
| TeddFullScreenLoadingIndicator | TeddLoadingIndicator.kt:43 | Preserve active screen background and safe insets; no unrelated navigation replacement. |

## 8. Reader shared UI

| Composable | Location | Target contract |
| --- | --- | --- |
| ReaderChromeSurface | core/ui/.../reader/ReaderChrome.kt:18 | Reader palette surface, no hardcoded app colors, safe elevation/shadow by edge, full-width hit region. |
| ReaderTopControls | core/ui/.../reader/ReaderControls.kt:33 | Back, ellipsized title, bookmark, menu; 48 dp actions; top inset on content only. |
| ReaderBottomControls | ReaderControls.kt:81 | Progress plus actions; two-row compact variant below 360 dp; navigation inset on content only. |
| ReaderProgressBar | core/ui/.../reader/ReaderProgressBar.kt:21 | Slider/progress behavior matches interactivity; announces current/total/percent; handles total 0/1 safely. |
| ReaderPageLabel | ReaderProgressBar.kt:86 | Human-readable page/percent labels without duplicated semantics. |
| ReaderOptionPreview | core/ui/.../reader/ReaderOptionPreview.kt | Bounded sample, active ReaderStyle colors/type, non-interactive and excluded from redundant accessibility traversal where appropriate. |
| ReaderPageSurface overloads | core/ui/.../reader/ReaderPageSurface.kt:28,50,65 | One content-padding policy; line-length cap; compact/comfortable/wide presets based on constraints, not device orientation. |
| ReaderBottomActionBar | feature/reader/.../ReaderBottomActionBar.kt:22 | Previous, next, auto-scroll, progress; disabled boundary states; no duplicate page actions. |

## 9. Pager, animation, and rendering Composables

These Composables are implementation variants of one interaction contract. Visual changes must not fork gesture semantics.

| Composable group | Locations | UX contract |
| --- | --- | --- |
| ReaderPager, ReaderScrollPager | ReaderPager.kt:69,174 | One tap-zone model, one drag threshold model, stable keys, continuous animation, boundary feedback without fake page moves. |
| GoogleCurlPager, GoogleCurlLayer | ReaderPager.kt:260,332 | Curl follows pointer; cancel returns smoothly; commit uses distance/velocity; accessibility actions remain available. |
| AppleReferenceCurlPager | ReaderPager.kt:478 | Same commit/cancel and boundary rules; no unique navigation semantics. |
| FoundationEffectPager, FoundationCurlPager | FoundationPagerEffects.kt:57,256 | Preserve pager state during chrome visibility changes; animation selection must not change navigation outcome. |
| FoundationPageFlipAwareBox/HalfBox | FoundationPagerEffects.kt:281,310 | Pure rendering layers; no pointer ownership or semantics duplication. |
| FoundationPagerCurlReferenceImpl | FoundationPagerCurlReferenceImpl.kt:62 | Same gesture contract and stable page keys. |
| FoundationPagerFluidReferenceImpl/Page/ClipBox | FoundationPagerFluidReferenceImpl.kt:47,275,322 | Rendering state stays local; page navigation callback fires once per committed gesture. |
| PdfPageSurface | feature/reader/.../pdf/PdfPageSurface.kt:21 | Use active reader background around page; loading/error/unavailable states remain readable and navigable. |
| PlatformPdfPageSurface | PdfPageSurface.kt:46 plus Android/iOS actuals | Platform rendering only; common state/copy/semantics remain consistent. |
| PdfPlaceholderSurface | PdfPageSurface.kt:56 | Clear unavailable/error message, optional retry if supported, page number context. |
| PlatformPageCurlShaderOverlay | PageCurlShader.kt:9 plus actuals | Decorative only; invisible to semantics; never blocks pointer input. |

## 10. Platform/provider Composables

| Composable | Location | Contract |
| --- | --- | --- |
| rememberDocumentImporter expect/actual | app/reader/.../DocumentImporter.kt:32 and platform actuals | Stable remembered object/callbacks; platform picker lifecycle only; no visible UI. |
| rememberPlatformReaderModule expect/actual | app/reader/.../ReaderAppModule.kt:95 and platform actuals | Stable DI module per composition; no UI/state reset on recomposition. |
| readerSystemBarsInsets expect/actual | feature/reader/.../ReaderInsets.kt:6 and platform actuals | Return platform-correct safe/system insets without applying padding itself. |
| ReaderSystemBarsEffect expect/actual | core/ui/.../system/ReaderSystemBarsEffect.kt:6 and platform actuals | Apply active reader background/icon contrast/fullscreen/keep-screen-on; restore prior window state on disposal. |

## 11. Content and visual language

- Voice: short, calm, sentence case.
- Replace implementation enum names such as CURL_PAGER or HORIZONTAL with “Page curl” and “Horizontal.”
- Replace text glyph icons (←, ☆, ⋮, ‹, ›, ▶) with shared vector resources.
- Card elevation is for grouping, not every section. Lists should read as lists.
- Default body line length: approximately 45-75 characters. Reader user settings override font/line height, not semantic hierarchy.
- Selected color/theme previews must meet contrast requirements before they can be committed.

## 12. Preview and test matrix

### 12.1 Required previews

Each public reusable component:

- enabled and disabled,
- compact 280 dp,
- regular 360 dp,
- long Korean/English text,
- font scale stress where preview tooling permits,
- light and dark when color behavior differs.

Each major screen:

| Screen | Required states |
| --- | --- |
| Home | loading, empty, populated, filtered-empty, error; 280/360/840 dp |
| Search | blank, loading, no results, results, error, IME-safe compact; 280/360/720 dp |
| Bookmarks | empty, populated, edit sheet, delete confirmation; 280/360/840 dp |
| Document info | loading, missing metadata, long values, populated; 280/360/840 dp |
| Reader | loading, error, light/dark/sepia, chrome shown/hidden, one/two pane, active sheet; 280/360/600/840 dp |
| Settings | loading and populated; 280/360/720 dp |

### 12.2 Automated checks

- Pure layout decisions: breakpoint and pane-count unit tests.
- State reducers/ViewModels: loading/empty/error/populated transitions, filter/query persistence, destructive confirmation state.
- Semantics tests: labels, roles, selected/checked state, click target uniqueness, progress descriptions.
- Gesture tests: tap zones, drag threshold, non-consumption, one callback per gesture, auto-scroll cancellation.
- Screenshot tests: compact/regular/expanded and light/dark for major screens.
- Build gates: Android host tests, iOS simulator tests, Android assembleDebug, full build before release.

## 13. Non-goals

- No social/store/cloud library features.
- No new design-system dependency for layout alone.
- No rewrite of pager algorithms solely for visual cleanup.
- No experimental adaptive Grid API unless explicitly approved; stable Lazy layouts and BoxWithConstraints are sufficient.
- No disabled “coming later” controls in production UI.
