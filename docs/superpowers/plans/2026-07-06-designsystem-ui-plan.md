# Design System UI Implementation Plan

**For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans implement plan task-by-task. Steps use checkbox (`- [ ]`) syntax tracking.

**Goal:** 비즈니스 로직을 제외하고 `core/designsystem`, `core/ui`에 테마/타이포/컬러 토큰, 공용 UI 컴포넌트, 프리뷰, 필요한 extension을 추가한다.

**Architecture:** `core/designsystem`은 토큰과 theme만 가진다. `core/ui`는 상태 없는 Compose 컴포넌트와 UI extension만 가진다. Android-only/resource 기반 Mycle extension은 가져오지 않는다.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3, Kotlin stdlib.

---

## Phase 1: Source Baseline

**Files:**
- Modify: `core/common/src/commonMain/kotlin/com/tedd/teddreader/core/common/model/ReaderModels.kt`
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/*.kt`
- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/*.kt`

- [x] 깨진 Kotlin 파일을 정상 문법으로 복구한다.
- [x] 비즈니스 규칙은 추가하지 않는다.

## Phase 2: Design Tokens

**Files:**
- Create: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderColors.kt`
- Create: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderTypography.kt`
- Create: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderSpacing.kt`
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/TeddReaderTheme.kt`
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/ReaderColors.kt`
- Modify: `core/designsystem/src/commonMain/kotlin/com/tedd/teddreader/core/designsystem/ReaderTypography.kt`

- [x] App color/typography/spacing token을 추가한다.
- [x] ReaderStyle → ReaderColors/TextStyle 변환을 유지한다.
- [x] companion object 대신 top-level val/function을 사용한다.

## Phase 3: Extensions

**Files:**
- Create: `core/common/src/commonMain/kotlin/com/tedd/teddreader/core/common/extension/Int.kt`
- Create: `core/common/src/commonMain/kotlin/com/tedd/teddreader/core/common/extension/String.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/extension/ConvertUtils.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/extension/Modifier.kt`

- [x] Reader에 필요한 순수 `Int`/`String` extension만 추가한다.
- [x] Compose density 변환 extension을 추가한다.
- [x] 중복 클릭 방지와 split motion guard modifier를 추가한다.

## Phase 4: Components and Previews

**Files:**
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddButton.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddIconButton.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddSurface.kt`
- Create: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/component/TeddEmptyState.kt`
- Modify: `core/ui/src/commonMain/kotlin/com/tedd/teddreader/core/ui/reader/*.kt`

- [x] 상태 없는 공용 컴포넌트를 추가한다.
- [x] Reader page/controls/progress 컴포넌트를 정리한다.
- [x] 각 컴포넌트에 최소 preview를 추가한다.

## Phase 5: Verification

- [x] Run `./gradlew :core:common:allTests :core:designsystem:allTests :core:ui:allTests`
- [x] Run `./gradlew :androidApp:assembleDebug :app:reader:linkDebugFrameworkIosSimulatorArm64`
