# Bascule UI Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Bascule a real app icon and remove the specific UI friction found by running it on hardware — without touching BLE protocol code or onboarding.

**Architecture:** Eight independent tasks against an existing Kotlin/Compose Android app. Each modifies one screen or one cross-cutting concern, is covered by JVM unit tests where logic exists, and commits on its own. No new modules, no new dependencies, no data migration.

**Tech Stack:** Kotlin 2.4.10, Jetpack Compose (Material3, BOM 2026.08.00), Room 2.8.4, WorkManager 2.11.2, JUnit4 + Robolectric 4.16.1, detekt.

**Spec:** `docs/superpowers/specs/2026-08-29-ui-modernization-design.md` — read it before starting; it carries the *why* and the evidence for every change below.

## Global Constraints

- **minSdk 26, compileSdk/targetSdk 37.** Adaptive icons are supported on every device; no legacy per-density PNG fallbacks.
- **Storage is always kilograms** (`00-design.md §2.7`). Never write a converted value to `ReadingEntity.weightKg`.
- **`detekt` runs with `maxIssues: 0`** and `buildUponDefaultConfig = true`. The limits that actually bite in this codebase: `MaxLineLength` **120**, `LongMethod` **60 lines**, default `LargeClass`, and `TooManyFunctions` **11 in interfaces** (`ReadingDao` is at the ceiling — do not add a method to it). If a change trips a threshold, **restructure (extract a function, split a test class); do not raise the threshold.**
- **No new dependencies.** AndroidX + kotlinx only; OkHttp is the one sanctioned exception and is already present.
- **Tests: fakes, not mocks.** Follow `app/src/test/kotlin/com/ventouxlabs/bascule/ui/fake/`. ViewModel tests need `MainDispatcherRule` and a `backgroundScope.launch { vm.uiState.collect {} }` collector, or `uiState.value` reads defaults.
- **Verification command:** `./gradlew testDebugUnitTest detekt`. Baseline before starting is **528 tests, 0 failures, detekt clean**.
- **Never uninstall the app to test.** `EncryptedPreferences` builds its `MasterKey` in the `AndroidKeyStore`; uninstalling destroys the BF720 registration irrecoverably. Always `adb install -r`.

---

### Task 1: Adaptive launcher icon

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/AndroidManifest.xml:42` (the `<application>` tag)

**Interfaces:**
- Consumes: nothing.
- Produces: `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`. No Kotlin surface.

All geometry is on a 108×108 viewport. The adaptive-icon safe zone is the central 72×72 (coordinates 18–90). Every **foreground and monochrome** path stays inside x 27–81, y 26–82. The **background is deliberately full-bleed** (`M0,0h108v108h-108z`) — it must extend past the safe zone, or masking to a launcher's shape leaves gaps at the edges.

- [ ] **Step 1: Create the background**

`app/src/main/res/drawable/ic_launcher_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">
            <gradient android:startX="54" android:startY="0" android:endX="54" android:endY="108"
                android:type="linear">
                <item android:offset="0" android:color="#FF4C34B4"/>
                <item android:offset="1" android:color="#FF241A57"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>
```

- [ ] **Step 2: Create the foreground**

`app/src/main/res/drawable/ic_launcher_foreground.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- base slab, ghosted -->
    <path android:fillColor="#FFA586F2" android:fillAlpha="0.30"
        android:pathData="M54,82 L81,67 L54,52 L27,67 Z"/>
    <!-- middle slab: left edge, right edge, top face -->
    <path android:fillColor="#FF553CBE" android:pathData="M27,56 L54,71 L54,75.5 L27,60.5 Z"/>
    <path android:fillColor="#FF4530A0" android:pathData="M81,56 L54,71 L54,75.5 L81,60.5 Z"/>
    <path android:fillColor="#FF7C5CE0" android:pathData="M54,71 L81,56 L54,41 L27,56 Z"/>
    <!-- top slab: left edge, right edge, top face -->
    <path android:fillColor="#FF17A8BF" android:pathData="M27,41 L54,56 L54,60.5 L27,45.5 Z"/>
    <path android:fillColor="#FF128FA3" android:pathData="M81,41 L54,56 L54,60.5 L81,45.5 Z"/>
    <path android:fillColor="#FF22D3EE" android:pathData="M54,56 L81,41 L54,26 L27,41 Z"/>
</vector>
```

- [ ] **Step 3: Create the monochrome layer**

Single-colour silhouette — three top faces only, so the stack still reads when the launcher tints it. `android:fillColor` **must** be `#FFFFFFFF`; the system applies its own tint.

`app/src/main/res/drawable/ic_launcher_monochrome.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:pathData="M54,56 L81,41 L54,26 L27,41 Z"/>
    <path android:fillColor="#FFFFFFFF" android:pathData="M54,71 L81,56 L54,41 L27,56 Z"/>
    <path android:fillColor="#FFFFFFFF" android:fillAlpha="0.55"
        android:pathData="M54,82 L81,67 L54,52 L27,67 Z"/>
</vector>
```

- [ ] **Step 4: Create both mipmap entries**

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` — and an identical copy at `ic_launcher_round.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
```

- [ ] **Step 5: Wire the manifest**

In `app/src/main/AndroidManifest.xml`, add to `<application>` alongside the existing `android:label`:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

- [ ] **Step 6: Verify resources compile and the icon renders**

```bash
./gradlew :app:processDebugResources
./gradlew installDebug
adb shell monkey -p com.ventouxlabs.bascule 1
```
Expected: resource task succeeds (a malformed vector or an unresolved `@drawable` fails it). Then confirm visually: the launcher shows the slab icon, not the default Android robot. Check the themed variant too (Settings → Wallpaper & style → Themed icons).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/drawable/ic_launcher_*.xml \
        app/src/main/res/mipmap-anydpi-v26/ app/src/main/AndroidManifest.xml
git commit -m "feat: add an adaptive launcher icon with a monochrome layer"
```

---

### Task 2: Demote manual entry out of the bottom bar

**Files:**
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/nav/BasculeDestination.kt`
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/BasculeApp.kt:46` (`BasculeDestination.entries.forEach`)
- Test: `app/src/test/kotlin/com/ventouxlabs/bascule/ui/nav/BasculeDestinationTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `BasculeDestination.inBottomBar: Boolean` and `BasculeDestination.Companion.bottomBarEntries: List<BasculeDestination>`. Task 8 does not depend on this.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/ventouxlabs/bascule/ui/nav/BasculeDestinationTest.kt`:

```kotlin
package com.ventouxlabs.bascule.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasculeDestinationTest {

    @Test
    fun bottomBarShowsHistoryScaleAndSettingsOnly() {
        assertEquals(
            listOf(BasculeDestination.History, BasculeDestination.Scale, BasculeDestination.Config),
            BasculeDestination.bottomBarEntries,
        )
    }

    /**
     * Manual entry is reachable only from History's FAB. Keeping it out of the
     * bar is the behavioural half of P25 — two entry points to one destination
     * gave it two different back-stack contracts.
     */
    @Test
    fun manualEntryIsStillARouteButNotABarItem() {
        assertFalse(BasculeDestination.ManualEntry.inBottomBar)
        assertTrue(BasculeDestination.entries.contains(BasculeDestination.ManualEntry))
        assertEquals("manual_entry", BasculeDestination.ManualEntry.route)
    }

    @Test
    fun everyRouteIsUnique() {
        val routes = BasculeDestination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew testDebugUnitTest --tests '*BasculeDestinationTest*'`
Expected: FAIL — `Unresolved reference 'bottomBarEntries'` and `'inBottomBar'`.

- [ ] **Step 3: Add the flag**

In `BasculeDestination.kt`, add the parameter and companion. Replace the existing KDoc — it currently claims a flat bar is lowest-friction because "every screen is one tap away from every other", which is no longer true of all four:

```kotlin
/**
 * The app's four top-level routes. Three are peers in the bottom bar, so each
 * is one tap from the others. [ManualEntry] is deliberately excluded: it is
 * the fallback for when the scale misses you, and giving the rarest action a
 * quarter of primary navigation — while *also* exposing it through History's
 * FAB — gave one destination two back-stack contracts (P25).
 */
enum class BasculeDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean = true,
) {
    History(route = "history", label = "History", icon = Icons.AutoMirrored.Filled.List),
    ManualEntry(route = "manual_entry", label = "Add weight", icon = Icons.Filled.Add, inBottomBar = false),
    Scale(route = "scale", label = "Scale", icon = Icons.Filled.MonitorWeight),
    Config(route = "config", label = "Settings", icon = Icons.Filled.Settings),
    ;

    companion object {
        val bottomBarEntries: List<BasculeDestination> = entries.filter { it.inBottomBar }
    }
}
```

- [ ] **Step 4: Use it in the shell**

In `BasculeApp.kt`, change the bar's iteration only. Leave the FAB block untouched — it is now the sole entry point:

```kotlin
BasculeDestination.bottomBarEntries.forEach { destination ->
```

- [ ] **Step 5: Verify**

Run: `./gradlew testDebugUnitTest detekt`
Expected: PASS, 531 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/ventouxlabs/bascule/ui/nav/BasculeDestination.kt \
        app/src/main/kotlin/com/ventouxlabs/bascule/ui/BasculeApp.kt \
        app/src/test/kotlin/com/ventouxlabs/bascule/ui/nav/BasculeDestinationTest.kt
git commit -m "feat: drop manual entry from the bottom bar, closing P25"
```

---

### Task 3: Registration enables automatic capture

**Files:**
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt` (both success paths: ~465–474 and ~504–508)
- Test: `app/src/test/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModelCaptureOnRegistrationTest.kt` (create)

**Interfaces:**
- Consumes: `ConfigStore.saveAutomaticCaptureEnabled(enabled: Boolean)` (already exists, `ConfigStore.kt:43`).
- Produces: a private `suspend fun onRegistrationSucceeded(address: String, scaleIndex: Int)` inside `ConfigViewModel`.

**Read first:** spec §3. There are **two** success paths and only one involves the scale. Adding the capture-enable to just one is this task's known failure mode — hence the extracted helper.

- [ ] **Step 1: Write the failing tests**

New file (not added to `ConfigViewModelTest` — that class is already at detekt's `LargeClass` ceiling):

```kotlin
package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.FakeSessionCookieStore
import com.ventouxlabs.bascule.ui.fake.FakeVitalForgeApi
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Registration is the consent: nobody completes a scale handshake without
 * meaning for the app to read it. Both registration routes must agree —
 * enabling capture on only one would mean registering via the scale works and
 * linking by hand silently does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelCaptureOnRegistrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun TestScope.viewModel(configStore: FakeConfigStore): ConfigViewModel {
        val vm = ConfigViewModel(
            configStore,
            FakeAuthTokenStore(),
            InMemoryConsentStore(),
            FakeSessionCookieStore(),
            FakeDeliveryTrigger(),
            FakeReadingDao(),
            ioDispatcher = mainDispatcherRule.dispatcher,
            apiFactory = { FakeVitalForgeApi() },
        )
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun linkingAnExistingScaleEnablesAutomaticCapture() = runTest {
        val configStore = FakeConfigStore()
        assertFalse(
            "precondition: capture ships off",
            configStore.automaticCaptureEnabled.first(),
        )
        val vm = viewModel(configStore)
        advanceUntilIdle()

        vm.linkExistingScale("E7:DB:51:F1:36:91", "2", "1234")
        advanceUntilIdle()

        assertTrue(configStore.automaticCaptureEnabled.first())
    }

    @Test
    fun aRejectedLinkDoesNotEnableAutomaticCapture() = runTest {
        val configStore = FakeConfigStore()
        val vm = viewModel(configStore)
        advanceUntilIdle()

        // Consent code outside SigWeightProfile.CONSENT_CODE_RANGE — the
        // validation branch, which must not reach the success helper.
        vm.linkExistingScale("E7:DB:51:F1:36:91", "2", "99999999")
        advanceUntilIdle()

        assertFalse(configStore.automaticCaptureEnabled.first())
        assertTrue(vm.uiState.value.scaleRegistration is ScaleRegistrationUiState.Failure)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew testDebugUnitTest --tests '*CaptureOnRegistrationTest*'`
Expected: FAIL — `linkingAnExistingScaleEnablesAutomaticCapture` asserts `true` but gets `false`. The rejection test should already pass; that is fine, it is a guard against over-reach in Step 3.

- [ ] **Step 3: Extract the shared tail and enable capture in it**

Both paths currently end in the same four calls. Add this private function to `ConfigViewModel`:

```kotlin
/**
 * The tail both registration routes share — the BLE handshake and
 * [linkExistingScale]. Extracted because enabling capture in only one would
 * make registering via the scale work while linking by hand silently did
 * not, with nothing to report the difference.
 *
 * Capture is enabled here rather than defaulted on in `ConfigStore`: a bare
 * default would arm background scanning for someone who never asked, whereas
 * completing a registration is an unambiguous statement of intent. The Scale
 * screen's toggle still turns it back off.
 */
private suspend fun onRegistrationSucceeded(address: String, scaleIndex: Int) {
    activateLinkedProfile(address, scaleIndex)
    configStore.saveAutomaticCaptureEnabled(true)
    rearmScanner?.invoke()
    _consentVersion.value++
    _scaleRegistration.value = ScaleRegistrationUiState.Success(address, scaleIndex)
}
```

In the `ScaleRegistrationResult.Success` branch, replace the four calls with:

```kotlin
is ScaleRegistrationResult.Success -> onRegistrationSucceeded(result.address, result.scaleIndex)
```

In `linkExistingScale`'s `else ->` branch, keep the `consentStore.save(...)` and `configStore.savePairedDeviceAddress(...)` calls (they are specific to that path) and replace the trailing four with:

```kotlin
onRegistrationSucceeded(normalizedAddress, scaleIndexValue)
```

- [ ] **Step 4: Verify**

Run: `./gradlew testDebugUnitTest detekt`
Expected: PASS, 533 tests. Existing `ConfigViewModelRegistrationActivationTest` must still pass — if it fails, the extraction changed ordering; `activateLinkedProfile` must stay first.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt \
        app/src/test/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModelCaptureOnRegistrationTest.kt
git commit -m "feat: enable automatic capture when a scale is registered"
```

---

### Task 4: Pin delivery status colours

**Files:**
- Create: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/theme/StatusColor.kt`
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/theme/Color.kt` (Step 5 — the `Teal*` → `Violet*` rename)
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/theme/Theme.kt` (Step 5 — the renamed references in `LightColors`/`DarkColors`)
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryScreen.kt` (the `statusColors` composable near the end of the file)
- Test: `app/src/test/kotlin/com/ventouxlabs/bascule/ui/theme/StatusColorTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `statusPalette(status: ReadingStatus, darkTheme: Boolean): StatusPalette` in `theme/StatusColor.kt`, where `data class StatusPalette(val container: Color, val content: Color)`.

**Why:** dynamic colour is on by default (SDK 31+), so today the chips inherit from the wallpaper and how distinguishable `blocked auth` is from `sent` is wallpaper-dependent.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/ventouxlabs/bascule/ui/theme/StatusColorTest.kt`:

```kotlin
package com.ventouxlabs.bascule.ui.theme

import androidx.compose.ui.graphics.Color
import com.ventouxlabs.bascule.data.ReadingStatus
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class StatusColorTest {

    /** WCAG relative luminance, then the standard (L1+0.05)/(L2+0.05) ratio. */
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun everyStatusIsLegibleInBothThemes() {
        for (status in ReadingStatus.entries) {
            for (dark in listOf(false, true)) {
                val palette = statusPalette(status, dark)
                val ratio = contrast(palette.container, palette.content)
                assertTrue(
                    "$status (dark=$dark) contrast $ratio is below the WCAG AA 4.5:1 floor",
                    ratio >= 4.5,
                )
            }
        }
    }

    /**
     * The point of pinning these: a delivery that needs the user to act must
     * never look like one that succeeded, on any wallpaper.
     */
    @Test
    fun blockedIsVisuallyDistinctFromSent() {
        for (dark in listOf(false, true)) {
            assertNotEquals(
                statusPalette(ReadingStatus.SENT, dark).container,
                statusPalette(ReadingStatus.BLOCKED_AUTH, dark).container,
            )
        }
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew testDebugUnitTest --tests '*StatusColorTest*'`
Expected: FAIL — `Unresolved reference 'statusPalette'`.

- [ ] **Step 3: Add the tokens**

Create `app/src/main/kotlin/com/ventouxlabs/bascule/ui/theme/StatusColor.kt` (a new file rather than growing `Color.kt`, and it keeps the pure function out of the Compose screen so the JVM lane can call it):

```kotlin
package com.ventouxlabs.bascule.ui.theme

import androidx.compose.ui.graphics.Color
import com.ventouxlabs.bascule.data.ReadingStatus

/** Container plus the content colour guaranteed legible on it. */
data class StatusPalette(val container: Color, val content: Color)

/**
 * Delivery status is the one thing on History that must not be re-tinted by
 * the wallpaper: dynamic colour is on by default, so leaving these to
 * `MaterialTheme.colorScheme` made "needs your attention" and "delivered"
 * as distinguishable as the user's background happened to allow.
 *
 * Values are chosen to clear WCAG AA (4.5:1) against their own container in
 * both themes — pinned by `StatusColorTest`, not by eye.
 */
fun statusPalette(status: ReadingStatus, darkTheme: Boolean): StatusPalette = when (status) {
    ReadingStatus.BLOCKED_AUTH, ReadingStatus.FAILED_PERMANENT ->
        if (darkTheme) StatusPalette(Color(0xFF5C1A1A), Color(0xFFFFD9D6))
        else StatusPalette(Color(0xFFFFDAD6), Color(0xFF6B1010))

    ReadingStatus.HELD_CONFIRM ->
        if (darkTheme) StatusPalette(Color(0xFF4A3A08), Color(0xFFFFE8A8))
        else StatusPalette(Color(0xFFFFEBC2), Color(0xFF5A4304))

    ReadingStatus.PENDING ->
        if (darkTheme) StatusPalette(Color(0xFF283044), Color(0xFFC7D3F0))
        else StatusPalette(Color(0xFFE2E8F8), Color(0xFF2B3550))

    ReadingStatus.SENT, ReadingStatus.DECLINED ->
        if (darkTheme) StatusPalette(Color(0xFF2A2A31), Color(0xFFC9C9D2))
        else StatusPalette(Color(0xFFEBEBF1), Color(0xFF44444E))
}
```

- [ ] **Step 4: Use it in HistoryScreen**

Replace the existing `statusColors` composable with a delegation, keeping its `Pair` shape so call sites do not change:

```kotlin
@Composable
private fun statusColors(status: ReadingStatus): Pair<Color, Color> {
    val palette = statusPalette(status, isSystemInDarkTheme())
    return palette.container to palette.content
}
```

Add `import androidx.compose.foundation.isSystemInDarkTheme` and `import com.ventouxlabs.bascule.ui.theme.statusPalette`.

- [ ] **Step 5: Re-tune the non-dynamic fallback palette (spec §4, final paragraph)**

`Color.kt`'s `TealPrimaryLight` (`#00696B`) / clay / blue scheme renders only when dynamic colour is unavailable — pre-Android-12, or if `useDynamicColor` is ever passed `false`. Bring its primary family into line with the icon's violet so the fallback does not look like a different app:

```kotlin
val TealPrimaryLight = Color(0xFF553CBE)
val TealPrimaryContainerLight = Color(0xFFE6DEFF)
val OnTealPrimaryContainerLight = Color(0xFF1B0A63)
```

and the dark equivalents in the same block:

```kotlin
val TealPrimaryDark = Color(0xFFC9B4F8)
val TealPrimaryContainerDark = Color(0xFF3E2A96)
val OnTealPrimaryContainerDark = Color(0xFFE6DEFF)
```

**Rename the properties to `VioletPrimaryLight` etc. in the same edit** — leaving violet values behind `Teal*` names is worse than not changing them. Update the references in `Theme.kt`'s `LightColors`/`DarkColors`. Match whatever `on*` names already exist in `Color.kt`; do not invent new roles.

This is deliberately cosmetic and unreachable on the test device (SDK 37 always takes the dynamic branch). It is in scope only so the fallback is not stale; do not spend time tuning it beyond a straight swap.

- [ ] **Step 6: Verify**

Run: `./gradlew testDebugUnitTest detekt`
Expected: PASS, 535 tests. If a contrast assertion fails, adjust the offending hex — **do not lower the 4.5 threshold.** If `Theme.kt` fails to compile, a `Teal*` reference was missed in the rename.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/ventouxlabs/bascule/ui/theme/StatusColor.kt \
        app/src/main/kotlin/com/ventouxlabs/bascule/ui/theme/Color.kt \
        app/src/main/kotlin/com/ventouxlabs/bascule/ui/theme/Theme.kt \
        app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryScreen.kt \
        app/src/test/kotlin/com/ventouxlabs/bascule/ui/theme/StatusColorTest.kt
git commit -m "fix: pin delivery status colours so they do not depend on the wallpaper"
```

---

### Task 5: History honours the selected weight unit

**Files:**
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryFormatting.kt`
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryViewModel.kt` (`HistoryUiState`, constructor, factory)
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryScreen.kt` (the row's weight `Text`)
- Modify: `app/src/test/kotlin/com/ventouxlabs/bascule/ui/HistoryFormattingTest.kt`

**Interfaces:**
- Consumes: `ConfigStore.displayUnit: Flow<WeightUnit>`.
- Produces: `formatWeight(reading: ReadingEntity, unit: WeightUnit): String` and `HistoryUiState.displayUnit: WeightUnit`.

**Read first:** spec §6. This is a bug fix, not a preference tweak — and it retires a test that pins known-wrong behaviour. **Replace that test; do not delete it.**

- [ ] **Step 1: Rewrite the formatting tests**

In `HistoryFormattingTest.kt`, replace **all four** `formatWeight` tests — `rendersTheStoredKilogramsWhenTheDisplayUnitIsKilograms`, `convertsToPoundsWhenTheDisplayUnitIsPounds`, the corrupt-unit test, and `alwaysRendersExactlyOneDecimalPlace` (it calls the changed signature too, so leaving it will not compile) — with:

```kotlin
    // --- formatWeight: storage is always kilograms; the user's current
    // --- preference decides the number shown, not the row's stored unit.

    @Test
    fun rendersKilogramsWhenTheUserSelectedKilograms() {
        assertEquals(
            "90.8",
            formatWeight(readingFixture(weightKg = 90.82, displayUnit = "kg"), WeightUnit.KILOGRAMS),
        )
    }

    @Test
    fun convertsToPoundsWhenTheUserSelectedPounds() {
        assertEquals(
            "200.2",
            formatWeight(readingFixture(weightKg = 90.82, displayUnit = "kg"), WeightUnit.POUNDS),
        )
    }

    /**
     * Replaces the old corrupt-`displayUnit` test, which pinned a real defect:
     * an unrecognised stored unit fell back to kilograms and rendered a wrong
     * number (90.8 where a pounds row should read 200.2) with nothing marking
     * it. Display no longer reads the stored string at all, so that failure
     * mode cannot occur — this test exists to keep the record that it once
     * could, and to fail if anyone reintroduces the dependency.
     */
    @Test
    fun aCorruptStoredUnitNoLongerAffectsWhatIsDisplayed() {
        val corrupt = readingFixture(weightKg = 90.82, displayUnit = "not-a-unit")
        assertEquals("200.2", formatWeight(corrupt, WeightUnit.POUNDS))
        assertEquals("90.8", formatWeight(corrupt, WeightUnit.KILOGRAMS))
    }

    @Test
    fun alwaysRendersExactlyOneDecimalPlace() {
        assertEquals("70.0", formatWeight(readingFixture(weightKg = 70.0), WeightUnit.KILOGRAMS))
        assertEquals("70.3", formatWeight(readingFixture(weightKg = 70.25), WeightUnit.KILOGRAMS))
    }
```

Add `import com.ventouxlabs.bascule.data.WeightUnit` if absent.

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew testDebugUnitTest --tests '*HistoryFormattingTest*'`
Expected: FAIL — too many arguments for `formatWeight`.

- [ ] **Step 3: Change the formatter**

In `HistoryFormatting.kt`, replace `formatWeight` and its KDoc:

```kotlin
/**
 * Storage is always kilograms (`00-design.md` §2.7); `ReadingEntity.displayUnit`
 * records only which unit was on screen when the row was captured, which is a
 * UI preference rather than part of the measurement. Rendering therefore uses
 * the user's *current* unit — the stored string is not consulted, so a corrupt
 * one can no longer produce a silently wrong number.
 */
internal fun formatWeight(reading: ReadingEntity, unit: WeightUnit): String =
    "%.1f".format(unit.fromKilograms(reading.weightKg))
```

- [ ] **Step 4: Thread the unit through the ViewModel**

Add to `HistoryUiState`:

```kotlin
    val displayUnit: WeightUnit = WeightUnit.KILOGRAMS,
```

Add a `configStore: ConfigStore` constructor parameter to `HistoryViewModel`, include `configStore.displayUnit` in the existing `combine`, and set `displayUnit` on the emitted state. Update the factory to pass `app.configStore`.

**Note:** `combine` tops out at 5 typed flows. If this pushes the existing call over, nest as `ConfigViewModel` already does for its own `transientState` — do not restructure the whole flow.

- [ ] **Step 5: Use it in the row**

In `HistoryScreen.kt`, replace the weight `Text`'s first argument. It currently reads `"${formatWeight(reading)} ${reading.displayUnit}"`:

```kotlin
"${formatWeight(reading, state.displayUnit)} ${state.displayUnit.wire}",
```

`ReadingRow` will need `state.displayUnit` passed in as a `unit: WeightUnit` parameter; thread it from the `items(state.rows)` call site.

- [ ] **Step 6: Verify**

Run: `./gradlew testDebugUnitTest detekt`
Expected: PASS, 535 tests (count unchanged — tests were replaced, not added).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryFormatting.kt \
        app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryViewModel.kt \
        app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryScreen.kt \
        app/src/test/kotlin/com/ventouxlabs/bascule/ui/HistoryFormattingTest.kt
git commit -m "fix: render history in the selected unit, not each row's capture unit"
```

---

### Task 6: History shows capture state

**Files:**
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryViewModel.kt`
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryScreen.kt`
- Test: `app/src/test/kotlin/com/ventouxlabs/bascule/ui/HistoryCaptureStateTest.kt` (create)

**Interfaces:**
- Consumes: `HistoryViewModel`'s `configStore` (added in Task 5), `ConfigStore.automaticCaptureEnabled`, `ConfigStore.pairedDeviceAddress`.
- Produces: `HistoryUiState.captureState: CaptureState`, an enum `CaptureState { WATCHING, OFF, NO_SCALE }`.

**Depends on Task 5** for the `configStore` constructor parameter.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ventouxlabs.bascule.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A registered scale with capture switched off looks identical to "nobody has
 * weighed in" — the app is silently doing nothing, which is the worst state
 * for a background-capture app to be in without saying so.
 */
class HistoryCaptureStateTest {

    @Test
    fun noScalePairedIsDistinctFromCaptureOff() {
        assertEquals(CaptureState.NO_SCALE, captureStateOf(pairedAddress = null, captureEnabled = false))
        assertEquals(CaptureState.NO_SCALE, captureStateOf(pairedAddress = null, captureEnabled = true))
    }

    @Test
    fun aPairedScaleWithCaptureOffReportsOff() {
        assertEquals(
            CaptureState.OFF,
            captureStateOf(pairedAddress = "E7:DB:51:F1:36:91", captureEnabled = false),
        )
    }

    @Test
    fun aPairedScaleWithCaptureOnReportsWatching() {
        assertEquals(
            CaptureState.WATCHING,
            captureStateOf(pairedAddress = "E7:DB:51:F1:36:91", captureEnabled = true),
        )
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew testDebugUnitTest --tests '*HistoryCaptureStateTest*'`
Expected: FAIL — `Unresolved reference 'CaptureState'` / `'captureStateOf'`.

- [ ] **Step 3: Implement**

In `HistoryViewModel.kt`, above the class:

```kotlin
/** What History tells the user the app is currently doing about the scale. */
enum class CaptureState { WATCHING, OFF, NO_SCALE }

/**
 * Pure so the JVM lane can cover it. `NO_SCALE` outranks `OFF` because with
 * nothing paired the capture flag is not the thing standing in the user's way.
 */
internal fun captureStateOf(pairedAddress: String?, captureEnabled: Boolean): CaptureState = when {
    pairedAddress.isNullOrBlank() -> CaptureState.NO_SCALE
    captureEnabled -> CaptureState.WATCHING
    else -> CaptureState.OFF
}
```

Add `val captureState: CaptureState = CaptureState.NO_SCALE` to `HistoryUiState` and fold `configStore.automaticCaptureEnabled` and `configStore.pairedDeviceAddress` into the combine, calling `captureStateOf(...)`.

- [ ] **Step 4: Render it**

In `HistoryScreen.kt`, add above the existing banners:

```kotlin
        when (state.captureState) {
            CaptureState.OFF -> item {
                Banner(text = "Automatic capture is off — weigh-ins won't be picked up.")
            }
            CaptureState.NO_SCALE -> item {
                Banner(text = "No scale registered yet. Add one on the Scale tab.")
            }
            CaptureState.WATCHING -> Unit
        }
```

- [ ] **Step 5: Verify**

Run: `./gradlew testDebugUnitTest detekt`
Expected: PASS, 538 tests. If `HistoryScreen`'s composable trips `LongMethod`, extract the banner block into a private `@Composable` rather than raising the limit.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryViewModel.kt \
        app/src/main/kotlin/com/ventouxlabs/bascule/ui/HistoryScreen.kt \
        app/src/test/kotlin/com/ventouxlabs/bascule/ui/HistoryCaptureStateTest.kt
git commit -m "feat: show capture state on History so a silent no-op is visible"
```

---

### Task 7: Remove the single-option contract-version dropdown

**Files:**
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigScreen.kt:315-339` (`selectableContractVersions`, `UnitAndContractSection`)
- Modify: `HANDOFF.md` (the V2-gate bullet under "Known open items")
- Modify: `app/src/test/kotlin/com/ventouxlabs/bascule/ui/ContractVersionSelectionTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. `ConfigViewModel.saveContractVersion` and the stored value stay; only the control goes.

**Read first:** spec §5.1. `ContractVersion` has two entries and `selectableContractVersions` filters one out, so the dropdown offers exactly one choice.

- [ ] **Step 1: Add the tripwire test and de-stale an existing message**

All three existing tests in `ContractVersionSelectionTest` stay: they exercise `selectableContractVersions`, which survives as the import-side gate. But `offersTheShippedV1Contract`'s failure message reads "gating v2 must not leave the dropdown with nothing to choose", and after Step 3 there is no dropdown. Change that message to:

```kotlin
            "gating v2 must not leave the import path with no valid contract",
```

Then add a fourth test — the tripwire that makes the deletion's justification falsifiable:

```kotlin
    /**
     * The Settings dropdown was removed because this list has exactly one
     * entry, making it a control the user cannot change (spec §5.1). If a
     * second version ever becomes selectable this fails, which is the signal
     * to bring the control back — not to relax the assertion.
     */
    @Test
    fun exactlyOneContractVersionIsSelectableSoNoControlIsWarranted() {
        assertEquals(listOf(ContractVersion.V1_WEIGHT_ONLY), selectableContractVersions)
    }
```

- [ ] **Step 2: Run and confirm it passes**

Run: `./gradlew testDebugUnitTest --tests '*ContractVersionSelectionTest*'`
Expected: PASS, 4 tests. **This is the one step in the plan with no red phase**, because the change in Step 3 is a deletion — the test documents its precondition rather than driving it.

- [ ] **Step 3: Remove the control**

In `UnitAndContractSection`, delete the `LabeledDropdown` block for "VitalForge contract version" and the now-unused `onContractChanged` parameter, updating its call site. Rename the composable to `UnitSection`. Keep `selectableContractVersions` and its KDoc — it is still the import-path gate.

If `LabeledDropdown` has no other caller after this, leave it: `UnitSection`'s weight-unit dropdown still uses it.

- [ ] **Step 4: Update HANDOFF.md**

The "V2 contract field names" bullet says both `ConfigScreen`'s `selectableContractVersions` and `ConfigViewModel`'s import gate "need deleting together when the doc lands". Rewrite it to say the UI dropdown is gone, that `selectableContractVersions` survives as the import-side gate plus the tripwire test, and what to restore when the contract doc arrives.

- [ ] **Step 5: Verify**

Run: `./gradlew testDebugUnitTest detekt`
Expected: PASS, 539 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigScreen.kt \
        app/src/test/kotlin/com/ventouxlabs/bascule/ui/ContractVersionSelectionTest.kt HANDOFF.md
git commit -m "fix: remove the contract-version dropdown, which offered one option"
```

---

### Task 8: Plain-language Scale screen copy

**Files:**
- Modify: `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ScaleScreen.kt:79` and `:123`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

No unit test: this task changes only user-visible strings, and a test asserting a literal equals itself would be vacuous. Verification is visual, in Step 3.

- [ ] **Step 1: Rewrite line 123**

Replace `"Scale inventory may be incomplete until List All Users capability probing is supported."` with:

```kotlin
            Text("Bascule may not know about every user slot stored on the scale itself.")
```

- [ ] **Step 2: Rewrite line 79**

Replace `"Profile deletion is disabled until consent verification and typed " + ...` with:

```kotlin
                    "Profiles can't be removed yet — you can re-register a scale to replace one.",
```

Keep the surrounding `Text(...)` call and its modifiers; only the string changes. If the original was a multi-line concatenation, collapse it to the single string above and check the line stays under 120 characters.

- [ ] **Step 3: Verify**

```bash
./gradlew testDebugUnitTest detekt && ./gradlew installDebug
adb shell am start -n com.ventouxlabs.bascule/.MainActivity
```
Then screenshot the Scale tab and confirm neither "capability probing" nor "consent verification" appears:

```bash
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png /tmp/scale.png && adb shell rm /sdcard/s.png
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/ventouxlabs/bascule/ui/ScaleScreen.kt
git commit -m "docs: rewrite Scale screen copy in user terms"
```

---

## Final verification (after all tasks)

- [ ] `./gradlew testDebugUnitTest detekt` — expect **539 tests, 0 failures, detekt clean**.
- [ ] `adb install -r app/build/outputs/apk/debug/app-debug.apk` — **never uninstall**.
- [ ] Screenshot all three tabs plus manual entry, in light and dark.
- [ ] Confirm the launcher icon renders, standard and themed.
- [ ] Exercise Task 3 on hardware via **`linkExistingScale`** with the known BF720 credentials (`E7:DB:51:F1:36:91`, slot 2, consent 1234) — writes local encrypted prefs only, no BLE, burns no slot. Confirm the Scale screen's capture toggle flips on.
- [ ] Update `HANDOFF.md` with what was verified on hardware and what was not.

**Report honestly:** the `0x2A9F` handshake path of Task 3 **cannot** be hardware-verified without the physical BF720. It shares the extracted helper with the path that was verified, which is an argument for correctness, not an observation of it. Do not report it as hardware-verified.
