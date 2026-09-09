# Contributing

## Prerequisites

- **JDK 17** (CI uses Temurin 17; the build sets `sourceCompatibility`/
  `targetCompatibility` to 17)
- **Android SDK** with `compileSdk` 37; `minSdk` is 26
- Gradle comes from the wrapper — use `./gradlew`, never a system Gradle

No `.env` file. The only external configuration is the release keystore, and
only for signed builds — see [RUNBOOK.md](RUNBOOK.md).

## Commands

<!-- AUTO-GENERATED: derived from .github/workflows/ci.yml and app/build.gradle.kts -->

| Command | Purpose |
|---|---|
| `./gradlew assembleDebug` | Debug APK (~22.6 MB) |
| `./gradlew testDebugUnitTest` | The full unit suite — JUnit 4 + Robolectric |
| `./gradlew lintDebug` | Android Lint |
| `./gradlew detekt` | Static analysis, config in `config/detekt/detekt.yml` |
| `./gradlew assembleRelease` | R8 release APK (~2.4 MB); unsigned without a keystore |
| `./gradlew testDebugUnitTest --tests "*SomeTest*"` | One test class |

CI runs the first four in that order. The `release` job declares
`needs: build`, so a red gate blocks the signed APK.

<!-- END AUTO-GENERATED -->

| Tool | Purpose |
|---|---|
| `tools/weigh-in-test.sh [--install]` | One live weigh-in, fully instrumented — reads back off the phone rather than inferring |
| `tools/hw-probe/` | Throwaway BLE reconnaissance app; enumerates services, drives the UDS handshake, logs every byte |

## Testing

`./gradlew testDebugUnitTest` is the **only** automated lane. There is no
`app/src/androidTest`, no Compose lane, and no CI job that runs instrumented
tests. The `androidTest` dependencies and `testInstrumentationRunner` were
removed in Aug 2026 as dead weight implying coverage that never existed — do
not cite instrumented tests as coverage.

### Conventions this repo actually holds to

- **Fakes over mocks.** See `app/src/test/.../fake/`. No mocking framework is
  in the dependency set.
- **Mutation-check every new guard.** Break it, confirm the right test goes
  red, revert. A guard whose test passes with the guard deleted is worse than
  no test — that has happened here and was caught only by running the
  mutation.
- **Never `git checkout <file>` to undo a mutation** when that file holds
  uncommitted work; it reverts to HEAD and destroys the real edits. Use `cp`
  to a backup first.
- **Assert what the JVM lane can actually prove.** Robolectric records
  `stopSelf(int)` but does not model the platform's newer-start no-op, so
  "the service stopped" passes regardless of the id. Assert that your code
  passes the right id instead.
- **Code unreachable from Robolectric** — anything behind
  `applicationContext as BasculeApplication` — needs an injectable seam
  matching the established ones (`ScanBroadcastReceiver.enqueuerFactory`,
  `BridgeForegroundService.enqueuerFactory` / `activeAddressProvider` /
  `boundStopScheduler`). Do not invent a new pattern.
- **When a fix lands in genuinely unreachable code, document the gap** rather
  than writing a test that cannot fail.

### Known lane limits

Concurrent `./gradlew` runs in one worktree corrupt `app/build/test-results`
(`NoSuchFileException`, `EOFException`). That is contention, not a defect.
Work around it with `--init-script` setting `project.layout.buildDirectory`
plus `--project-cache-dir`.

## Code style

- Kotlin official style (`kotlin.code.style=official`).
- **detekt is the gate.** Note `TooManyFunctions.thresholdInClasses = 20`:
  `ConfigViewModel` sits at 19, so adding a function there needs a design
  decision, not a patch.
- Prefer `val`; never `!!`; never catch `CancellationException` without
  rethrowing.
- Comments explain *why*, especially why an obvious-looking alternative is
  wrong. Match the density of the file you are editing.

## Pull requests

This repo has twice regretted pushing straight to `main`. **Cut a branch
before committing, not after** — `/prp-pr` correctly refuses when the current
branch *is* `main`, and retrofitting means reverting and replaying.

Checklist:

- [ ] Branch cut from an up-to-date `main`
- [ ] `./gradlew testDebugUnitTest detekt` green locally
- [ ] Every new guard mutation-checked
- [ ] Commit message says *why*, and states what was verified vs assumed
- [ ] `git log origin/main..HEAD` matches what the PR claims
- [ ] Known limits stated in the PR body rather than discovered by a reviewer

Independent review is expected before merge — `/codex review` for a second
model's opinion, and never self-approve in the same pass.

## Where to read next

- `docs/CODEMAPS/` — token-lean architecture maps, start with `architecture.md`
- `docs/prp/05-retrospective.md` — what the fakes got wrong about the real
  device; read before trusting `00-design.md` or `01-plan.md`
- `HANDOFF.md` — session-by-session history and open threads
