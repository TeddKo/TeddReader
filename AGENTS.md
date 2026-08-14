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

### Compose hierarchy rules

- Use the layout that actually arranges the content as the component root. Do not add a `Box`, `Row`, `Column`, or `Surface` only to carry a `Modifier` that the real root can own.
- Keep a wrapper only when it provides required multi-child layout, alignment or overlay scope, clipping, insets, semantics, or interaction behavior that cannot be preserved on the child modifier.
- Prefer modifier drawing such as `background`, `border`, and `drawBehind` over an extra visual-only composable node.
- Use Material clickable, selectable, and toggleable component overloads before building an outer interaction wrapper. Ripple and pressed indications must be clipped to the visible component shape; minimum touch-target handling must not enlarge the visible indication.
- Reject pass-through one-child wrappers during review unless the wrapper is an intentional design-system boundary with behavior beyond forwarding parameters.

## Testing Guidelines

Tests use `kotlin.test`. Put cross-platform tests in `shared/src/commonTest/kotlin/`, Android host tests in `shared/src/androidHostTest/kotlin/`, and iOS tests in `shared/src/iosTest/kotlin/`. Add the smallest test that proves new logic works; UI-only changes should at least build successfully.

## Git Workflow, Worktrees, and Commits

Use the Git Flow branch model. In this repository, “Git Flow” means the branch topology and naming rules below; the optional `git-flow` shell extension is not required.

### Protected integration branches

- `master` is the final, release-ready branch. Never implement work or commit directly on it.
- `develop` is the integration branch. Create it from `master` once if it does not exist.
- All task branches start from the latest `develop` and merge back into `develop` through a pull request on `origin`.
- `develop` and `master` only ever advance through a merged pull request. Never merge a task branch into `develop` locally, and never push either integration branch directly.
- Merge `develop` into `master` only for an explicitly requested final release, and only through a pull request.
- Even `hotfix/*` branches flow through `develop`; this project does not merge hotfixes directly into `master`.

### Mandatory task lifecycle

Every new coding, documentation, configuration, or maintenance task must use its own branch and worktree.

1. Confirm the main checkout and `develop` are clean and up to date:
   ```bash
   git fetch origin
   git switch develop
   git pull --ff-only
   ```
2. Choose one task type and a short lowercase kebab-case slug.
3. Create the task branch and sibling worktree from `develop`:
   ```bash
   git worktree add -b <type>/<slug> ../TeddReader-<type>-<slug> develop
   ```
4. Perform all task edits and verification only inside that worktree.
5. Review the diff, run the smallest sufficient tests, and create the task commit.
6. Confirm the task worktree is clean after the commit. Never discard uncommitted work to satisfy cleanup.
7. Push the task branch to `origin` from inside the worktree:
   ```bash
   git push -u origin <type>/<slug>
   ```
8. Remove the completed task worktree:
   ```bash
   git worktree remove ../TeddReader-<type>-<slug>
   ```
9. Open a pull request into `develop`. The PR title uses the same format as the merge summary, `<commit-type>: <변경 대상과 핵심 동작을 담은 구체적 한글 명사형 병합 요약>`, and the PR body carries the same detail as the task commit body:
   ```bash
   gh pr create --base develop --head <type>/<slug> \
     --title "<commit-type>: <한글 명사형 병합 요약>" --body "<한글 명사형 본문>"
   ```
   When the `gh` CLI is unavailable, report the compare link instead and let the user open the pull request:
   `https://github.com/TeddKo/TeddReader/compare/develop...<type>/<slug>?expand=1`
10. Merge the pull request only after the user approves it. Always use a merge commit; never squash and never rebase-merge. Then delete the branch and sync the local integration checkout:
    ```bash
    gh pr merge <number> --merge --delete-branch
    git switch develop
    git pull --ff-only
    git branch -d <type>/<slug>
    ```

Stop before worktree removal, push, pull request creation, or merge when tests fail, conflicts remain, or the task worktree is dirty. Report the blocker instead of using `--force`, resetting, or deleting user changes.

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

### Detailed Korean noun-form commit messages

- Format every commit subject as `<type>: <한글 요약>`.
- End the Korean summary with a noun-form expression such as `추가`, `수정`, `개편`, `정리`, `제거`, `병합`, or `구성`.
- Do not end with an imperative or finite verb such as `추가한다`, `수정함`, or `개편했다`.
- Subject must contain all three elements in one phrase: concrete target/scope, principal behavior/structural change, and noun-form result.
- A noun ending alone is not specific; use `수정/개선/개편/정리/병합` only after explicit target and concrete action.
- For non-trivial changes, body is required; include concise `-` noun-form bullets for applicable items (주요 구현 내용, 경계/호환 처리, 마이그레이션 처리, 테스트/검증). Trivial single-line changes may omit body only when fully explained by subject.
- Merge commit message must preserve task detail and keep `<commit-type>: <변경 대상과 핵심 동작을 담은 구체적 한글 명사형 병합 요약>`. The pull request title supplies that subject, so it follows the same rule.
- Keep commit bullets noun-form too.
- Before every non-trivial commit, inspect `git diff --cached --stat`, `git diff --cached`, and `git diff --cached --check`; keep only files matching the commit scope and make subject/body describe every material staged change.

Bad:

```text
chore: 작업 반영
fix: develop 병합
fix: 페이지 이동 효과 구분 수정
fix: 펼침 화면 페이지 경계 오류 수정
refactor: UI 컴포넌트 역할별 파일 정리
```

Good:

```text
fix: Scroll 연속 이동과 Slide 페이지 스냅 분리 및 Sheet Flip 통합

- Slide의 HorizontalPager·VerticalPager 기반 손가락 추적 전환
- 저장된 SHEET_FLIP 값을 SLIDE로 변환하는 호환 처리
- 페이지 이동 분기 및 설정 직렬화 회귀 테스트 추가

fix: 펼침 화면 마지막 spread 진행률 보정 및 페이지 경계 드래그 차단

- 2-pane 마지막 spread의 slider 위치와 페이지 표기 보정
- Scroll 빈 인접 슬롯 제거 및 Pager 경계 방향 포인터 소비
- 펼침 화면 진행률·드래그 경계 회귀 테스트 추가

refactor: 카드·행·입력 UI 컴포넌트 역할별 파일 재배치

- TeddWrappers 제거 및 역할별 컴포넌트 이동
- 공개 Composable 함수 시그니처와 동작 유지
- core UI Android host 테스트 통과
```

Merge good example:

```text
fix: 펼침 화면 마지막 spread 진행률 보정 및 페이지 경계 드래그 차단 병합
```

The rules above apply to tasks started after this section is added. Do not automatically reset, move, or discard pre-existing dirty changes; migrate them only as a separately reviewed task.

Pull requests should include a brief description, test results or build commands run, linked issues when applicable, and screenshots for visible UI changes.
