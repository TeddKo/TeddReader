# Repository Guidelines

## Project Structure & Module Organization

TeddReader is a Kotlin Multiplatform project with two Gradle modules:

- `androidApp/`: Android application entry point and Android-only app configuration.
- `shared/`: shared Compose Multiplatform UI and business logic.
- `shared/src/commonMain/kotlin/`: code shared by Android and iOS.
- `shared/src/androidMain/kotlin/` and `shared/src/iosMain/kotlin/`: platform-specific Kotlin implementations.
- `shared/src/commonMain/composeResources/`: shared Compose resources such as drawables.
- `shared/src/*Test/kotlin/`: common, Android host, and iOS tests.
- `iosApp/iosApp/`: SwiftUI iOS application wrapper and Xcode entry point.

Version aliases and plugin coordinates live in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper.

- `./gradlew :androidApp:assembleDebug`: builds the Android debug APK.
- `./gradlew :shared:testAndroidHostTest`: runs Android host tests for shared code.
- `./gradlew :shared:iosSimulatorArm64Test`: runs shared iOS simulator tests.
- `./gradlew build`: runs the full Gradle build across configured modules.

For iOS app runs, open `iosApp/` in Xcode and use the IDE run action.

## Coding Style & Naming Conventions

Use Kotlin style conventions already present in the repo: 4-space indentation, trailing commas in multiline calls where helpful, and package names under `com.tedd.teddreader`. Keep shared code in `commonMain`; use `androidMain` or `iosMain` only when platform APIs are required.

Name Compose entry points and screens in PascalCase, such as `App`. Name tests as `*Test` classes with focused `@Test` methods.

## Testing Guidelines

Tests use `kotlin.test`. Put cross-platform tests in `shared/src/commonTest/kotlin/`, Android host tests in `shared/src/androidHostTest/kotlin/`, and iOS tests in `shared/src/iosTest/kotlin/`. Add the smallest test that proves new logic works; UI-only changes should at least build successfully.

## Commit & Pull Request Guidelines

Recent history uses concise Conventional Commit-style messages, for example `chore: setup project structure and build logic`. Prefer `feat:`, `fix:`, `test:`, `docs:`, or `chore:` with a short imperative summary.

Pull requests should include a brief description, test results or build commands run, linked issues when applicable, and screenshots for visible UI changes.
