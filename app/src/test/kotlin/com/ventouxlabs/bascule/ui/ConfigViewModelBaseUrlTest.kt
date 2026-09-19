package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.FakeSessionCookieStore
import com.ventouxlabs.bascule.ui.fake.FakeVitalForgeApi
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * What saving a base URL does to the delivery backlog: a same-host correction
 * makes backing-off rows due and drains, a host change does neither. Split
 * from [ConfigViewModelTest], which is at detekt's `LargeClass` ceiling; the
 * validation cases (`baseUrlRejects…`) stay there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelBaseUrlTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Nothing here reads `uiState`, so no collector is needed — see [ConfigViewModelProfileRegistryTest.viewModel]. */
    private fun viewModel(
        configStore: FakeConfigStore = FakeConfigStore(),
        deliveryTrigger: FakeDeliveryTrigger = FakeDeliveryTrigger(),
        dao: FakeReadingDao = FakeReadingDao(),
    ) = ConfigViewModel(
        configStore,
        FakeAuthTokenStore(),
        InMemoryConsentStore(),
        FakeSessionCookieStore(),
        deliveryTrigger,
        dao,
        ioDispatcher = mainDispatcherRule.dispatcher,
        apiFactory = { FakeVitalForgeApi() },
    )

    /**
     * A row backing off against a URL that was wrong (404ing, redirecting)
     * has no reason to keep waiting once the user has fixed the very thing
     * that was failing — the ladder is up to 15 minutes deep by then.
     * `saveToken` already makes the backlog due and drains the moment the
     * credential changes; a same-host URL correction (the real case: adding
     * the `/p/{slug}` path VitalForge needs) is the same kind of event.
     */
    @Test
    fun correctingTheBaseUrlPathOnTheSameHostMakesBackingOffRowsDueAndDrainsImmediately() = runTest {
        val configStore = FakeConfigStore(initialBaseUrl = "https://weight.example/")
        val deliveryTrigger = FakeDeliveryTrigger()
        val dao = FakeReadingDao()
        dao.insert(
            readingFixture(id = "backing-off", status = ReadingStatus.PENDING, nextAttemptMillis = Long.MAX_VALUE),
        )
        val vm = viewModel(configStore = configStore, deliveryTrigger = deliveryTrigger, dao = dao)
        advanceUntilIdle()

        vm.saveBaseUrl("https://weight.example/p/slug")
        advanceUntilIdle()

        assertNull(
            "the wait was earned against the URL that was wrong; it must not outlive the correction",
            dao.rows.value.single { it.id == "backing-off" }.nextAttemptMillis,
        )
        assertEquals(
            "making the row due without a drain would leave it waiting for the periodic one",
            1,
            deliveryTrigger.triggerCount,
        )
    }

    /**
     * Same gate `importSettings` applies, for the same reason: a host change
     * is the one thing that flow cannot tell apart from a hostile repoint, so
     * it never resurrects rows straight into a drain. The manual path is a
     * deliberate act and parks nothing — but it must not become the *faster*
     * way to send the stored credential to a new host either. Drain timing on
     * a host change stays exactly what it is today: the periodic drain.
     */
    @Test
    fun changingTheBaseUrlToADifferentHostLeavesTheBackoffAndTheDrainTimingAlone() = runTest {
        val configStore = FakeConfigStore(initialBaseUrl = "https://weight.example/p/slug")
        val deliveryTrigger = FakeDeliveryTrigger()
        val dao = FakeReadingDao()
        dao.insert(
            readingFixture(id = "backing-off", status = ReadingStatus.PENDING, nextAttemptMillis = Long.MAX_VALUE),
        )
        val vm = viewModel(configStore = configStore, deliveryTrigger = deliveryTrigger, dao = dao)
        advanceUntilIdle()

        vm.saveBaseUrl("https://other.example/p/slug")
        advanceUntilIdle()

        assertEquals("the save itself must still land", "https://other.example/p/slug", configStore.baseUrl.value)
        assertEquals(Long.MAX_VALUE, dao.rows.value.single { it.id == "backing-off" }.nextAttemptMillis)
        assertEquals("a host change must not be the fast path to a new host", 0, deliveryTrigger.triggerCount)
    }

    /**
     * `hostOf` compares host *and port* — see its KDoc — and this call site
     * has to inherit that, or a staging deployment on the same domain would
     * count as the same server here while the import path says otherwise.
     */
    @Test
    fun changingOnlyThePortIsAHostChangeAtThisCallSiteToo() = runTest {
        val configStore = FakeConfigStore(initialBaseUrl = "https://weight.example/p/slug")
        val deliveryTrigger = FakeDeliveryTrigger()
        val dao = FakeReadingDao()
        dao.insert(
            readingFixture(id = "backing-off", status = ReadingStatus.PENDING, nextAttemptMillis = Long.MAX_VALUE),
        )
        val vm = viewModel(configStore = configStore, deliveryTrigger = deliveryTrigger, dao = dao)
        advanceUntilIdle()

        vm.saveBaseUrl("https://weight.example:8443/p/slug")
        advanceUntilIdle()

        assertEquals(Long.MAX_VALUE, dao.rows.value.single { it.id == "backing-off" }.nextAttemptMillis)
        assertEquals("same hostname, different port, is a different server", 0, deliveryTrigger.triggerCount)
    }

    /**
     * No previous host means nothing to redirect away from — the same
     * exemption `importSettings` grants a first restore. A capture that
     * happened before the server was ever configured is exactly the row
     * that should go out the moment it is.
     */
    @Test
    fun theFirstEverBaseUrlSaveIsNotGatedOnAPreviousHost() = runTest {
        val deliveryTrigger = FakeDeliveryTrigger()
        val dao = FakeReadingDao()
        dao.insert(
            readingFixture(id = "backing-off", status = ReadingStatus.PENDING, nextAttemptMillis = Long.MAX_VALUE),
        )
        val vm = viewModel(deliveryTrigger = deliveryTrigger, dao = dao)
        advanceUntilIdle()

        vm.saveBaseUrl("https://weight.example/p/slug")
        advanceUntilIdle()

        assertNull(dao.rows.value.single { it.id == "backing-off" }.nextAttemptMillis)
        assertEquals(1, deliveryTrigger.triggerCount)
    }
}
