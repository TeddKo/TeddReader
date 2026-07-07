# UI/UX Theme Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:brainstorming` before changing behavior, then `superpowers:executing-plans` or `superpowers:subagent-driven-development` to implement phase-by-phase. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:core:designsystem`, `:core:ui`, and `:feature:*:impl` UI를 reader 서비스답게 고도화한다.

**Architecture:** `core/designsystem`은 token/theme만, `core/ui`는 stateless 공용 Compose 컴포넌트만, `feature:*:impl`은 화면별 composition과 UiState binding만 담당한다. 비즈니스 로직, repository, parser, datastore 스키마는 이 플랜 범위 밖이다.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3, build-logic convention, Koin ViewModel, current feature api/impl module structure.

---

## 0. Current Diagnosis

현재 UI가 구려 보이는 핵심 원인:

- `TeddReaderSpacing`이 `xSmall~xLarge`뿐이라 화면/카드/시트/reader margin의 밀도 체계가 없다.
- Typography는 Material3 기본 scale만 있고 reader 앱용 역할(`readerBody`, `documentTitle`, `settingLabel`, `statValue`)이 없다.
- Color는 warm paper 계열 방향은 있으나 brand identity, semantic accent, state layer, reader palette가 약하다.
- Shape/elevation/motion/icon-size token이 없어 각 컴포넌트가 평면적이다.
- `feature` 화면은 `Button`, `Text`, `Column`, `24.dp` 중심의 scaffold 수준이다.
- Reader controls가 `Text("←")`, `Text("☆")`, `Text("Aa")` 같은 글리프 아이콘에 의존한다.
- BottomSheet/Menu는 기능은 있으나 header, grouping, preview area, sticky action, iconography, density가 부족하다.
- `ReaderScreen.kt`가 너무 크고 option sheet/reader chrome/surface logic이 섞여 있다.
- `ReaderColor.toColor()`는 `Long` ARGB를 Compose packed `Color(Long)`로 해석할 위험이 있어 preview crash 재발 가능성이 있다. `Color(argb.toInt())` 계열로 고정해야 한다.

## 1. UX Direction

추천 방향은 **“Calm Library / Focused Reader”**.

- Library/Home: 문서 관리 앱처럼 명확한 카드, format badge, progress, 최근 읽기 CTA.
- Reader: 본문이 주인공. control chrome은 반투명 glass surface, 터치 시만 노출, 48dp 이상 hit target.
- Settings/Options: menu로 카테고리 선택 → bottom sheet에서 조작. slider/radio/switch는 preview와 함께 제공.
- Visual identity: warm paper + ink indigo + bookmark amber + sepia copper. Dark는 OLED black이 아니라 눈 피로 적은 night ink.

## 2. Approach Options

### Option A — Minimal Facelift

기존 컴포넌트에 색/간격만 입힌다.

- 장점: 빠름.
- 단점: 구조적 구림(`Text` 아이콘, 큰 화면 대응, screen consistency)은 남음.

### Option B — Token-first Design System Refresh (Recommended)

Design token을 확장하고 core/ui 컴포넌트를 재설계한 뒤 feature 화면을 교체한다.

- 장점: 이후 reader 옵션/홈/검색/북마크/문서정보가 같은 UX 언어를 공유한다.
- 단점: Phase 1~3 선행 작업 필요.

### Option C — Full Custom Reader Skin

custom canvas, texture, advanced motion, asset-heavy UI까지 간다.

- 장점: 가장 차별화 가능.
- 단점: 현재 단계에서는 과함. CMP 리소스/성능/프리뷰 리스크 큼.

**결정:** Option B로 간다. Option C 요소는 reader palette/background image phase에서만 일부 흡수한다.

## 3. Phase Plan

### Phase 1: Designsystem Token 고도화

**Files:**
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderColors.kt`
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderTypography.kt`
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderSpacing.kt`
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderTheme.kt`
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/ReaderColors.kt`
- Create: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderShapes.kt`
- Create: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderElevation.kt`
- Create: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderMotion.kt`
- Create: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderIconography.kt`

Tasks:

- [ ] Brand palette 정의: `InkIndigo`, `PaperCream`, `BookmarkAmber`, `SepiaCopper`, `Sage`, `NightInk`.
- [ ] Material3 light/dark color scheme를 brand palette로 재작성한다.
- [ ] Reader palette를 `Light`, `Dark`, `Sepia`, `Night`, `HighContrast`로 확장한다.
- [ ] `ReaderColor.toColor()` ARGB Long 변환을 preview-safe하게 수정한다.
- [ ] Spacing scale을 `none, xxxs, xxs, xs, sm, md, lg, xl, xxl, xxxl` + semantic spacing(`screenPadding`, `cardPadding`, `sheetPadding`, `readerMargin`)으로 확장한다.
- [ ] Shape token: `extraSmall, small, medium, large, extraLarge, full`.
- [ ] Elevation token: `flat, raised, overlay, modal`.
- [ ] Motion token: `fast, normal, slow`, easing naming만 정의한다. custom animation 구현은 feature phase로 미룬다.
- [ ] Typography role 확장: `documentTitle`, `documentMeta`, `settingTitle`, `settingDescription`, `statValue`, `readerBody`, `readerCaption`.
- [ ] `TeddReaderTheme`가 color/typography/spacing/shape/elevation/motion locals를 모두 제공하게 한다.

### Phase 2: Core UI Component 재설계

**Files:**
- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/*.kt`
- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/*.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddScaffold.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddTopBar.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddListItem.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddChip.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddCard.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddSearchField.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddInfoRow.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddErrorBanner.kt`

Tasks:

- [ ] 모든 core/ui 컴포넌트의 hardcoded `dp`를 spacing token으로 교체한다.
- [ ] `TeddButton` variants: primary, tonal, outline, text, danger.
- [ ] `TeddIconButton`은 `contentDescription` 필수 API로 바꾸고 size/container variants를 둔다.
- [ ] `TeddModalBottomSheet`에 handle, header, description, section spacing, optional sticky footer slot을 추가한다.
- [ ] `TeddDropdownMenuItem`에 leading icon, trailing text, destructive style, selected style을 추가한다.
- [ ] `TeddSwitchRow`, `TeddCheckboxRow`, `TeddRadioRow`, `TeddSliderRow`를 공통 option row visual language로 통일한다.
- [ ] `TeddEmptyState`, `TeddLoadingIndicator`, `TeddErrorBanner`를 feature 화면에서 바로 쓸 수 있게 polished state component로 만든다.
- [ ] `TeddCard`, `TeddListItem`, `TeddChip`, `TeddInfoRow`, `TeddSearchField` 추가.
- [ ] component preview를 light/dark/compact state로 늘린다.

### Phase 3: Reader Core UI 고도화

**Files:**
- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderControls.kt`
- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderPageSurface.kt`
- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderProgressBar.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderChrome.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderPalettePreview.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderOptionPreview.kt`

Tasks:

- [ ] Reader top/bottom controls를 translucent glass surface로 재설계한다.
- [ ] Progress는 page label + thin progress + optional percent label을 지원한다.
- [ ] Reader surface에 semantic padding preset(`compact`, `comfortable`, `wide`)을 둔다.
- [ ] Font/theme option sheet 안에 실제 reader preview card를 공용화한다.
- [ ] Text glyph 아이콘을 제거할 준비를 한다. Material vector 또는 composeResources vector icon 전략을 정한다.

### Phase 4: Feature UI 리디자인

**Files:**
- Modify: `feature/home/impl/src/commonMain/kotlin/.../HomeScreen.kt`
- Modify: `feature/home/impl/src/commonMain/kotlin/.../component/DocumentListItem.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/.../ReaderScreen.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/.../component/*.kt`
- Modify: `feature/search/impl/src/commonMain/kotlin/.../SearchScreen.kt`
- Modify: `feature/bookmarks/impl/src/commonMain/kotlin/.../BookmarksScreen.kt`
- Modify: `feature/document-info/impl/src/commonMain/kotlin/.../DocumentInfoScreen.kt`
- Modify: `feature/settings/impl/src/commonMain/kotlin/.../ReaderSettingsSheet.kt`

Tasks:

- [ ] Home: hero header, primary import CTA, recent document card, format chip, progress/metadata row.
- [ ] Home sort/filter: radio list 대신 menu + filter chips 또는 segmented chips로 변경.
- [ ] Reader: `ReaderScreen.kt`를 `ReaderChrome`, `ReaderActiveSheetHost`, `ReaderContentSurface`로 분리한다.
- [ ] Reader action menu: category grouping, leading icons, selected/active indicators.
- [ ] Reader option sheets: `View`, `Font`, `Theme`, `PageTurn`, `AutoScroll`, `Brightness`, `Controls`별 preview + section layout 적용.
- [ ] Search: top search field 고정 느낌, result card, snippet highlight visual, empty/result/loading states.
- [ ] Bookmarks: card list, note preview, edit/delete actions를 destructive style로 정리.
- [ ] DocumentInfo: key stat cards + detail list + session history section.
- [ ] Settings sheet: 현재 Text 3개 수준에서 reader setting summary + option rows로 고도화.

### Phase 5: Preview, Accessibility, Validation

Tasks:

- [ ] 각 core/ui component preview는 light/dark 최소 2개를 둔다.
- [ ] 각 feature screen preview는 empty/loading/content/error state 중 최소 2개를 둔다.
- [ ] 모든 clickable icon은 contentDescription을 가진다.
- [ ] Touch target 48dp 이상 유지.
- [ ] Color contrast: body text/background, controls/background, error/banner 최소 AA 목표.
- [ ] Preview crash 회귀 방지: `ReaderColorsTest`에 ARGB conversion test 추가.
- [ ] Build 검증:

```bash
./gradlew :core:designsystem:testAndroidHostTest
./gradlew :core:ui:testAndroidHostTest
./gradlew :feature:reader:impl:testAndroidHostTest
./gradlew :androidApp:assembleDebug
```

## 4. Non-goals

- 비즈니스 로직, parser, repository, datastore 변경 금지.
- APK UI/asset 복제 금지. 참고한 UX 패턴만 Compose로 재해석.
- 과한 custom DSL 금지. 먼저 Material3 기반 thin wrapper로 간다.
- Android-only API를 commonMain에 넣지 않는다.
- companion object 남발 금지. enum/sealed/top-level immutable token을 우선한다.

## 5. Acceptance Criteria

- Preview가 crash 없이 열린다.
- feature 화면에서 raw `Button`/`Text`/`24.dp` 중심의 scaffold 느낌이 사라진다.
- Reader option UX가 menu → bottom sheet → preview/controls 흐름으로 일관된다.
- Home/Search/Bookmarks/DocumentInfo/Settings가 같은 card, spacing, typo, color 언어를 공유한다.
- `core/designsystem`은 token/theme만, `core/ui`는 stateless component만 가진다.
- dark/light/sepia reader palette가 눈 피로와 대비를 고려해 구분된다.
