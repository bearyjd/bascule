<!-- Generated: 2026-09-08 | Files scanned: 13 (ui/) | Token estimate: ~700 -->

# UI

Jetpack Compose, Material 3, single activity. No Compose or instrumented test
lane exists — everything here is verified on hardware or not at all.

## Screen tree

```
MainActivity
  └─ BasculeApp (NavHost + bottom bar)
       ├─ Scale     ScaleScreen      ← ScaleViewModel  + ConfigViewModel
       ├─ History   HistoryScreen    ← HistoryViewModel + ScaleViewModel
       └─ Settings  ConfigScreen     ← ConfigViewModel
     (Manual entry) ManualEntryScreen ← ManualEntryViewModel
```

Manual entry was deliberately removed from the bottom bar (P25) — the rarest
action owned a quarter of primary navigation.

## ViewModels

| ViewModel | Owns |
|---|---|
| `ConfigViewModel` | Base URL, login, connection test, contract version, capture toggles, registration, backup import/export |
| `ScaleViewModel` | Bridge service control, `weighNow()`, profile delete, capture state |
| `HistoryViewModel` | Reading list, confirm/decline for `HELD_CONFIRM` |
| `ManualEntryViewModel` | Manual weigh-in entry with an `isSaving` guard |

`ScaleViewModel` is constructed once in `BasculeApp` against the **same**
`ViewModelStoreOwner` `ConfigViewModel` uses, so Scale and History read one
shared `weighNowActive` flag rather than two that can disagree.
`HistoryScreen`'s `scaleViewModel` parameter is required, not defaulted —
a `viewModel()` default silently resolved to a second, route-scoped instance.

## Constraint worth knowing

`ConfigViewModel` sits at **19 functions against detekt's ceiling of 20**
(`config/detekt/detekt.yml`). Adding a function there needs a design decision,
not a patch. Reverting Task 3's inlined helper is clean once addressed.

## Shared components

`WeighNowButton` (in `HistoryScreen.kt`, shared with `ScaleScreen`) ·
`RegisteredScaleSection` · `Banner` · `StatusLabel` ·
`SettingsBackupInput` · `PermissionRequester`

## Diagnostics surfaced to the user

- **Scale tab** — "Last attempt: `<time>` — `<outcome>`", from
  `CaptureAttemptLog`, which persists across processes.
- **Base URL field** — always-on supporting text naming the expected
  `/p/<slug>` shape; yields to the validation error when there is one.
- **Test connection** — a 404 names the missing person path rather than
  rendering the generic "rejected by server".
- **History banners** — capture-state warnings, tappable to the Scale tab.

## Known cosmetic debt

`Banner()` is hardcoded to `errorContainer` with a warning icon, so
informational banners render as red errors and up to four can stack
identically. `DECLINED` shares `SENT`'s colours. `HistoryScreen` calls
`isSystemInDarkTheme()` directly while `BasculeTheme` is parameterised. All
three need a device to judge, which is why they are still open.
