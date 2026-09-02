# TeddReader

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.11.1-4285F4)
![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS-lightgrey)
![minSdk](https://img.shields.io/badge/minSdk-24-3DDC84)

*[English](README.md)*

기기에 이미 가지고 있는 문서를 읽는 리더입니다. TXT, EPUB, PDF, CBZ, 또는 이미지가 담긴 폴더를
열면 어디까지 읽었는지 기억합니다. Android 와 iOS 가 같은 Kotlin 코드와 같은 Compose UI 를
공유하며, 어디에도 업로드하지 않고 계정을 만들 필요도 없습니다.

## 무엇을 하나

**서재.** 기기 저장소에서 파일을 가져오거나 Google Drive 에서 고릅니다. 최근순·제목순·형식순으로
정렬하고, 형식으로 거르고, 직접 이름 붙인 폴더로 묶습니다. 홈 화면은 마지막에 읽던 문서를 가장
앞에 보여줍니다.

**읽기.** 페이지는 가로나 세로로 넘기거나 이어서 스크롤합니다. 태블릿이나 펼친 폴더블에서는 두
페이지를 나란히 펼치고 접히는 부분에 여백을 두어, 글자가 접힘선에 빠지지 않습니다 —
[두 페이지 펼침](#두-페이지-펼침) 참고.

**페이지 넘김.** 열 가지이고, 그중 셋은 손가락을 따라 움직이다가 놓는 지점에서 자리를 잡습니다 —
[페이지 넘김](#페이지-넘김) 참고.

**글자.** 크기, 줄 높이, 글꼴 계열(문서 자체 글꼴·고딕·세리프·고정폭), 그리고 굵기 300~600.
강조는 고른 굵기를 기준으로 정해지므로, 어느 굵기에서든 제목이나 굵은 문장이 본문과 같은 대비를
유지합니다. 본문은 실제로 측정한 줄 상자를 기준으로 다시 나누므로, 크기를 키우거나 굵기를 올려도
잘리지 않고 다시 흐릅니다.

**편안하게.** 페이지 색은 문서를 따르거나, 시스템을 따르거나, 라이트·다크·세피아 중에서 고릅니다.
디스플레이 자체의 최저 밝기보다 어둡게 읽을 수 있는 조광 오버레이가 있고, 자동 스크롤은 픽셀·줄·
페이지 단위로 넘어갑니다. 앱 언어는 시스템을 따르거나 한국어 또는 영어로 고정할 수 있습니다.

**읽던 자리 찾기.** 책갈피, 문서 내 검색, 페이지 이동, 진행률 슬라이더, 문서 정보 시트를 제공합니다.
읽던 위치는 문서마다 텍스트 기준점으로 저장되므로, 글꼴을 바꿔 페이지 번호가 전부 달라져도
그대로 유지됩니다.

## 형식

| 형식 | 한 페이지가 무엇인가 | 파서 |
| --- | --- | --- |
| TXT | 측정된 텍스트. 활자 설정이 바뀌면 다시 흐른다 | `TxtDocumentParser`, `TxtTextDecoder` |
| EPUB | 인라인 구조를 가진 측정된 텍스트. spine 아이템 하나씩 | `EpubDocumentParser`, `EpubXhtmlParser`, `EpubCssEngine`, `EpubNavigationParser` |
| PDF | 플랫폼 PDF 엔진이 렌더링한 페이지 하나 | `PdfDocumentParser`, `PdfMetadataReader` |
| CBZ | 정렬된 압축 항목 하나 | `ComicBookDocumentParser` |
| 이미지 | 고른 폴더 안의 파일 하나 | `ImageDocumentParser`, `ImageDimensionSniffer` |

`DocumentFormatDetector` 는 세 가지를 함께 봅니다. 어느 하나도 단독으로는 믿을 수 없기
때문입니다: 표시 이름, 소스가 보고한 MIME 타입, 그리고 PDF 와 래스터 이미지에 한해 파일 앞부분
바이트입니다. 클라우드 제공자는 `application/octet-stream` 을 보고할 수 있고, 콘텐츠 URI 로 연
문서는 확장자가 아예 없을 수도 있습니다. CBZ 는 의도적으로 MIME 타입이나 리터럴 `.cbz` 확장자로만
인식하고 시그니처로는 판별하지 않습니다 — 모든 CBZ 는 `.docx` 나 일반 `.zip` 과 같은 ZIP
시그니처를 공유하므로, 스니핑하면 그것들이 만화로 잘못 분류됩니다.

## 문서가 페이지가 되기까지

1. **가져오기.** `DocumentImporter` 가 파일을 앱 저장소로 복사합니다 — Android 는 SAF 와 Drive
   인텐트 센더, iOS 는 `UIDocumentPickerViewController` 와 `GoogleDrivePicker.swift` 브리지입니다.
   그 이후로 파일 시스템에 손을 대는 것은 `DocumentFileSource` 뿐입니다.
2. **섹션으로 파싱.** 형식별 파서가 바이트를 `ReaderSection` 으로 바꿉니다: 평면 텍스트와, 그
   텍스트 위의 문자 범위로 표현된 `ReaderBlock` 구조(문단·제목·인용·목록 항목·표 셀·이미지 등)
   입니다. 구조가 텍스트를 소유하지 않으므로 검색·책갈피·읽던 위치가 모두 같은 평면 문자열을
   기준으로 삼습니다.
3. **페이지로 나누기.** 페이지 경계가 정해지는 유일한 곳이 `TextPageLayoutEngine` 이고, 여기에는
   불변식이 하나 있습니다: **페이지는 절대 두 섹션에 걸치지 않는다.** EPUB spine 아이템 하나는 그
   자체로 독립된 문서이므로, 챕터 제목이 이전 챕터 마지막 페이지 중간이 아니라 새 페이지 맨 위에
   옵니다 — 그리고 어떤 섹션이든 이웃을 건드리지 않고 측정·저장·복원·추가할 수 있습니다.
4. **실제로 측정.** 페이지 나누기는 추정한 글자 수가 아니라 Compose 텍스트 레이아웃이 측정한 줄
   상자를 기준으로 `ReaderPageMeasureDispatcher` 위에서 돕니다. 크기나 굵기를 바꾸면 책이 다시
   흐릅니다.
5. **자리 기억.** 위치는 페이지 번호가 아니라 텍스트 기준점(섹션 안의 문자 오프셋)으로 저장되므로,
   페이지가 다시 나뉘어도 그대로 남습니다.

## 페이지 넘김

Foundation 페이저 하나 위에 열 가지가 올라가 있습니다.

| 효과 | 무엇이 구동하나 |
| --- | --- |
| 없음, 슬라이드, 페이드 | 페이저 오프셋 |
| 스크롤 | 이어지는 리스트. 페이지 경계 자체가 없다 |
| 플루이드 페이지, 원형 전환 | 페이저 오프셋. 드러나는 모양의 시작점은 터치 지점 |
| 무비 캐러셀 | 페이저 오프셋. 떠나는 페이지에 깊이감 디밍 |
| 컬 페이지, 3D 컬, 페이지 플립 | 손가락. 놓는 지점에서 자리를 잡는다 |

두 페이지 펼침에서 손가락으로 구동되는 셋은 전체를 밀어내지 않고 **책등에서 한 장만** 접으며, 그
한 장은 gutter 를 건널 수 있는 노드 하나에 그립니다 — 한 장을 두 pane 노드에 쪼개 그리면 사이
gutter 가 빈 채로 남아 화면 가운데 이음선처럼 보입니다.

### 3D 컬

시트가 물결치는 게 아니라 실린더에 감깁니다. leaf 를 따라 세 구간입니다: 책등에서 crease 까지는
평면, 그다음은 반지름 `r` 의 실린더에 호 길이 `PI * r` 만큼 감김, 그 뒤로는 다시 평면이 되어 책등
밖으로 뻗습니다. crease 는 시트의 끝이 선형으로 이동해 진행률 정확히 절반에서 책등을 지나도록
배치합니다 — 넘어가는 페이지가 spine 쪽으로 줄어들다 사라지는 대신 반대쪽 페이지를 덮는 것이 이
때문입니다. `r` 은 turn 의 양 끝에서 0 으로 수렴하므로 양쪽 정지 상태가 항등 매핑이고 leaf 가
평평하게 안착합니다.

표면 법선은 감김 각도만큼 돌아가므로 `PI / 2` 를 넘은 컬럼은 카메라에서 돌아서 leaf 의 뒷면으로
그려집니다 — 그 구간은 목적지가 소스와 반대로 흐르고, 그래서 turn 도중 뒷면이 좌우 반전되어 보이며
텍스처도 반대쪽 끝에서 읽어야 합니다. cast shadow 는 시트의 선행 엣지 바로 바깥에 깔리는데, 앞면은
감기는 쪽이고 뒷면은 시트의 끝입니다. 둘을 같은 엣지에 묶으면 넘기는 방향에 따라 그림자가 다르게
보입니다.

이 전부가 `Float` 위의 순수 함수입니다 — crease 이동, strip 기하, 조명, 펼침 pane 너비, 미러
패리티. 그래서 기기 없이 기하를 검증하고, 그리기 코드는 그 함수들이 낸 값만 소비합니다.

## 두 페이지 펼침

| 규칙 | 값 |
| --- | --- |
| 두 pane 이 되는 조건 | 짧은 변이 `600.dp` 이상이거나, 접힘이 책등일 때 |
| gutter | 책등이면 `max(16.dp, 힌지 두께)`, 그 외에는 `16.dp` |
| 왼쪽 pane 몫 | 접힘 위치에서 도출하고 `0.2 .. 0.8` 로 clamp |

접힘이 책등으로 인정되는 것은 수직이면서 창을 실제로 분리할 때뿐입니다 — 평평하거나 수평인 힌지를
보고하는 폴더블은 해당하지 않습니다. pane 개수와 gutter 둘 다 이 조건으로 게이트하므로 평평하게
펼쳐 둔 기기가 원치 않는 펼침을 받는 일이 없고, 몫 clamp 는 중심에서 벗어난 힌지가 한쪽 pane 을
0 으로 짓누르지 못하게 막습니다.

`rememberDisplayFold()` 가 창의 레이아웃 정보를 구독해 첫 접힘 feature 를 dp 로 보고하고, 그
아래는 전부 너비·높이·그 접힘의 순수 함수입니다.

## 아키텍처

의존은 한 방향으로만 흐릅니다: `app` → `feature` → `core:ui` / `core:designsystem` →
`core:domain` → `core:common`. `core:data` 는 `core:domain` 의 인터페이스에 묶여 있고 DI 그래프를
통해서만 닿습니다. `core:common` 은 플랫폼·프레임워크 의존이 아예 없습니다.

모든 기능은 `api` / `impl` 로 나뉘어 있어 어떤 기능도 다른 기능의 내부에 닿지 못합니다 — `home`,
`reader`, `search`, `bookmarks`, `document-info`, `settings` 모두 같은 모양입니다. 이 경계는 리뷰가
아니라 빌드가 강제합니다: 기능의 빌드 파일에는 의존 블록 자체가 없고, `teddreader.feature.impl`
컨벤션 플러그인이 자기 `api` 와 `core:common`, `core:domain`, `core:designsystem`, `core:ui` 만
정확히 연결합니다. `core:data` 를 보는 것은 `app:reader` 뿐입니다. 객체 그래프는 Koin annotations
가 만듭니다.

공유 코드는 `commonMain` 에 있고, 플랫폼이 실제로 다른 지점에서만 `androidMain` / `iosMain` 으로
내려갑니다.

| `expect` 선언 | Android | iOS |
| --- | --- | --- |
| `rememberDisplayFold()` | `WindowInfoTracker` 의 접힘 feature 를 dp 로 변환 | `null` — 폴더블 iOS 기기가 아직 없다 |
| `PlatformPdfPageSurface` | `android.graphics.pdf.PdfRenderer` | `UIKitView` 안의 PDFKit |
| `decodeLegacyKoreanText` | JVM charset 디코딩 | `kCFStringEncodingDOSKorean` |
| `ReaderPageMeasureDispatcher` | `Dispatchers.Default` | `Dispatchers.Main` — 텍스트 측정이 메인 스레드 전용 |
| `foundationPagerRenderProfile` | 컬 mesh 25 컬럼, 그림자 1 겹 | 12 컬럼, 4 겹 |
| `drawFoundationPagerCurlShadow` | `Paint.setShadowLayer` 네이티브 블러 한 번 | 반투명 path 를 겹쳐 부드러운 가장자리 |
| `rememberDocumentImporter` | SAF 와 Drive 인텐트 센더 | `UIDocumentPickerViewController` 와 Swift Drive 브리지 |

## 저장소 구조

| 경로 | 담긴 것 |
| --- | --- |
| `androidApp/` | Android 진입점과 매니페스트 |
| `iosApp/` | Xcode 프로젝트, SwiftUI 진입점, Google Drive 피커 브리지 |
| `baselineprofile/` | Android 베이스라인 프로파일을 생성하는 매크로벤치마크 |
| `app/reader/` | 앱 조립: DI 그래프, 내비게이션 호스트, 테마 배선, 임포터 |
| `core/common/` | 플랫폼·프레임워크 의존이 없는 모델과 순수 로직 |
| `core/domain/` | 저장소 인터페이스와 유스케이스 |
| `core/data/` | 저장소 구현, 형식 파서, 페이지네이션 엔진 |
| `core/room/`, `core/datastore/` | Room 데이터베이스와 DataStore 환경설정 |
| `core/designsystem/` | 테마, 색, 타이포그래피, 간격, 아이콘 |
| `core/ui/` | 두 개 이상의 기능이 함께 쓰는 컴포저블 |
| `feature/<name>/api/` | 그 기능이 바깥에 드러내는 공개 표면 |
| `feature/<name>/impl/` | 그 기능의 화면, 뷰모델, 컴포넌트 |
| `build-logic/` | 컨벤션 플러그인. 모듈 빌드 파일은 `id(...)` 한 줄 |

## 테스트

공통 로직은 `commonTest` 에서 `kotlin.test` 로 검증하며 JVM 과 iOS 시뮬레이터 양쪽에서 실행됩니다.
Android 전용 부분은 `androidHostTest` 에 있습니다. 테스트 케이스는 약 800 개이고, 숫자가 틀려도
화면을 보기 전까지는 드러나지 않는 부분에 무게가 실려 있습니다.

| 모듈 | 케이스 | 무엇을 덮나 |
| --- | --- | --- |
| `core/data` | 316 | 형식 파서, EPUB CSS·내비게이션, 페이지네이션과 섹션 분배 |
| `feature/reader/impl` | 228 | 페이지 목표 계산, 펼침 기하, 페이지 넘김 효과 계산, 뷰모델 상태 |
| `core/common` | 109 | 모델, 블록 구조, 읽던 위치, 검증 |
| `core/ui` | 51 | 여러 기능이 함께 쓰는 리더 컴포저블 로직 |
| `core/domain` | 24 | fake 저장소를 상대로 한 유스케이스 |
| `app/reader`, `feature/home/impl` | 41 | 내비게이션과 서재 목록 동작 |
| 그 외 | 27 | datastore, room 매퍼, 디자인 토큰, 검색, 설정, 문서 정보 |

페이지네이션·페이지 목표 계산·펼침 기하·페이지 넘김 효과 계산은 의도적으로 전부 순수 함수로 두어
기기 없이도 테스트할 수 있게 했습니다.

## 빌드

Gradle 실행에는 JDK 17 이상이 필요하고(모듈 자체는 Java 11 타깃), Android SDK 37 과 iOS 쪽을 위한
Xcode 가 필요합니다. SDK 경로는 `local.properties` 에 넣습니다 — 커밋되지 않습니다.

```bash
./gradlew :androidApp:assembleDebug                  # Android 디버그 APK
./gradlew :feature:reader:impl:testAndroidHostTest   # 한 모듈의 JVM 단위 테스트
./gradlew :core:data:iosSimulatorArm64Test           # 같은 테스트를 iOS 시뮬레이터 타깃에서
./gradlew :androidApp:generateBaselineProfile        # 기기에서 베이스라인 프로파일 재생성
```

iOS 는 `iosApp/iosApp.xcworkspace` 를 Xcode 로 열어 `iosApp` 스킴을 실행하거나:

```bash
cd iosApp
xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Google Drive 가져오기는 플랫폼마다 클라이언트 ID 가 필요합니다. iOS 쪽은 gitignore 된
`iosApp/Configuration/Config.xcconfig` 에 넣습니다 —
`iosApp/Configuration/Config.xcconfig.template` 을 그 경로로 복사한 뒤 값을 채우면 됩니다.

## 기술 스택

| 영역 | 쓰는 것 |
| --- | --- |
| 언어, UI | Kotlin 2.4.0, Compose Multiplatform 1.11.1, Material 3 |
| 내비게이션, DI | Navigation 3, Koin 4.2 및 annotations(KSP) |
| 저장 | Room 3 및 번들 SQLite, Okio 기반 DataStore |
| 비동기, 데이터 | kotlinx coroutines, serialization, datetime, immutable collections |
| 이미지, 로깅 | Coil 3, Kermit |
| 플랫폼 | 접힘 feature 용 androidx.window. Android minSdk 24, compileSdk 37, AGP 9.2 |
