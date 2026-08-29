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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The credentials card has to distinguish "a credential is stored" from "the
 * stored credential still works". Split out of [ConfigViewModelTest], which is
 * already at detekt's `LargeClass` ceiling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelCredentialRejectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** See [ConfigViewModelTest.viewModel] on why `uiState` needs a live collector. */
    private fun TestScope.viewModel(
        sessionCookieStore: FakeSessionCookieStore = FakeSessionCookieStore(),
        dao: FakeReadingDao = FakeReadingDao(),
    ): ConfigViewModel {
        val vm = ConfigViewModel(
            FakeConfigStore(),
            FakeAuthTokenStore(),
            InMemoryConsentStore(),
            sessionCookieStore,
            FakeDeliveryTrigger(),
            dao,
            ioDispatcher = mainDispatcherRule.dispatcher,
            apiFactory = { FakeVitalForgeApi() },
        )
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    /**
     * Found on the first real-hardware run: the credentials card read
     * "Signed in via username/password" off `sessionIsSet`, which only means a
     * cookie is *stored*, not that the server still accepts it. With a
     * six-day-stale session the Settings screen claimed to be signed in while
     * History simultaneously said "VitalForge needs your login again" — both
     * from the same state. `BLOCKED_AUTH` rows are the app's own evidence that
     * the stored credential was rejected, so the card has to consult them.
     */
    @Test
    fun credentialIsReportedRejectedWhenStoredSessionHasBlockedRows() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "blocked", status = ReadingStatus.BLOCKED_AUTH))
        val vm = viewModel(sessionCookieStore = FakeSessionCookieStore("stale-cookie"), dao = dao)
        advanceUntilIdle()

        assertTrue(
            "a stored cookie the server rejects is not 'signed in'",
            vm.uiState.value.credentialRejected,
        )
        assertTrue(
            "the credential is still set — it is rejected, not absent",
            vm.uiState.value.sessionIsSet,
        )
    }

    @Test
    fun credentialIsNotReportedRejectedWhenNothingIsBlocked() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "sent", status = ReadingStatus.SENT))
        dao.insert(readingFixture(id = "pending", status = ReadingStatus.PENDING))
        val vm = viewModel(sessionCookieStore = FakeSessionCookieStore("good-cookie"), dao = dao)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.credentialRejected)
    }

    /**
     * Guards the inverse mistake: with no credential stored the card already
     * says "Not signed in", which is accurate. Reporting *rejected* there
     * would be a second wrong message, not a fix.
     */
    @Test
    fun credentialIsNotReportedRejectedWhenNoCredentialIsStored() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "blocked", status = ReadingStatus.BLOCKED_AUTH))
        val vm = viewModel(dao = dao)
        advanceUntilIdle()

        assertFalse(
            "nothing is stored, so there is no credential to have been rejected",
            vm.uiState.value.credentialRejected,
        )
        assertFalse(vm.uiState.value.sessionIsSet)
    }

    /** The §8.6 recovery must clear the warning, or it would stick forever after a good re-login. */
    @Test
    fun rejectedFlagClearsOnceANewCredentialUnblocksTheQueue() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "blocked", status = ReadingStatus.BLOCKED_AUTH))
        val vm = viewModel(sessionCookieStore = FakeSessionCookieStore("stale-cookie"), dao = dao)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.credentialRejected)

        vm.saveToken("a-fresh-token")
        advanceUntilIdle()

        assertFalse(
            "saving a credential unblocks the rows, so the warning must go",
            vm.uiState.value.credentialRejected,
        )
    }
}
