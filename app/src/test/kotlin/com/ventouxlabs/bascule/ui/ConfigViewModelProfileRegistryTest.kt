package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.data.BackupCredentialType
import com.ventouxlabs.bascule.data.PortableSettings
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.ScaleProfileStore
import com.ventouxlabs.bascule.data.SettingsBackupCodec
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.data.fake.FakeScaleProfileStore
import com.ventouxlabs.bascule.network.ContractVersion
import com.ventouxlabs.bascule.network.AuthTokenStore
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.readingFixture
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ble.ScaleRegistrar
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.FakeSessionCookieStore
import com.ventouxlabs.bascule.ui.fake.FakeVitalForgeApi
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [ConfigViewModel]'s scale-profile registry paths: export, import, and manual
 * linking. Split from [ConfigViewModelTest] because these are the only cases
 * that need a real [ScaleProfileStore] wired in, and because the combined class
 * exceeded detekt's LargeClass threshold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelProfileRegistryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        configStore: FakeConfigStore = FakeConfigStore(),
        consentStore: ConsentStore = InMemoryConsentStore(),
        sessionCookieStore: FakeSessionCookieStore = FakeSessionCookieStore(),
        scaleRegistrar: ScaleRegistrar? = null,
        scaleProfileStore: ScaleProfileStore? = null,
        rearmScanner: (suspend () -> Unit)? = null,
        deliveryTrigger: DeliveryTrigger = FakeDeliveryTrigger(),
        dao: FakeReadingDao = FakeReadingDao(),
        authTokenStore: AuthTokenStore = FakeAuthTokenStore(),
    ) = ConfigViewModel(
        configStore,
        authTokenStore,
        consentStore,
        sessionCookieStore,
        deliveryTrigger,
        dao,
        ioDispatcher = mainDispatcherRule.dispatcher,
        scaleRegistrar = scaleRegistrar,
        scaleProfileStore = scaleProfileStore,
        rearmScanner = rearmScanner,
        apiFactory = { FakeVitalForgeApi() },
    )

    /**
     * Production hands the same [com.ventouxlabs.bascule.data.EncryptedScaleProfileStore]
     * in as both collaborators ([com.ventouxlabs.bascule.BasculeApplication] `consentStore`
     * delegates to `scaleProfileStore`), so a test that supplies only one of them
     * exercises a wiring that never ships.
     */
    private fun viewModelWithRegistry(
        registry: FakeScaleProfileStore,
        configStore: FakeConfigStore = FakeConfigStore(),
        sessionCookieStore: FakeSessionCookieStore = FakeSessionCookieStore(),
        rearmScanner: (suspend () -> Unit)? = null,
        deliveryTrigger: DeliveryTrigger = FakeDeliveryTrigger(),
        dao: FakeReadingDao = FakeReadingDao(),
        authTokenStore: AuthTokenStore = FakeAuthTokenStore(),
    ) = viewModel(
        configStore = configStore,
        consentStore = registry,
        sessionCookieStore = sessionCookieStore,
        scaleProfileStore = registry,
        rearmScanner = rearmScanner,
        deliveryTrigger = deliveryTrigger,
        dao = dao,
        authTokenStore = authTokenStore,
    )

    private fun profile(
        id: String,
        address: String = "E7:DB:51:F1:36:91",
        scaleIndex: Int = 2,
        consentCode: Int = 1234,
        active: Boolean = true,
    ) = ScaleProfile(
        id = id,
        deviceAddress = address,
        scaleIndex = scaleIndex,
        consentCode = consentCode,
        label = "Profile $scaleIndex",
        registeredAtMillis = 1_000L,
        active = active,
    )

    // --- C1: the profile-registry export/import path, with the store production actually wires in.

    @Test
    fun settingsExportRoundTripsTheProfileRegistryThroughEncryption() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("bf720-slot-2")))
        val configStore = FakeConfigStore(
            initialBaseUrl = "https://weight.grepon.cc",
            initialPairedDeviceAddress = "E7:DB:51:F1:36:91",
        )
        val vm = viewModelWithRegistry(registry, configStore)
        advanceUntilIdle()

        val bytes = vm.exportSettings("correct horse battery staple").getOrThrow()
        val restored = SettingsBackupCodec.decrypt(bytes, "correct horse battery staple")

        assertEquals(listOf(profile("bf720-slot-2")), restored.profiles)
        assertTrue("E7:DB:51:F1:36:91" !in bytes.decodeToString())
    }

    @Test
    fun importingABackupWithProfilesReplacesTheRegistryRatherThanTakingTheLegacyBranch() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("stale", scaleIndex = 5, consentCode = 999)))
        val configStore = FakeConfigStore(initialPairedDeviceAddress = "E7:DB:51:F1:36:91")
        val vm = viewModelWithRegistry(registry, configStore)
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings(profiles = listOf(profile("imported"))),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(listOf(profile("imported")), registry.profiles.value)
    }

    /**
     * TS-H5 / M12 end to end: a backup that carries no profile registry entries
     * is not evidence that this device has none. Deleting on that basis destroys
     * consent codes that can only be recovered by physically re-registering with
     * the scale, burning one of its eight slots.
     */
    @Test
    fun importingAZeroProfileBackupDoesNotWipeAnExistingRegistration() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("existing")))
        val configStore = FakeConfigStore(initialPairedDeviceAddress = "E7:DB:51:F1:36:91")
        val vm = viewModelWithRegistry(registry, configStore)
        val bytes = SettingsBackupCodec.encrypt(backupSettings(), "correct horse battery staple")

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(listOf(profile("existing")), registry.profiles.value)
        assertEquals(ScaleCredential(2, 1234), registry.credentialFor("E7:DB:51:F1:36:91"))
    }

    @Test
    fun importingABackupWhoseProfilesAreAllInactiveIsRejectedBeforeAnythingIsWritten() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("existing")))
        val configStore = FakeConfigStore(initialBaseUrl = "https://original.example.com")
        val vm = viewModelWithRegistry(registry, configStore)
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings(profiles = listOf(profile("imported", active = false))),
            "correct horse battery staple",
        )

        val result = vm.importSettings(bytes, "correct horse battery staple")
        advanceUntilIdle()

        assertTrue("a registry that can never arm must not import silently", result.isFailure)
        assertEquals(
            "the check must precede the first write, so no setting is left half-applied",
            "https://original.example.com",
            configStore.baseUrl.value,
        )
        assertEquals(listOf(profile("existing")), registry.profiles.value)
    }

    /**
     * `replaceAll` refuses duplicate ids rather than deduping — dedup would drop
     * whichever copy came second, active flag and all. That refusal has to be
     * front-loaded here or it throws after the URL and unit are already written.
     */
    @Test
    fun importingABackupWithDuplicateProfileIdsIsRejectedBeforeAnythingIsWritten() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("existing")))
        val configStore = FakeConfigStore(initialBaseUrl = "https://original.example.com")
        val vm = viewModelWithRegistry(registry, configStore)
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings(
                profiles = listOf(
                    profile("same", scaleIndex = 1, active = false),
                    profile("same", scaleIndex = 2),
                ),
            ),
            "correct horse battery staple",
        )

        val result = vm.importSettings(bytes, "correct horse battery staple")
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertEquals("https://original.example.com", configStore.baseUrl.value)
        assertEquals(listOf(profile("existing")), registry.profiles.value)
    }

    // --- M10: the screen cannot re-derive the host comparison after the import overwrote the URL.

    @Test
    fun aSameHostImportReportsThatTheCredentialWasApplied() = runTest {
        val configStore = FakeConfigStore(initialBaseUrl = "https://mine.example.com")
        val vm = viewModelWithRegistry(FakeScaleProfileStore(), configStore)
        val bytes = SettingsBackupCodec.encrypt(backupSettings(), "correct horse battery staple")

        val outcome = vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(ImportOutcome.APPLIED, outcome)
    }

    @Test
    fun aHostChangingImportReportsThatNoCredentialWasInstalled() = runTest {
        val configStore = FakeConfigStore(initialBaseUrl = "https://original.example.com")
        val vm = viewModelWithRegistry(FakeScaleProfileStore(), configStore)
        val bytes = SettingsBackupCodec.encrypt(backupSettings(), "correct horse battery staple")

        val outcome = vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(
            "reporting a plain success here would tell the user they are signed in when they are not",
            ImportOutcome.APPLIED_WITHOUT_CREDENTIAL_AFTER_HOST_CHANGE,
            outcome,
        )
    }

    /**
     * The import path reads the same list the Settings selector offers, so a
     * backup pinned to v2 restores v2 now that v2 is offered — the gate that
     * used to withhold it (M11) is the same gate, just no longer withholding
     * anything. The rest of the backup applies alongside it.
     */
    @Test
    fun importingABackupPinnedToV2RestoresV2AndAppliesTheRest() = runTest {
        val configStore = FakeConfigStore(initialBaseUrl = "https://original.example.com")
        val vm = viewModelWithRegistry(FakeScaleProfileStore(), configStore)
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings().copy(
                contractVersion = ContractVersion.V2_BODY_COMP,
                displayUnit = WeightUnit.POUNDS,
            ),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(
            "a contract the screen offers must be reachable by import too",
            ContractVersion.V2_BODY_COMP,
            configStore.contractVersion.value,
        )
        assertEquals(
            "skipping one field must not turn into refusing the whole restore",
            WeightUnit.POUNDS,
            configStore.displayUnit.value,
        )
        assertEquals("https://mine.example.com", configStore.baseUrl.value)
    }

    /**
     * Codex review, v2-body-composition PR: a same-host restore that changes
     * the contract used to write straight to `ConfigStore`, bypassing the
     * recovery `saveContractVersion` gives a manual toggle — a row a v2
     * server rejected would have stayed `FAILED_PERMANENT` forever even
     * though the very backup being restored switches back to v1.
     */
    @Test
    fun importingABackupThatChangesTheContractRequeuesRowsRejectedUnderTheOldOne() = runTest {
        val dao = FakeReadingDao()
        val trigger = FakeDeliveryTrigger()
        dao.insert(
            readingFixture().copy(
                id = "rejected-under-v2",
                status = ReadingStatus.FAILED_PERMANENT,
                contractVersionAtDelivery = ContractVersion.V2_BODY_COMP.wire,
                permanentRejectionHttpCode = 422,
            ),
        )
        val configStore = FakeConfigStore(initialContractVersion = ContractVersion.V2_BODY_COMP)
        val vm = viewModelWithRegistry(FakeScaleProfileStore(), configStore, dao = dao, deliveryTrigger = trigger)
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings().copy(contractVersion = ContractVersion.V1_WEIGHT_ONLY),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(ContractVersion.V1_WEIGHT_ONLY, configStore.contractVersion.value)
        assertEquals(
            ReadingStatus.PENDING,
            dao.rows.value.single { it.id == "rejected-under-v2" }.status,
        )
        assertEquals(1, trigger.triggerCount)
    }

    /**
     * Codex review, v2-body-composition PR — a real security regression: the
     * requeue fix above turns FAILED_PERMANENT rows back into PENDING and
     * triggers an immediate drain. On a host change, `blockAllPendingForAuth`
     * already parks the backlog specifically so nothing reaches the *new*
     * host until the user takes an explicit Login/Save-token action — the
     * recovery must not undo that by resurrecting rows straight into a drain
     * against the host that was just switched to.
     */
    @Test
    fun importingABackupThatChangesBothHostAndContractDoesNotRequeueOnAHostChange() = runTest {
        val dao = FakeReadingDao()
        val trigger = FakeDeliveryTrigger()
        dao.insert(
            readingFixture().copy(
                id = "rejected-under-v2",
                status = ReadingStatus.FAILED_PERMANENT,
                contractVersionAtDelivery = ContractVersion.V2_BODY_COMP.wire,
                permanentRejectionHttpCode = 422,
            ),
        )
        val configStore = FakeConfigStore(
            initialBaseUrl = "https://original.example.com",
            initialContractVersion = ContractVersion.V2_BODY_COMP,
        )
        val vm = viewModelWithRegistry(FakeScaleProfileStore(), configStore, dao = dao, deliveryTrigger = trigger)
        val bytes = SettingsBackupCodec.encrypt(
            // backupSettings()'s baseUrl (mine.example.com) differs from the
            // configured host above — a real host change, not the same-host
            // restore the other test covers.
            backupSettings().copy(contractVersion = ContractVersion.V1_WEIGHT_ONLY),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(ContractVersion.V1_WEIGHT_ONLY, configStore.contractVersion.value)
        assertEquals(
            "a host change must leave a permanently-rejected row exactly where it was, not resubmit it " +
                "to the new host — blockAllPendingForAuth only ever touches PENDING rows, so this recovery " +
                "path is the only thing that could otherwise reach it",
            ReadingStatus.FAILED_PERMANENT,
            dao.rows.value.single { it.id == "rejected-under-v2" }.status,
        )
        assertEquals(
            "no drain must fire for a host change — that is exactly what blockAllPendingForAuth exists to prevent",
            0,
            trigger.triggerCount,
        )
    }

    /**
     * Codex review, v2-body-composition PR, third pass: the requeue+drain for
     * a same-host, contract-changing restore used to run *before*
     * `applyImportedProfilesAndCredential`. `deliveryTrigger.triggerImmediateDrain()`
     * schedules a WorkManager run rather than draining inline, so that ordering
     * let the scheduled drain reach the network under whatever credential was
     * still installed at that moment — a stale one, or another user's session
     * — instead of the one this same backup is in the middle of installing.
     * Pinned by recording the order two collaborators are touched in, since
     * `FakeDeliveryTrigger`'s plain counter cannot see *when* relative to the
     * credential write.
     */
    @Test
    fun theContractRecoveryDrainIsTriggeredAfterTheImportedCredentialIsApplied() = runTest {
        val order = mutableListOf<String>()
        val dao = FakeReadingDao()
        dao.insert(
            readingFixture().copy(
                id = "rejected-under-v2",
                status = ReadingStatus.FAILED_PERMANENT,
                contractVersionAtDelivery = ContractVersion.V2_BODY_COMP.wire,
                permanentRejectionHttpCode = 422,
            ),
        )
        val orderTrackingAuthTokenStore = object : AuthTokenStore {
            private val delegate = FakeAuthTokenStore()
            override fun isSet() = delegate.isSet()
            override fun token() = delegate.token()
            override fun save(token: String) {
                order += "credential-applied"
                delegate.save(token)
            }
            override fun clear() = delegate.clear()
        }
        val orderTrackingTrigger = object : DeliveryTrigger {
            override fun triggerImmediateDrain() {
                order += "drain-triggered"
            }
        }
        val configStore = FakeConfigStore(initialContractVersion = ContractVersion.V2_BODY_COMP)
        val vm = viewModelWithRegistry(
            FakeScaleProfileStore(),
            configStore,
            dao = dao,
            deliveryTrigger = orderTrackingTrigger,
            authTokenStore = orderTrackingAuthTokenStore,
        )
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings().copy(
                contractVersion = ContractVersion.V1_WEIGHT_ONLY,
                credentialType = BackupCredentialType.TOKEN,
                credentialValue = "backup-token",
            ),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        // Two drains actually fire here — unblockAuthRowsAndDrain's own, plus
        // the contract recovery's — and both are correct to fire once the
        // credential is in place. The invariant under test is narrower: no
        // drain may have already run when the credential was applied.
        assertEquals("credential-applied", order.first())
        assertTrue("both drains must follow the credential write", order.drop(1).all { it == "drain-triggered" })
    }

    // --- M12: the scan registration reflects what the screen just changed.

    @Test
    fun importingSettingsReArmsTheScanner() = runTest {
        var rearmCount = 0
        val registry = FakeScaleProfileStore()
        val vm = viewModelWithRegistry(registry, rearmScanner = { rearmCount += 1 })
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings(profiles = listOf(profile("imported"))),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(1, rearmCount)
    }

    // --- M1: a linked scale that is not the active profile is never captured from.

    @Test
    fun linkingASecondScaleMakesItTheActiveProfileRatherThanReportingAnInertSuccess() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("first", address = "AA:BB:CC:DD:EE:FF", scaleIndex = 1)))
        var rearmCount = 0
        val vm = viewModelWithRegistry(registry, rearmScanner = { rearmCount += 1 })
        advanceUntilIdle()

        vm.linkExistingScale("e7:db:51:f1:36:91", "2", "1234")
        advanceUntilIdle()

        val active = registry.activeProfile.value
        assertEquals("E7:DB:51:F1:36:91", active?.deviceAddress)
        assertEquals(2, active?.scaleIndex)
        assertEquals("linking must re-arm the scan onto the newly active address", 1, rearmCount)
    }

    private fun backupSettings(profiles: List<ScaleProfile> = emptyList()) = PortableSettings(
        baseUrl = "https://mine.example.com",
        displayUnit = WeightUnit.KILOGRAMS,
        contractVersion = ContractVersion.V1_WEIGHT_ONLY,
        alwaysOnBridging = false,
        credentialType = BackupCredentialType.NONE,
        credentialValue = null,
        pairedDeviceAddress = "E7:DB:51:F1:36:91",
        scaleCredential = null,
        profiles = profiles,
    )
    @Test
    fun choosingAContractVersionPersistsIt() = runTest {
        val configStore = FakeConfigStore()
        val vm = viewModel(configStore = configStore)

        vm.saveContractVersion(ContractVersion.V2_BODY_COMP)
        advanceUntilIdle()

        assertEquals(ContractVersion.V2_BODY_COMP, configStore.contractVersion.value)
    }

    /**
     * Devil's-advocate finding on the v2 PR: a 422 is classified permanent, so
     * a reading rejected because the *server* did not yet speak the chosen
     * contract was gone for good even after the user switched back. Switching
     * contracts is the moment such rows earn a fresh attempt — and only such
     * rows: one rejected under the contract now chosen was rejected on its
     * own merits.
     */
    /** One [FAILED_PERMANENT][ReadingStatus.FAILED_PERMANENT] row per case this test distinguishes. */
    private suspend fun seedFailedPermanentRows(dao: FakeReadingDao) {
        dao.insert(
            readingFixture().copy(
                id = "rejected-under-v2",
                status = ReadingStatus.FAILED_PERMANENT,
                contractVersionAtDelivery = ContractVersion.V2_BODY_COMP.wire,
                permanentRejectionHttpCode = 422,
            ),
        )
        dao.insert(
            readingFixture().copy(
                id = "rejected-under-v1",
                status = ReadingStatus.FAILED_PERMANENT,
                contractVersionAtDelivery = ContractVersion.V1_WEIGHT_ONLY.wire,
                permanentRejectionHttpCode = 422,
            ),
        )
        dao.insert(
            readingFixture().copy(
                id = "never-sent",
                status = ReadingStatus.FAILED_PERMANENT,
                contractVersionAtDelivery = null,
            ),
        )
        dao.insert(
            readingFixture().copy(
                id = "rejected-not-for-contract-reasons",
                status = ReadingStatus.FAILED_PERMANENT,
                contractVersionAtDelivery = ContractVersion.V2_BODY_COMP.wire,
                permanentRejectionHttpCode = 404,
            ),
        )
    }

    @Test
    fun switchingContractsRequeuesRowsRejectedUnderTheOtherOneOnly() = runTest {
        val dao = FakeReadingDao()
        val trigger = FakeDeliveryTrigger()
        seedFailedPermanentRows(dao)
        val vm = viewModel(dao = dao, deliveryTrigger = trigger)

        vm.saveContractVersion(ContractVersion.V1_WEIGHT_ONLY)
        advanceUntilIdle()

        val byId = dao.rows.value.associateBy { it.id }
        assertEquals(
            "rejected by the other contract: retried",
            ReadingStatus.PENDING,
            byId.getValue("rejected-under-v2").status,
        )
        assertEquals(
            "rejected by this contract: stays failed",
            ReadingStatus.FAILED_PERMANENT,
            byId.getValue("rejected-under-v1").status,
        )
        assertEquals(
            "never reached a server: untouched",
            ReadingStatus.FAILED_PERMANENT,
            byId.getValue("never-sent").status,
        )
        assertEquals(
            "rejected for a reason a contract switch cannot fix: untouched",
            ReadingStatus.FAILED_PERMANENT,
            byId.getValue("rejected-not-for-contract-reasons").status,
        )
        assertEquals("a requeue is followed by a drain", 1, trigger.triggerCount)
    }

    /** No stranded rows, no drain — switching must not poke the network for nothing. */
    @Test
    fun switchingContractsWithNothingStrandedDoesNotDrain() = runTest {
        val trigger = FakeDeliveryTrigger()
        val vm = viewModel(deliveryTrigger = trigger)

        vm.saveContractVersion(ContractVersion.V2_BODY_COMP)
        advanceUntilIdle()

        assertEquals(0, trigger.triggerCount)
    }

}
