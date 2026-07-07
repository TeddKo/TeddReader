# Reader Gesture, Page Animation, and System Bars Plan

> Draft from brainstorming. Do not implement until approved.

## 결론

이전 APK 분석은 부족했다. manifest/resource 중심이라 reader 핵심인 gesture, page turn, immersive/system bar를 제대로 커버하지 못했다. 이번 재분석 기준으로 현재 TeddReader의 reader UX 결함은 명확하다.

## 현재 코드 진단

- `TeddReaderApp`가 `ReaderNavHost` 전체에 `systemBarsPadding()`을 적용한다. reader도 항상 system inset 안쪽에 갇혀 진짜 fullscreen이 아니다.
- `MainActivity`는 `enableEdgeToEdge()`만 호출하고 status/navigation bar color를 theme/reader background와 동기화하지 않는다.
- `ReaderContent`는 root `Box.clickable`만 있다. `pointerInput`, `HorizontalPager`, `VerticalPager`, `draggable`, `scrollable`이 없다.
- `PageTurnMode`, `PageAnimation`은 DataStore/ViewModel/UI sheet에만 있고 실제 렌더링에 연결되지 않았다.
- `fullscreen`, `keepScreenOn` state는 존재하지만 Android window/system UI에 적용되지 않는다.
- `ReaderPageSurface`는 현재 page text 하나만 그린다. 이전/다음 page를 같이 그리지 않으므로 swipe 중 page transition이 불가능하다.
- Reader top/bottom controls는 system bar inset, navigation bar inset, immersive mode show/hide와 분리되어 있다.

## APK 재분석 신호

분석 범위: `/Users/kominhyuk/Downloads/reader.apk` manifest, resource names, arrays/strings, class/method names. 구현 복사 금지.

- `ActivityTxt`는 `GestureDetector`, `VelocityTracker`, `Scroller`, `MotionEvent`, `NewCurl3D`, `ScrollView2`, `ScrollImage`를 사용한다.
- reader constants에 `PAGE_UP`, `PAGE_DOWN`, `INIT_CURL3D`, `DELAY_CURL3D_TOUCH_UP`, `CACHE_NEXT_CHAPTER_CURL`, `SCROLL_NO_DELAY`, `CHECK_DUAL_PAGE`가 있다.
- 설정 배열 `flip_animation_list`: `None`, `Real Page Turning Effect`, `Flip Horizontally`, `Flip Vertically`, `Fade In`, `Real Page 2(Click Only)`.
- strings에 `Allow fling horizontally to turn page`, `Immersive mode (hide system navigation bar)`, `Full screen mode`, `Mini Status Bar`, `Shift Horizontally`, `Shift Vertically`, `Disable vertical scrolling on touch`가 있다.
- resources에 `txt_top`, `txt_bottom`, `bar_seek`, `statusbar_setting`, `page_hori.png`, `page_vert.png`, `dualpage.png`, `reader_seek_thumb.png`, `toolbar_shadow`가 있다.

가져올 것은 UX 패턴뿐이다: gesture 기반 page turn, page animation 선택, immersive reader, mini status/progress, system bar/theme 동기화.


## 추가 진단: TXT 한글 인코딩 깨짐

현재 앱의 TXT 한글 깨짐은 Compose `Text` 렌더링 문제가 아니라 import 단계의 bytes-to-String 디코딩 문제다.

Current flow:

```text
Android/iOS DocumentImporter
-> readBytes()
-> OpenDocumentUseCase
-> DocumentRepositoryImpl.importDocument()
-> source.bytes.decodeToString()
-> TxtDocumentParser.parse()
-> Room search index
-> ReaderViewModel.pageText
-> ReaderPageSurface Text()
```

Root cause:

```kotlin
text = source.bytes.decodeToString()
```

`decodeToString()`은 UTF-8 전제로 동작한다. 하지만 한국어 `.txt`는 아직 CP949/EUC-KR/MS949/UTF-16/UTF-8 BOM 형태가 흔하다. CP949 한글 bytes를 UTF-8로 읽으면 `안녕하세요`가 `�ȳ��ϼ���`처럼 깨진다. 따라서 깨진 문자열이 parser, Room search index, pagination, reader UI까지 그대로 전파된다.

Existing tests miss this because `DocumentRepositoryImplTest`는 UTF-8 `encodeToByteArray()`만 검증하고, `TxtDocumentParserTest`는 이미 정상 `String`을 넘긴다. CP949/EUC-KR byte import 회귀 테스트가 없다.

## Phase 0: TXT Korean Encoding Hardening

Files:
- Create: `core/data/src/commonMain/kotlin/com/tedd/teddreader/core/data/parser/TxtTextDecoder.kt`
- Modify: `core/data/src/commonMain/kotlin/com/tedd/teddreader/core/data/repository/DocumentRepositoryImpl.kt`
- Modify: `core/data/src/commonTest/kotlin/com/tedd/teddreader/core/data/parser/TxtDocumentParserTest.kt`
- Modify: `core/data/src/commonTest/kotlin/com/tedd/teddreader/core/data/repository/DocumentRepositoryImplTest.kt`

Tasks:
- [ ] `TxtTextDecoder`를 추가하고 TXT import에서만 사용한다.
- [ ] BOM 우선 처리: UTF-8 BOM, UTF-16 LE/BE BOM.
- [ ] BOM이 없으면 valid UTF-8이면 UTF-8로 decode한다.
- [ ] UTF-8 invalid이면 Korean legacy fallback을 적용한다. Android/JVM은 `Charset.forName("MS949")` 또는 `EUC-KR`; iOS/KMP common 제약이 있으면 최소 Android actual부터 적용하고 common fallback은 replacement 최소화로 둔다.
- [ ] `DocumentRepositoryImpl.importDocument()`의 `source.bytes.decodeToString()`을 decoder 호출로 교체한다.
- [ ] CP949 bytes로 `안녕하세요`가 정상 저장되는 test를 추가한다.
- [ ] UTF-8 Korean bytes는 기존처럼 정상 동작하는 test를 유지한다.

Acceptance:
- CP949/EUC-KR TXT를 열었을 때 한글이 깨지지 않는다.
- UTF-8 TXT 기존 동작이 깨지지 않는다.
- 깨진 문자열이 search index와 pagination으로 저장되지 않는다.

## 목표 UX

- 문서 진입 시 system navigation/status bar는 기본 숨김.
- 화면 탭 시 reader controls와 system bars가 같이 나타남.
- controls가 사라지면 system bars도 다시 숨김.
- horizontal mode: 좌우 swipe/fling으로 이전/다음 page.
- vertical mode: 상하 swipe/fling으로 이전/다음 page.
- continuous mode: 이후 phase에서 page list vertical scroll로 처리. MVP에서는 page swipe와 분리한다.
- system bar color는 reader background 또는 app surface와 동기화한다.
- non-reader 화면은 safe/system padding 유지.

## 접근 옵션

### A. 최소 수정

`pointerInput`으로 drag threshold만 잡고 page 이동. system bar hide/show만 붙임.

- 장점: 빠르다.
- 단점: animation/real page 효과는 여전히 허접하다.

### B. Compose Reader Pager 도입 — 추천

현재/이전/다음 page slot을 UiState에 노출하고, `ReaderPager`가 drag offset과 animation을 직접 그린다.

- 장점: horizontal/vertical/page animation/system bar를 한 번에 제어 가능.
- 단점: ViewModel UiState에 page slot 추가 필요.

### C. 완전한 물리 page curl 엔진

Canvas/Skia 기반 page mesh/curl renderer를 만든다.

- 장점: 가장 근접한 실제 페이지 넘김.
- 단점: 지금 당장 과하다. Compose에서 composable snapshot/mesh deformation까지 가면 리스크가 크다.

결정: **B로 기능 완성 후 C의 일부 효과를 단계적으로 흡수**한다.

## Phase 1: Insets/System Bars 바로잡기

Files:
- Modify: `app/reader/src/commonMain/kotlin/com/tedd/teddreader/app/reader/TeddReaderApp.kt`
- Modify: `app/reader/src/commonMain/kotlin/com/tedd/teddreader/app/reader/navigation/ReaderNavHost.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/system/ReaderSystemBarsEffect.kt`
- Create: `core/ui/src/androidMain/kotlin/com/tedd/teddreader/core/ui/system/ReaderSystemBarsEffect.android.kt`
- Create: `core/ui/src/iosMain/kotlin/com/tedd/teddreader/core/ui/system/ReaderSystemBarsEffect.ios.kt`

Tasks:
- [ ] `TeddReaderApp`의 global `.systemBarsPadding()` 제거.
- [ ] Home/Search/Bookmarks/DocumentInfo 같은 non-reader 화면은 각 screen에서 `safeContentPadding()` 유지/추가.
- [ ] Reader 화면 root는 fullscreen `fillMaxSize()` 유지.
- [ ] Reader top controls에 `statusBarsPadding()` 적용.
- [ ] Reader bottom controls에 `navigationBarsPadding()` 적용.
- [ ] Android actual에서 `WindowInsetsControllerCompat`로 controls hidden 시 system bars hide, controls visible/sheet visible 시 show.
- [ ] Android actual에서 `window.statusBarColor`, `window.navigationBarColor`를 reader background/app surface로 설정.
- [ ] dispose 시 system bars show + app 기본 color 복구.
- [ ] `keepScreenOn`은 Android `LocalView.current.keepScreenOn`에 연결.

Acceptance:
- Reader 본문은 전체 화면에 깔린다.
- 탭해서 controls를 보이면 system bars도 보인다.
- 다시 탭해서 controls를 숨기면 system bars도 숨는다.
- 다른 화면으로 나가면 system bars가 정상 표시된다.

## Phase 2: Reader Page Slot 모델

Files:
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderUiState.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderViewModel.kt`

Tasks:
- [ ] `ReaderPageUi` 추가: `page: Int`, `text: String`, `isPdf: Boolean` 정도만 둔다.
- [ ] `ReaderUiState`에 `previousPage`, `currentPage`, `nextPage` 추가.
- [ ] text 문서는 `currentPageWindows`에서 현재±1만 UiState로 노출한다. 전체 pages를 UiState에 넣지 않는다.
- [ ] PDF는 text 대신 page index/document uri로 렌더링한다.
- [ ] `moveToPage()`는 `pageText`만 바꾸지 말고 page slots도 같이 갱신한다.

Acceptance:
- swipe 중 이전/현재/다음 page를 동시에 그릴 수 있다.
- 큰 책에서도 UiState에 전체 page text를 올리지 않는다.

## Phase 3: Horizontal/Vertical Swipe Pager

Files:
- Create: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/component/ReaderPager.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderScreen.kt`

Tasks:
- [ ] `ReaderPager`는 `PageTurnMode.HORIZONTAL`에서 horizontal drag를 처리한다.
- [ ] `ReaderPager`는 `PageTurnMode.VERTICAL`에서 vertical drag를 처리한다.
- [ ] drag threshold: 화면 길이의 18% 또는 96dp 중 작은 값.
- [ ] fling threshold: velocity 기반 page turn.
- [ ] threshold 미달이면 current page로 spring back.
- [ ] page boundary에서는 overscroll resistance만 보여주고 page는 바꾸지 않는다.
- [ ] tap과 drag 충돌 방지: movement가 touch slop을 넘으면 controls toggle 금지.
- [ ] auto-scroll 중 user drag가 들어오면 auto-scroll stop.

Acceptance:
- 좌우 swipe로 page 이동.
- 상하 swipe로 page 이동.
- 단순 tap은 controls toggle.
- swipe는 controls toggle을 발생시키지 않음.

## Phase 4: Page Animation 연결

Files:
- Modify: `core/common/src/commonMain/kotlin/com/tedd/teddreader/core/common/model/ReaderModels.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/component/ReaderPager.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderScreen.kt`

Tasks:
- [ ] 기존 enum을 최소 확장한다: `NONE`, `SLIDE`, `FADE`, `SCROLL`, `BOOK_CURL`, `SHEET_FLIP`.
- [ ] UI label은 trademark 없이 `Real page`, `Sheet flip` 등 중립명 사용.
- [ ] `SLIDE`: current/next page translate.
- [ ] `FADE`: threshold 확정 후 crossfade.
- [ ] `SCROLL`: horizontal/vertical offset을 그대로 따라가는 기본 mode.
- [ ] `BOOK_CURL`: Google Books-like. edge pivot, rotationY/rotationX, shadow gradient, next page underlay.
- [ ] `SHEET_FLIP`: Apple Books-like. page sheet overlay, rounded fold highlight, shadow, content slide. true mesh curl은 하지 않는다.
- [ ] true physical curl은 별도 phase로 남긴다. 필요 시 Skia/custom Canvas로 구현.

Acceptance:
- option sheet에서 고른 animation이 실제 page turn에 반영된다.
- `BOOK_CURL`, `SHEET_FLIP`은 “진짜 page 느낌”을 주는 1차 구현이지만 APK 구현을 복사하지 않는다.

## Phase 5: Reader Controls/System UI 연동

Files:
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/ReaderScreen.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/component/ReaderBottomActionBar.kt`
- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/ReaderControls.kt`

Tasks:
- [ ] `ReaderSystemBarsEffect(visible = uiState.isControlsVisible || uiState.activeSheet != null)` 호출.
- [ ] top/bottom controls는 system inset 포함 후 배치.
- [ ] controls background는 `readerColors.controls` + translucent surface.
- [ ] progress bar가 disabled일 때 bottom progress 영역 숨김.
- [ ] text glyph icon은 임시 유지 가능. icon polish는 UI plan phase에서 처리.

Acceptance:
- system nav/status와 reader controls가 같은 show/hide lifecycle을 가진다.
- bottom bar가 navigation bar에 잘리지 않는다.

## Phase 6: PDF Reader parity

Files:
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/pdf/PdfPageSurface.kt`
- Modify: `feature/reader/impl/src/androidMain/kotlin/com/tedd/teddreader/feature/reader/impl/pdf/PdfPageSurface.android.kt`
- Modify: `feature/reader/impl/src/commonMain/kotlin/com/tedd/teddreader/feature/reader/impl/component/ReaderPager.kt`

Tasks:
- [ ] PDF도 same `ReaderPager` gesture path를 사용한다.
- [ ] current/prev/next page index를 렌더링한다.
- [ ] zoom 상태에서는 page turn swipe와 pan/zoom gesture 충돌을 분리한다. MVP는 zoom 1f일 때만 page turn swipe 활성.
- [ ] PDF page background는 reader/app background와 맞춘다.

Acceptance:
- PDF에서도 horizontal/vertical page swipe가 동작한다.
- zoom/pan과 page swipe가 서로 깨지지 않는다.

## Phase 7: 검증

Commands:

```bash
./gradlew :core:data:testAndroidHostTest
./gradlew :core:ui:testAndroidHostTest
./gradlew :feature:reader:impl:testAndroidHostTest
./gradlew :androidApp:assembleDebug
```

Manual checks:
- [ ] CP949/EUC-KR Korean TXT document: 한글이 깨지지 않음.
- [ ] UTF-8 Korean TXT document: 기존처럼 정상 표시.
- [ ] TXT document: horizontal swipe next/previous.
- [ ] TXT document: vertical swipe next/previous.
- [ ] TXT document: tap toggles controls/system bars.
- [ ] TXT document: controls hidden 상태에서 navigation bar 숨김.
- [ ] TXT document: bottom controls가 nav bar에 안 잘림.
- [ ] PDF document: page swipe 동작.
- [ ] theme 변경 후 system bar color가 배경과 어울림.
- [ ] rotate 후 page slots와 insets 정상.

## 우선순위

1. TXT Korean encoding hardening.
2. System bars/insets fix.
3. Horizontal/vertical swipe pager.
3. Slide/fade/scroll animation 연결.
4. `BOOK_CURL`, `SHEET_FLIP` 1차 구현.
5. PDF parity.
6. True physical curl은 별도 결정.
