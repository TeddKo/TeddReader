# TeddReader

*[English](README.md)*

기기에 이미 가지고 있는 문서를 읽는 리더입니다. TXT, EPUB, PDF, CBZ, 또는 이미지가 담긴 폴더를
열면 어디까지 읽었는지 기억합니다. Android 와 iOS 가 같은 Kotlin 코드와 같은 Compose UI 를
공유하며, 어디에도 업로드하지 않고 계정을 만들 필요도 없습니다.

## 무엇을 하나

**서재.** 기기 저장소에서 파일을 가져오거나 Google Drive 에서 고릅니다. 최근순·제목순·형식순으로
정렬하고, 형식으로 거르고, 직접 이름 붙인 폴더로 묶습니다. 홈 화면은 마지막에 읽던 문서를 가장
앞에 보여줍니다.

**읽기.** 페이지는 가로나 세로로 넘기거나 이어서 스크롤합니다. 태블릿이나 펼친 폴더블에서는 두
페이지를 나란히 펼치고 접히는 부분에 여백을 두어, 글자가 접힘선에 빠지지 않습니다.

**페이지 넘김.** 아홉 가지 — 없음, 슬라이드, 페이드, 스크롤, 플루이드 페이지, 컬 페이지, 원형
전환, 무비 캐러셀, 페이지 플립. 컬과 페이지 플립은 손가락을 따라 움직이다가 놓는 지점에서
자리를 잡고, 펼침 화면에서는 전체를 밀어내지 않고 한 장만 책등에서 접습니다.

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

## 저장소 구조

| 경로 | 담긴 것 |
| --- | --- |
| `androidApp/` | Android 진입점과 매니페스트 |
| `iosApp/` | Xcode 프로젝트, SwiftUI 진입점, Google Drive 피커 브리지 |
| `app/reader/` | 앱 조립: DI 그래프, 내비게이션 호스트, 테마 배선 |
| `core/common/` | 플랫폼·프레임워크 의존이 없는 모델과 순수 로직 |
| `core/domain/` | 저장소 인터페이스와 유스케이스 |
| `core/data/` | 저장소 구현, 임포터, 페이지네이션 엔진 |
| `core/room/`, `core/datastore/` | Room 데이터베이스와 DataStore 환경설정 |
| `core/designsystem/` | 테마, 색, 타이포그래피, 간격, 아이콘 |
| `core/ui/` | 두 개 이상의 기능이 함께 쓰는 컴포저블 |
| `feature/<name>/api/` | 그 기능이 바깥에 드러내는 공개 표면 |
| `feature/<name>/impl/` | 그 기능의 화면, 뷰모델, 컴포넌트 |
| `build-logic/` | 컨벤션 플러그인. 모듈 빌드 파일은 `id(...)` 한 줄 |

기능은 `api` / `impl` 로 나뉘어 있어 어떤 기능도 다른 기능의 내부에 의존하지 않습니다. `home`,
`reader`, `search`, `bookmarks`, `document-info`, `settings` 모두 같은 모양입니다.

## 빌드

Gradle 실행에는 JDK 17 이상이 필요하고(모듈 자체는 Java 11 타깃), Android SDK 37 과 iOS 쪽을 위한
Xcode 가 필요합니다. SDK 경로는 `local.properties` 에 넣습니다 — 커밋되지 않습니다.

```bash
./gradlew :androidApp:assembleDebug                  # Android 디버그 APK
./gradlew :feature:reader:impl:testAndroidHostTest   # 한 모듈의 JVM 단위 테스트
./gradlew :core:data:iosSimulatorArm64Test           # 같은 테스트를 iOS 시뮬레이터 타깃에서
```

iOS 는 `iosApp/iosApp.xcworkspace` 를 Xcode 로 열어 `iosApp` 스킴을 실행하거나:

```bash
cd iosApp
xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Google Drive 가져오기는 플랫폼마다 클라이언트 ID 가 필요합니다. iOS 쪽은
`iosApp/Configuration/Config.xcconfig` 에 넣습니다.

## 테스트

공통 로직은 `commonTest` 에서 `kotlin.test` 로 검증하며 JVM 과 iOS 시뮬레이터 양쪽에서 실행됩니다.
Android 전용 부분은 `androidHostTest` 에 있습니다. 검증의 대부분은 리더가 지고 있는데,
페이지네이션·페이지 목표 계산·펼침 화면 기하·페이지 넘김 효과 계산을 의도적으로 전부 순수 함수로
두어 기기 없이도 테스트할 수 있게 했습니다.

## 기술 스택

Kotlin 2.4 · Compose Multiplatform 1.11 · Material 3 · Koin(annotations) · Room 3 및 번들 SQLite ·
Okio 기반 DataStore · Coil 3 · Navigation 3 · kotlinx coroutines, serialization, datetime,
immutable collections. Android minSdk 24, compileSdk 37.
