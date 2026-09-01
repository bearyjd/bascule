# Scale profile management — design

Written 2026-08-31, from live hardware testing on the Pixel 9 Pro Fold against
a BF720 (`E7:DB:51:F1:36:91`). Three items, all on the Scale tab: why "Profile
1" doesn't appear, a way to delete a local profile, and a manual "weigh now"
trigger for fast capture. Nothing here touches the `0x2A9F` decode path or the
delivery pipeline.

## 1. Why "Profile 1" doesn't show

**Not a bug — confirmed by reading the store and the registration UI.**

`ScaleProfileStore` (`data/ScaleProfileStore.kt`) is a local registry Bascule
writes to itself, in exactly two places (`ConfigViewModel.startScaleRegistration`
and `.linkExistingScale`, both surfaced via `RegisteredScaleSection.kt`). It
has no code path that reads the BF720's own list of user slots — the SIG
Weight Scale profile doesn't expose one over BLE for a peer to enumerate. So
the Profiles card can only ever show slots Bascule was explicitly told about.
Right now that's one: "Profile 2" (`E7:DB:51:F1:36:91` · slot 2), registered
earlier this session via "Use existing." Slot 1, if it exists on the scale
itself (set up before Bascule ever touched it, or by someone else), is
invisible to the app — the on-screen line under the profile list already says
this: *"Bascule may not know about every user slot stored on the scale
itself."*

Two ways to make slot 1 known to Bascule, and one thing neither can do:

- **"Use existing"** (`RegisteredScaleSection`'s `OutlinedButton`) — if you
  already know slot 1's consent code, enter address + `1` + that code. This
  writes a `ScaleProfile` without touching the scale.
- **"Register scale"** — starts a fresh registration. The BF720 assigns
  whichever of its 8 slots it chooses; the app cannot request "slot 1"
  specifically, and if 1 is already occupied this consumes a *different* slot,
  leaving 1 still unregistered.
- **What's not possible:** recovering slot 1's consent code from the scale
  itself. The SIG protocol has no read-back for it. If it's genuinely unknown,
  the only way in is a fresh registration into a free slot.

**No fix needed here** — the behavior matches the architecture and the
existing copy already discloses it. Confirm with the user this was a
UI-observation question, not a report that a live capture from slot 1 silently
failed (see the caveat in §3's diagnosis).

## 2. Delete a local profile

**Problem.** `ScaleProfileStore.deleteProfile(profileId)` exists and works —
it's just never called. `ScaleScreen`'s "Scale status" card says outright:
*"Profiles can't be removed yet — you can re-register a scale to replace
one."* That's a real gap once more than one profile accumulates (e.g. a
mis-typed "Use existing" entry, or a household member's scale that's no longer
in use).

**Design.**

- `ScaleViewModel`: add
  ```kotlin
  fun delete(profile: ScaleProfile) = viewModelScope.launch {
      withContext(ioDispatcher) { profiles.deleteProfile(profile.id) }
  }
  ```
  Same shape as `rename` — synchronous encrypted-prefs `commit()`, off the
  caller's thread.
- `ProfilesCard`: add a destructive action per row. Given `Rename` is already
  a `TextButton` per row, add `Remove` beside it — but gate it behind a
  confirmation `AlertDialog` (matching the pattern `RegisteredScaleSection`
  already uses for re-registration), since this is unrecoverable from the
  app's side. Copy should be explicit that this is local-only: *"Remove this
  profile from Bascule? The BF720 keeps its own copy of slot N until it's
  overwritten or reset."* — reusing the same fact `RegisteredScaleSection`
  already states for re-registration, so the two dialogs don't contradict each
  other.
- Deleting the **active** profile: `ScaleProfileStore` has no defined behavior
  for zero active profiles beyond what `activeProfile` already models (`null`
  is valid — `mutableActive.value = next.firstOrNull { it.active }`). Downstream,
  `ScaleScanner.arm()` already treats `activeProfile.value == null` as "can't
  arm" and returns `false`, and `ScaleViewModel.setAutomaticCapture` already
  produces a diagnostic for that case ("Link or register a profile before
  enabling automatic capture."). So deleting the only/active profile needs no
  new guard — the existing ones already cover the resulting state. Worth a
  test that asserts this explicitly rather than trusting it by inspection.
- Update the now-stale "Profiles can't be removed yet" line once this ships.

## 3. "Weigh now" — bounded fast-scan trigger

**Diagnosis first, because the design leans on it.** There are already *two*
independent scan paths, not one:

- `ScaleScanner.arm()` — `SCAN_MODE_LOW_POWER`, `PendingIntent`-based, survives
  process death, runs whenever "Automatic background capture" is on. Low duty
  cycle by design (battery).
- `BridgeForegroundService` — `SCAN_MODE_BALANCED`, callback-based, runs the
  entire time "Always-on foreground fallback" is on. Confirmed alive and
  foregrounded during tonight's test (`dumpsys activity services` showed
  `isForeground=true` on `BridgeForegroundService`, notification `id=721`
  present). Both toggles were on for the live test just run, and neither path
  produced a `ScanBroadcastReceiver`, `BridgeForegroundService`, or
  `ScaleSessionWorker` log line in a 150s window.

So today's non-capture isn't "the background scan is too slow" (that story
fully explains the LOW_POWER path alone, but not BALANCED-mode's silence too).
Before building a third scan mode on top, it's worth confirming on the next
hardware pass whether the BF720 was actually advertising during that window —
some scales only broadcast for a few seconds right at step-on, and the window
needs to overlap that, not just be "long."  Recommend re-testing with a
`logcat` grep for `BluetoothLeScanner`/`bt_btm` system tags (not just the
app's own) to see whether the radio saw *any* advertisement from the address
at all, before concluding the app-side pipeline is at fault.

**Design, assuming the diagnosis holds and a faster deliberate trigger is
still wanted.** Don't add a third independent scan subsystem — turn
`BridgeForegroundService`'s existing active scan into something a one-shot
button can also request, bounded, on top of `SCAN_MODE_LOW_LATENCY` rather
than `BALANCED` (justified here specifically because it's bounded — the
comment on `BALANCED` explains it's chosen *because* that scan runs
unbounded).

- `ScaleScreen`: a prominent button — "Weigh now" — visible regardless of
  either toggle's state, since its entire purpose is bypassing them for one
  reading.
- `ScaleViewModel.weighNow()`: starts the bounded scan, exposes a
  `weighNowActive: Boolean` (or a countdown) in `ScaleUiState` so the button
  can show "Waiting… (Cancel)" instead of just firing and forgetting.
- The bounded scan needs to **not fight** the persistent toggles:
  - If "Always-on foreground fallback" is already on, "Weigh now" has nothing
    to add — the fast path is already running. The button should say so
    rather than start a second scan against the same radio filter.
  - If it's off, "Weigh now" starts `BridgeForegroundService` (or an
    equivalent bounded variant) for a fixed window and stops it again after —
    on a duration timer, not on first result, since `ScaleSessionWorker`'s own
    session (up to `SessionBudget.HARD_SESSION_CEILING` = 90s) needs to run to
    completion after the advertisement is seen. **120s** total window covers
    discovery time plus that ceiling with margin.
  - Cancelling early (button again, or leaving the screen) must stop the
    service — an abandoned foreground scan with its ongoing notification is
    exactly the friction this whole modernization pass has been removing.
- This does **not** touch `ScaleScanner`/`arm()`/`disarm()` at all — the
  LOW_POWER background path is orthogonal and keeps running whatever it was
  doing. "Weigh now" only ever starts/stops the foreground one.

## Sequencing

§2 (delete) is small, self-contained, and has no open question — implement
first. §3 (weigh now) depends on the re-test in its diagnosis paragraph
confirming there's a real gap left to close once both existing scan paths are
accounted for.
