# Bascule UI modernization — design

Written 2026-08-29. Scope agreed as **visual + flow, no onboarding**: a real
app icon, a restructured navigation shell, and the removal of the specific
friction points found by running the app on hardware. Onboarding, the two
unrendered failure flows, and anything BLE-protocol are explicitly out of
scope.

Every problem below was observed on a real device (Pixel 9 Pro Fold, Android
17 / SDK 37) on 2026-08-29 and then confirmed in source. None of them are
speculative.

## 1. App icon

**Problem.** There is no icon. `app/src/main/res` contains only `values/` and
`xml/` — no `mipmap*` directory, no `ic_launcher*` of any kind — and the
manifest declares neither `android:icon` nor `android:roundIcon`. logcat
confirms the consequence: `LauncherActivityCachingLogic: loadIcon: Default app
icon returned from PackageManager`.

**Design.** An adaptive icon built from **vector drawables**, not PNGs. minSdk
is 26, so `<adaptive-icon>` is supported on every device this app runs on and
no legacy per-density raster fallbacks are required.

| file | contents |
|---|---|
| `res/drawable/ic_launcher_background.xml` | Violet gradient field, `#4C34B4` → `#241A57` top-to-bottom. |
| `res/drawable/ic_launcher_foreground.xml` | The stacked-slab mark, art confined to the central 66.7% safe zone. |
| `res/drawable/ic_launcher_monochrome.xml` | Single-colour silhouette of the same mark. |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | `<adaptive-icon>` with `background`, `foreground`, **and `monochrome`**. |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | Same, for round-icon launchers. |

The `<monochrome>` layer is not optional polish: without it, Android 13+
themed icons fall back to an auto-generated crop of the foreground, which
looks broken next to correctly-themed icons.

Slab colours, top to bottom: `#22D3EE` (cyan cap, with `#17A8BF` / `#128FA3`
edges), `#7C5CE0` (with `#553CBE` / `#4530A0` edges), and a `#A586F2` base at
30% opacity. These are the repo palette's `violet` ramp from
`docs/assets/branding.json`, so the icon and the banner are the same mark in
the same colours.

The mark is the same stacked-slab motif used by the repo's README banner and
social-preview card, so the app and the repository share one identity.
`docs/assets/branding.json` remains the source of truth for the banner
artwork; the icon vectors are drawn to match it, not generated from it.

**Manifest.** Add `android:icon="@mipmap/ic_launcher"` and
`android:roundIcon="@mipmap/ic_launcher_round"` to `<application>`.

## 2. Navigation shell

**Problem.** `BasculeDestination` declares four flat peers, and
`BasculeApp.kt` *additionally* renders a FloatingActionButton that navigates
to `ManualEntry.route`. So the rarest action in the app has two entry points,
one of which occupies 25% of primary navigation — while the app's own copy
describes it as "Only for when the scale doesn't have you". This is the
behavioural half of the long-standing **P25** finding (`ManualEntry`'s duelling
nav-bar/FAB back-stack contracts), which has been open since before this
session.

**Design.** Keep `ManualEntry` as a real route; remove it from the bar.

Add a property to the enum rather than splitting the type — the routes and the
bar genuinely are the same set minus one entry, and two parallel lists would
drift:

```kotlin
enum class BasculeDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean = true,
) {
    History(...),
    ManualEntry(..., inBottomBar = false),
    Scale(...),
    Config(...),
}
```

`BasculeApp` renders `BasculeDestination.entries.filter { it.inBottomBar }`.
The FAB becomes the single entry point, which resolves P25's duplicate
back-stack contract as a side effect rather than needing its own fix.

The existing KDoc on `BasculeDestination` claims a flat bar is "the
lowest-friction shell" because "every screen is one tap away from every other".
That reasoning survives for the three remaining destinations and must be
updated, not deleted, to say why manual entry is excluded.

## 3. Automatic capture is enabled by registration

**Problem.** `ConfigStore.kt:100` defaults `automaticCaptureEnabled` to
`false`. The app's entire purpose is hands-off capture, so a user can register
their scale, step on it, and receive nothing — with no error shown, because
"registered but not capturing" is a valid configured state. On the test device
this was the live state: automatic capture off, "Last successful capture:
Never".

**Design.** On a successful scale registration, set
`automaticCaptureEnabled = true`. The Scale screen's toggle stays exactly as
it is, so the setting remains reversible.

**There are two registration-success paths, and both must be covered.** They
are easy to miss because only one of them involves the scale:

1. `ScaleRegistrationResult.Success` — the real BLE `0x2A9F` handshake.
2. `linkExistingScale(address, scaleIndex, consentCode)` — the user types
   credentials for a scale already registered against another install. **No
   BLE, no scale required, no slot burned.**

Both already end in the same four calls — `activateLinkedProfile(...)`,
`rearmScanner?.invoke()`, `_consentVersion.value++`, then
`ScaleRegistrationUiState.Success(...)` — and path 1's own comment
acknowledges the duplication ("Same two calls `linkExistingScale` makes, for
the same reason").

Extract that shared tail into one private function and add the capture-enable
there, rather than editing two call sites. Adding it to only one is the
obvious failure mode of this change: registering via the scale would enable
capture while linking by hand would not, and nothing would report the
difference.

The consent argument is that registration *is* the consent: nobody completes a
`0x2A9F` handshake with a bathroom scale without intending the app to read it.
This is deliberately **not** a change to the stored default — a bare `?: true`
would arm a background-scanning feature for someone who never asked.

`ScaleScanner.arm()` is already gated on both the flag and an active profile,
so ordering is not delicate here: the flag is set on the same path that
activates the profile.

## 4. Pinned status colours

**Problem.** `BasculeTheme` sets `useDynamicColor = true` by default and the
minimum for dynamic colour is SDK 31, so on any modern device the entire
palette comes from the user's wallpaper. The carefully-built
`TealPrimaryLight` (`#00696B`) / clay / blue scheme in `Color.kt` is the
pre-Android-12 fallback and effectively never renders. A consequence that
matters: the History status chips (`sent`, `blocked auth`, `pending`,
`held`) derive from `MaterialTheme.colorScheme`, so how distinguishable a
*failed* delivery is from a *successful* one depends on the wallpaper.

**Design.** Keep dynamic colour as the default — it is the modern Android
idiom and costs the user nothing. Add explicit semantic tokens to `Color.kt`
for delivery status only, with light and dark variants, and have
`HistoryScreen` use those instead of scheme roles:

- `StatusSent` — settled/neutral, deliberately quiet
- `StatusPending` — in-flight
- `StatusBlocked` — needs the user to act (`BLOCKED_AUTH`, `FAILED_PERMANENT`)
- `StatusHeld` — awaiting confirmation (`HELD_CONFIRM`)

Each must meet WCAG AA contrast against both light and dark surfaces, verified
by a unit test computing contrast ratios rather than by eye. The re-tuned
non-dynamic fallback palette should also be brought into line with the icon's
violet, since it is what renders when dynamic colour is unavailable.

## 5. Copy and per-screen friction

### 5.1 Remove the contract-version dropdown

`ContractVersion` has exactly two entries and `selectableContractVersions`
filters out `V2_BODY_COMP`, so the "VitalForge contract version" dropdown on
Settings offers **exactly one option**. It is an unchangeable control
exposing an internal wire-format concept.

Remove the control. The stored value is untouched and still defaults
correctly; the dropdown returns when V2 lands.

**This changes an existing handoff commitment.** `HANDOFF.md` records that two
independent gates keep V2 unreachable — `ConfigScreen`'s
`selectableContractVersions` and `ConfigViewModel`'s matching import gate — and
that both must be deleted together when the contract doc arrives. Removing the
dropdown removes the first gate's *UI*, so the handoff note must be rewritten
to describe what actually remains, or the next session will look for a control
that no longer exists.

### 5.2 Plain-language Scale screen

The Scale screen currently shows, verbatim: "Scale inventory may be incomplete
until List All Users capability probing is supported" and "Profile deletion is
disabled until consent verification and typed confirmation can be completed
safely". Both describe unimplemented internals in implementation vocabulary.

Rewrite in user terms or remove. The honest content — that the app may not know
about every user slot on the scale, and that deleting a profile is not yet
possible — can be said without naming the capability probe.

### 5.3 History shows capture state

History is where the user lands and currently says nothing about whether the
app is actually watching for the scale. Add a single status line: watching /
idle / off, with the off state offering a one-tap enable.

This is *not* the same as the two unrendered failure flows
(`startupFailure`, `alwaysOnBridgingStartFailed`), which stay out of scope.

## 6. Display unit follows the user's preference

**Problem.** `formatWeight(reading)` picks its unit from the *row's* stored
`displayUnit`, and `HistoryScreen.kt:152` renders that same stored string as
the label. `HistoryViewModel` never reads `ConfigStore.displayUnit` at all. So
with Pounds selected, the Add-weight field correctly reads "Weight (lbs)" while
History still renders "70.5 kg".

There is no data-integrity argument for this. Storage is *always* kilograms
(`WeightUnit`'s KDoc, `00-design.md §2.7`); `displayUnit` records only which
unit happened to be on screen at capture time. The stored measurement is
unit-agnostic, so converting for display loses nothing.

**Design.** `formatWeight(reading, unit)` takes the user's current
`WeightUnit`; `HistoryViewModel` exposes it from `ConfigStore`; the row label
uses that unit's `wire`. The stored `displayUnit` column is left alone — it is
still written, and the settings backup still round-trips it.

**This also closes a documented defect.** `HistoryFormattingTest` currently
pins, as known-wrong behaviour, that an unrecognised `displayUnit` falls back
to kilograms and renders "a **wrong number** — 90.8 where the user's pounds row
should read 200.2 — with no marker reaching the user". Once display no longer
parses the stored string, that failure mode is structurally impossible.

That pinning test must be **replaced, not deleted**: the new test asserts that a
row with a corrupt `displayUnit` now renders correctly in the user's chosen
unit, because display no longer depends on the stored value. Deleting it
outright would remove the only record that the bug existed.

## 7. Testing and verification

Unit-testable, and required:

- `BasculeDestination.entries.filter { it.inBottomBar }` yields exactly
  History/Scale/Config, and `ManualEntry` remains a resolvable route.
- Successful registration sets `automaticCaptureEnabled`; a *failed*
  registration does not.
- Status colour mapping is total over `ReadingStatus`, and each token meets
  WCAG AA against both surfaces.
- `formatWeight` honours the passed unit, including for a corrupt stored one.

Then, on the device: install and screenshot all three tabs plus manual entry,
in both light and dark, and confirm the launcher shows the new icon (both
standard and themed).

**The gap, stated precisely** — narrower than it first appears. Only path 1 of
§3 needs the BF720; `linkExistingScale` needs nothing but typed credentials,
and the BF720's are known (`E7:DB:51:F1:36:91`, slot 2, consent 1234, recorded
in `HANDOFF.md`). So:

- **Hardware-verifiable now:** capture-enable via `linkExistingScale`, the
  icon, the nav shell, status colours, all copy, and the display-unit change.
  Re-linking writes only local encrypted prefs — no BLE, so it burns no slot
  and is safe to run against the live install.
- **Not verifiable until the scale is on hand:** capture-enable via the real
  `0x2A9F` handshake (path 1), and end-to-end capture actually firing.

Do not report path 1 as hardware-verified on the strength of path 2 passing.
They share the extracted helper, which is exactly why testing one says
something about the other — but it is an argument, not an observation.

## Out of scope

First-run onboarding; surfacing `startupFailure` and
`alwaysOnBridgingStartFailed`; the `EncryptedSharedPreferences` deprecation;
and every open architectural finding (P8, P16, P17).
