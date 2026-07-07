# Reader UX/UI Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the reader screen feel like a real document/book reader: reading-first page surface, clean transient chrome, compact bottom controls, better option sheets, safe-area aware layout, and preview-backed visual checks.

**Architecture:** Keep business logic in existing `ReaderViewModel`/`ReaderUiState`. `core/ui` owns stateless reader primitives. `feature/reader/impl` composes screen UX and binds callbacks. Platform-only system UI work must stay outside `commonMain`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3, existing `core/designsystem` tokens, existing `core/ui` components.

---

## 0. Brutal Current Diagnosis

The previous “refresh” improved tokens/components but did not solve reader UX.

Current problems:

- `ReaderBottomActionBar.kt` dumps too many controls into one row. Progress + 7 buttons cannot fit on phone width, so visible glyph/text wraps and the bar looks broken.
- `ReaderTopControls` and bottom chrome are visually heavy pill surfaces spanning full width. They look like app toolbars, not transient reader chrome.
- `ReaderScreen.kt` uses one whole-page `clickable`; there are no clear tap zones for previous/next/controls. This blocks reader-native interaction design.
- Reader options are technically present, but sheets are raw lists of controls with weak preview/context. Font/theme/page-turn/brightness should show immediate reading preview.
- Safe-area/system-bar behavior is not treated as reader UX. Controls can feel cut off or fight navigation bars.
- `ReaderActionMenu` is a long grouped dropdown. It works, but it is not optimized for frequent reading actions.
- The page itself has no page frame/shadow/margins distinction. Text is placed on a background, but not presented as a deliberate reading surface.
- Previews do not cover compact width, dark mode, visible/hidden chrome, and option sheets enough to catch bad layout.

Core rule: reader UX must prioritize reading. Controls are temporary overlays, not permanent dashboard UI.

## 1. Product UX Target

Reader screen hierarchy:

1. Page content
2. Reading position and page navigation
3. Temporary reader chrome
4. Option sheets and secondary actions
5. App-level navigation

Target interaction:

- Tap center: show/hide chrome.
- Tap left/right zones: previous/next page.
- Bottom chrome: previous, progress, next, auto-scroll toggle only.
- Top chrome: back, title, bookmark, more menu only.
- More menu: search, bookmarks, TOC, go to page, view/font/theme/page-turn/auto-scroll/brightness/control/document info.
- Option sheets: grouped controls with reader preview where visual settings change the page.

Non-goals for this plan:

- No repository/parser/datastore changes.
- No new dependency or Material Icons dependency.
- No copied APK assets/code.
- No full custom page-curl engine in this pass. Page animation UX can be prepared, but real curl implementation belongs to a separate animation plan.

## 2. Visual Direction

Use the existing warm editorial design system:

- Page: warm paper / dark paper / sepia surface from `ReaderStyle.readerColors()`.
- Chrome: low-opacity reader controls, rounded but not huge, not full-dashboard.
- Text: title/content labels must truncate, never wrap inside chrome.
- Icons: until vector assets exist, keep glyphs short and single-line. Full meaning goes into `contentDescription`.
- Sheets: option controls grouped by user intent, not enum dumps.

Compact 360dp target:

```text
┌────────────────────────────────────┐
│ ←  Book title...             ☆ ⋮   │  transient top chrome
│                                    │
│          reading content           │
│                                    │
│                                    │
│ ‹   12 / 240  ━━━━━━━────   ›  ▶  │  compact bottom chrome
└────────────────────────────────────┘
```

## 3. File Map

### `core/ui`

- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderControls.kt`
  - Make top/bottom chrome layout compact and safe for narrow width.
  - Add optional content padding/token usage only.

- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderChrome.kt`
  - Tune chrome surface shape/elevation/padding to overlay style.
  - Keep stateless.

- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderPageSurface.kt`
  - Add page content padding presets or page-frame mode if needed.
  - Keep existing `ReaderPageSurface(text, style, modifier, contentPadding)` overload.

- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderProgressBar.kt`
  - Add compact progress layout support if current one still consumes too much height.

- Optional create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderTapZones.kt`
  - Stateless overlay for left/center/right tap zones.
  - Only create if it reduces `ReaderScreen.kt` complexity.

### `feature/reader/impl`

- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderScreen.kt`
  - Separate page content, tap zones, chrome overlay, active sheet host.
  - Keep callback wiring and ViewModel behavior unchanged.

- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/component/ReaderBottomActionBar.kt`
  - Reduce bottom bar to navigation/progress/autoscroll.

- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/component/ReaderActionMenu.kt`
  - Keep all secondary actions reachable.
  - Improve grouping/order and labels.

- Optional create: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/component/ReaderTopActionBar.kt`
  - Only if top chrome logic becomes too large inside `ReaderScreen.kt`.

### Platform/system UI

- Inspect existing app/platform entry points before editing.
- If fullscreen/system bar behavior is implemented, keep Android-only calls in Android source sets. Do not put Android APIs in `commonMain`.

## 4. Phase Plan

### Phase 1: Reader chrome information architecture

Goal: stop showing everything everywhere.

- [ ] In `ReaderBottomActionBar.kt`, remove bottom shortcuts for font/theme/page-turn/auto-scroll-options.
- [ ] Keep bottom controls only:
  - previous page
  - compact progress
  - next page
  - auto-scroll start/stop
- [ ] In `ReaderScreen.kt`, remove deleted bottom callback arguments only.
- [ ] Confirm `ReaderActionMenu.kt` still exposes:
  - Search
  - Bookmarks
  - Table of contents
  - Go to page
  - View options
  - Font options
  - Theme options
  - Page turn options
  - Auto-scroll options
  - Brightness
  - Controls
  - Document info
- [ ] Verify:

```bash
./gradlew :feature:reader:impl:testAndroidHostTest --quiet
```

Expected: exit 0.

### Phase 2: Compact bottom chrome layout

Goal: no wrapping, no cramping at 360dp.

- [ ] In `ReaderBottomActionBar.kt`, visible button text must be one short glyph:

```kotlin
Text("‹", maxLines = 1)
Text("›", maxLines = 1)
Text(if (isAutoScrollEnabled) "Ⅱ" else "▶", maxLines = 1)
```

- [ ] Keep full labels in `contentDescription`.
- [ ] Set progress to compact mode:

```kotlin
ReaderProgressBar(
    pageIndex = pageIndex,
    showPageLabel = true,
    showPercentLabel = false,
)
```

- [ ] Add compact preview:

```kotlin
@Preview(widthDp = 360, heightDp = 96)
@Composable
private fun ReaderBottomActionBarCompactPreview() { ... }
```

- [ ] Static check:

```bash
grep -R "Auto-scroll\|Font\|Theme\|Page turn" -n \
  feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/component/ReaderBottomActionBar.kt || true
```

Expected: no visible-label usage except `contentDescription` strings.

### Phase 3: Top chrome polish

Goal: top chrome should be reader overlay, not app dashboard.

- [ ] In `ReaderTopControls`, ensure title is one line with ellipsis.
- [ ] Keep top actions to:
  - back
  - bookmark
  - more menu
- [ ] Add content descriptions to top glyph buttons in `ReaderScreen.kt`:
  - Back
  - Toggle bookmark
- [ ] Tune `ReaderChromeSurface` shape/padding/elevation so it feels light over the page.
- [ ] Add previews:
  - short title
  - long title
  - dark reader style

Validation:

```bash
./gradlew :core:ui:testAndroidHostTest :feature:reader:impl:testAndroidHostTest --quiet
```

Expected: exit 0.

### Phase 4: Page surface and reading comfort

Goal: page content must visually read like a document page.

- [ ] Keep `ReaderPageSurface(text, style, ...)` API compatible.
- [ ] Add or tune padding presets:
  - compact
  - comfortable
  - wide
- [ ] Ensure text preview includes Korean and Latin mixed text:

```text
가나다 ABC 123
문장 간격과 줄 높이 확인용 텍스트입니다.
```

- [ ] Ensure page background/text uses `ReaderStyle.readerColors()` and `readerTextStyle()` only.
- [ ] Add previews:
  - light
  - sepia
  - dark
  - large font

Validation:

```bash
./gradlew :core:ui:testAndroidHostTest --quiet
```

Expected: exit 0.

### Phase 5: Tap zone UX

Goal: reader screen should not rely on one ambiguous full-screen click.

- [ ] Replace single whole-page `clickable` behavior with explicit zones:
  - left zone: previous page
  - center zone: toggle controls
  - right zone: next page
- [ ] If auto-scroll is running, any tap stops auto-scroll before handling navigation/control toggle.
- [ ] Keep this in `ReaderScreen.kt` or create a small stateless `ReaderTapZones` if it reduces code.
- [ ] Do not change ViewModel logic.

Validation:

```bash
./gradlew :feature:reader:impl:testAndroidHostTest --quiet
```

Expected: exit 0.

### Phase 6: Option sheet UX cleanup

Goal: sheets should feel like reader settings, not enum dumps.

- [ ] `FontOptionsSheet`
  - Keep font size and line height sliders.
  - Keep font family radio rows.
  - Show reader preview card near the top or bottom.
  - Preview must include Korean text.

- [ ] `ThemeOptionsSheet`
  - Show theme options with a reader preview.
  - Keep custom colors disabled if no picker exists, but label must explain clearly.

- [ ] `PageTurnOptionsSheet`
  - Group mode and animation separately.
  - Do not imply real page-curl exists if implementation is not present.

- [ ] `AutoScrollOptionsSheet`
  - Make start/stop primary action obvious.
  - Speed label should be human-readable, e.g. `1.0x`, not raw float noise.

- [ ] `BrightnessOptionsSheet`
  - Keep description that this is an overlay dimmer, not system brightness.

Validation:

```bash
./gradlew :feature:reader:impl:testAndroidHostTest --quiet
```

Expected: exit 0.

### Phase 7: Action menu IA cleanup

Goal: more menu should be predictable.

- [ ] Keep sections:
  - Reading: Search, Bookmarks, TOC, Go to page
  - Appearance: View, Font, Theme, Brightness
  - Motion: Page turn, Auto-scroll
  - Info: Document info, Controls if still needed
- [ ] Keep leading glyphs short.
- [ ] Add/keep readable labels.
- [ ] Do not add icon dependency.

Validation:

```bash
./gradlew :feature:reader:impl:compileAndroidMain --quiet
```

Expected: exit 0.

### Phase 8: Safe-area and system UI UX

Goal: controls should not be cut off by status/navigation bars.

- [ ] In common UI, use Compose safe-area padding where appropriate for chrome overlays.
- [ ] Inspect Android app entry point before adding platform behavior.
- [ ] If fullscreen behavior is wired, Android-only APIs must stay in Android source sets.
- [ ] Keep `uiState.fullscreen` as the source of user intent; do not create duplicate state.

Validation:

```bash
./gradlew :androidApp:assembleDebug --quiet
```

Expected: exit 0.

### Phase 9: Preview and visual regression coverage

Goal: bad layout must be caught before running the app.

- [ ] Add previews for:
  - `ReaderScreen` controls visible
  - `ReaderScreen` controls hidden
  - compact 360dp reader
  - dark theme reader
  - font option sheet
  - theme option sheet
  - bottom action bar compact
- [ ] Each preview must use real-ish long title and Korean text.
- [ ] Keep previews compile-only; no screenshot test dependency.

Validation:

```bash
./gradlew :feature:reader:impl:compileAndroidMain --quiet
```

Expected: exit 0.

## 5. Acceptance Criteria

- Bottom action bar does not wrap visible text at 360dp width.
- Reader top/bottom chrome feels transient and compact.
- Page content remains the visual focus.
- Tap zones are explicit: left previous, center controls, right next.
- Font/theme sheets show reader preview with Korean text.
- All secondary actions remain reachable through `ReaderActionMenu`.
- Safe-area padding prevents chrome from being cut off.
- No new dependencies.
- No Android APIs in `commonMain`.
- No repository/parser/datastore changes.
- These commands pass:

```bash
./gradlew :core:ui:testAndroidHostTest --quiet
./gradlew :feature:reader:impl:testAndroidHostTest --quiet
./gradlew :androidApp:assembleDebug --quiet
```

## 6. Implementation Order

1. Bottom chrome IA and compact layout.
2. Top chrome polish.
3. Page surface reading comfort.
4. Tap zones.
5. Option sheets and action menu cleanup.
6. Safe-area/system UI pass.
7. Preview coverage.
8. Final Gradle validation.

This order fixes the visible UX break first, then improves the whole reader experience without touching business logic.
