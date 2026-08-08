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

## Git Workflow, Worktrees, and Commits

Use the Git Flow branch model. In this repository, “Git Flow” means the branch topology and naming rules below; the optional `git-flow` shell extension is not required.

### Protected integration branches

- `master` is the final, release-ready branch. Never implement work or commit directly on it.
- `develop` is the integration branch. Create it from `master` once if it does not exist.
- All task branches start from the latest `develop` and merge back into `develop`.
- Merge `develop` into `master` only for an explicitly requested final release.
- Even `hotfix/*` branches flow through `develop`; this project does not merge hotfixes directly into `master`.

### Mandatory task lifecycle

Every new coding, documentation, configuration, or maintenance task must use its own branch and worktree.

1. Confirm the main checkout and `develop` are clean and up to date.
2. Choose one task type and a short lowercase kebab-case slug.
3. Create the task branch and sibling worktree from `develop`:
   ```bash
   git worktree add -b <type>/<slug> ../TeddReader-<type>-<slug> develop
   ```
4. Perform all task edits and verification only inside that worktree.
5. Review the diff, run the smallest sufficient tests, and create the task commit.
6. Confirm the task worktree is clean after the commit. Never discard uncommitted work to satisfy cleanup.
7. Remove the completed task worktree:
   ```bash
   git worktree remove ../TeddReader-<type>-<slug>
   ```
8. In a clean integration checkout, merge the branch into `develop` with `--no-ff`, then delete the merged task branch:
   ```bash
   git switch develop
   git merge --no-ff <type>/<slug> -m "<commit-type>: <한글 명사형 병합 요약>"
   git branch -d <type>/<slug>
   ```

Stop before worktree removal or merge when tests fail, conflicts remain, or the task worktree is dirty. Report the blocker instead of using `--force`, resetting, or deleting user changes.

### Branch and commit types

Use matching branch and commit intent:

| Work | Branch | Commit prefix |
| --- | --- | --- |
| Feature | `feature/<slug>` | `feat:` |
| Bug fix | `fix/<slug>` | `fix:` |
| Urgent production fix | `hotfix/<slug>` | `hotfix:` |
| Maintenance | `chore/<slug>` | `chore:` |
| Documentation only | `docs/<slug>` | `docs:` |
| Tests only | `test/<slug>` | `test:` |
| Behavior-preserving refactor | `refactor/<slug>` | `refactor:` |

Do not mix unrelated work types in one branch. Split them into separate task branches when they require independent review or rollback.

### Korean noun-form commit messages

- Format every commit subject as `<type>: <한글 요약>`.
- End the Korean summary with a noun-form expression such as `추가`, `수정`, `개편`, `정리`, `제거`, `병합`, or `구성`.
- Do not end with an imperative or finite verb such as `추가한다`, `수정함`, or `개편했다`.
- Keep commit bodies optional and concise; when present, write their bullet items as Korean noun phrases too.

Examples:

```text
feat: 독서 화면 에디토리얼 UI 개편
fix: 검색 입력 활성화 오류 수정
hotfix: 앱 시작 크래시 긴급 수정
chore: Git 작업 규칙 정립
```

The rules above apply to tasks started after this section is added. Do not automatically reset, move, or discard pre-existing dirty changes; migrate them only as a separately reviewed task.

Pull requests should include a brief description, test results or build commands run, linked issues when applicable, and screenshots for visible UI changes.
