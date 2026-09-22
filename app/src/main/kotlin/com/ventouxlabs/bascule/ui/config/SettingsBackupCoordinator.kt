package com.ventouxlabs.bascule.ui.config

import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.data.BackupCredentialType
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.PortableSettings
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ScaleProfileStore
import com.ventouxlabs.bascule.data.SettingsBackupCodec
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.network.AuthTokenStore
import com.ventouxlabs.bascule.network.SessionCookieStore
import com.ventouxlabs.bascule.ui.ImportOutcome
import com.ventouxlabs.bascule.ui.config.BaseUrls.hostOf
import com.ventouxlabs.bascule.ui.config.BaseUrls.validateBaseUrl
import com.ventouxlabs.bascule.ui.selectableContractVersions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Encrypted settings export and import — the data side of the Settings
 * screen's backup feature, split out of
 * [com.ventouxlabs.bascule.ui.ConfigViewModel], which keeps the two public
 * entry points as delegations and applies its own UI-refresh bumps once
 * [importSettings] has returned.
 *
 * Everything the security reviews sequenced lives here, in the order they
 * pinned: on a host change the backlog is parked behind `BLOCKED_AUTH` before
 * anything is written; the credential is applied before any drain can run;
 * and both the unblock-and-drain and the contract-rejection requeue are gated
 * on the import keeping the same host.
 *
 * [unblockAuthRowsAndDrain] is the ViewModel's own — `saveToken` and `login`
 * share it, so it stays there and is handed in rather than duplicated.
 */
internal class SettingsBackupCoordinator(
    private val configStore: ConfigStore,
    private val authTokenStore: AuthTokenStore,
    private val sessionCookieStore: SessionCookieStore,
    private val consentStore: ConsentStore,
    private val scaleProfileStore: ScaleProfileStore?,
    private val dao: ReadingDao,
    private val deliveryTrigger: DeliveryTrigger,
    private val nowMillis: () -> Long,
    private val rearmScanner: (suspend () -> Unit)?,
    private val ioDispatcher: CoroutineDispatcher,
    private val unblockAuthRowsAndDrain: suspend () -> Unit,
) {

    suspend fun exportSettings(passphrase: String): Result<ByteArray> = runCatching {
        withContext(ioDispatcher) {
            val pairedAddress = configStore.pairedDeviceAddress.first()
            val token = authTokenStore.token()
            val session = sessionCookieStore.cookie()
            val credentialType = when {
                token != null -> BackupCredentialType.TOKEN
                session != null -> BackupCredentialType.SESSION
                else -> BackupCredentialType.NONE
            }
            SettingsBackupCodec.encrypt(
                PortableSettings(
                    baseUrl = configStore.baseUrl.first().orEmpty(),
                    displayUnit = configStore.displayUnit.first(),
                    contractVersion = configStore.contractVersion.first(),
                    alwaysOnBridging = configStore.alwaysOnBridging.first(),
                    credentialType = credentialType,
                    credentialValue = token ?: session,
                    pairedDeviceAddress = pairedAddress,
                    scaleCredential = pairedAddress?.let(consentStore::credentialFor),
                    profiles = scaleProfileStore?.profiles?.value.orEmpty(),
                    automaticCaptureEnabled = configStore.automaticCaptureEnabled.first(),
                ),
                passphrase,
            )
        }
    }

    /**
     * A backup file sets both *which server* and *which credential*, and the two
     * are consistent with each other, so a swapped pair produces no auth error
     * the user would notice. Draining the backlog straight after the swap would
     * POST every stored reading — weight and, under the V2 contract, the full
     * body-composition set — to a host the user never chose. So the immediate
     * drain fires only when the imported URL keeps the same host as the one
     * already configured; against a new host the rows stay `BLOCKED_AUTH` until
     * the user does something deliberate
     * ([com.ventouxlabs.bascule.ui.ConfigViewModel.saveToken] or
     * [com.ventouxlabs.bascule.ui.ConfigViewModel.login], both of which
     * unblock and drain on their own).
     */
    suspend fun importSettings(bytes: ByteArray, passphrase: String): Result<ImportOutcome> = runCatching {
        withContext(ioDispatcher) {
            val imported = SettingsBackupCodec.decrypt(bytes, passphrase)
            require(imported.baseUrl.isBlank() || validateBaseUrl(imported.baseUrl) == null) {
                "Backup contains an invalid server URL"
            }
            // Checked before the first write: a registry with no active profile
            // arms nothing, so importing one would leave capture inert with
            // nothing said about it. Aborting here keeps the import atomic.
            require(imported.profiles.isEmpty() || imported.profiles.any { it.active }) {
                "Backup has profiles but none of them is active"
            }
            // Front-loaded for the same reason: replaceAll refuses duplicate
            // ids, and letting it throw would leave the URL, unit, and
            // contract version already written.
            require(imported.profiles.distinctBy { it.id }.size == imported.profiles.size) {
                "Backup contains duplicate profile ids"
            }
            val previousAddress = configStore.pairedDeviceAddress.first()
            val currentHost = hostOf(configStore.baseUrl.first())
            // No host configured yet means there is nothing to silently redirect
            // *away from* — this is a fresh setup or a first restore, the most
            // common legitimate use of this feature, and must not be penalized
            // with the same friction a real host change gets. Once a real host
            // IS on record, a blank or unparseable imported one must never
            // compare equal to it (an unparseable host is not "no change").
            // See pr-1-review-security.md HIGH-1 / MEDIUM-2.
            val keepsSameHost = currentHost == null || currentHost == hostOf(imported.baseUrl)
            // A backup pointing at an unfamiliar host is the one case this
            // import flow cannot tell apart from a hostile one that silently
            // repoints the app: the same-host check only ever gated the one
            // unblockAuthRowsAndDrain() call below, but the PENDING backlog and
            // every future capture were never gated by anything — a periodic
            // drain re-reads the base URL and credential fresh on every run, so
            // both would have reached the new host with zero user interaction.
            // On a host change: park the existing backlog behind BLOCKED_AUTH
            // (the same status a real auth rejection uses) and do NOT install
            // the backup's own credential automatically — the user has to
            // notice they're signed out and take an explicit Login/Save-token
            // action before anything drains to the new host again.
            if (!keepsSameHost) dao.blockAllPendingForAuth()
            // Never overwrite a real URL with a blank one — a legacy or
            // malformed backup carrying an empty base_url would otherwise wipe
            // a working configuration for no benefit to the user.
            if (imported.baseUrl.isNotBlank()) configStore.saveBaseUrl(imported.baseUrl)
            configStore.saveDisplayUnit(imported.displayUnit)
            // Same list the Settings selector offers, so a version withheld
            // there is withheld here too. The existing value is kept rather than
            // forced to a default — this skips one field, it does not half-apply
            // the import.
            //
            // Codex review, v2-body-composition PR: a same-host restore that
            // changes the contract must recover rows rejected under the
            // contract being switched away from, exactly like a manual toggle
            // does — requeueRowsRejectedUnderOtherContract is shared with
            // saveContractVersion for exactly this. The requeue+drain itself
            // is deferred past applyImportedProfilesAndCredential below (a
            // second review pass caught it running before that point, which
            // let a WorkManager drain reach the network under the *previous*
            // credential — a stale or another user's session — instead of
            // the one the backup installs, or none at all after a host
            // change). Also gated on keepsSameHost for the same reason
            // unblockAuthRowsAndDrain is: on a host change, resurrecting rows
            // straight into a drain is the exact bypass blockAllPendingForAuth
            // above exists to prevent.
            val shouldRecoverContractRejections = imported.contractVersion in selectableContractVersions &&
                keepsSameHost
            if (imported.contractVersion in selectableContractVersions) {
                configStore.saveContractVersion(imported.contractVersion)
            }
            configStore.saveAlwaysOnBridging(imported.alwaysOnBridging)
            configStore.saveAutomaticCaptureEnabled(imported.automaticCaptureEnabled)
            configStore.savePairedDeviceAddress(imported.pairedDeviceAddress)
            applyImportedProfilesAndCredential(imported, previousAddress, keepsSameHost)
            rearmScanner?.invoke()
            if (imported.credentialType != BackupCredentialType.NONE && keepsSameHost) {
                unblockAuthRowsAndDrain()
            }
            if (shouldRecoverContractRejections) {
                requeueRowsRejectedUnderOtherContract(dao, deliveryTrigger, imported.contractVersion, nowMillis)
            }
            if (keepsSameHost) {
                ImportOutcome.APPLIED
            } else {
                ImportOutcome.APPLIED_WITHOUT_CREDENTIAL_AFTER_HOST_CHANGE
            }
        }
    }

    /**
     * The two writes `importSettings` gated on separate conditions (profiles on
     * their own contents, the credential on [keepsSameHost]) — combined here
     * only to keep `importSettings` itself under this file's complexity
     * threshold; the two halves remain independent of each other.
     */
    private suspend fun applyImportedProfilesAndCredential(
        imported: PortableSettings,
        previousAddress: String?,
        keepsSameHost: Boolean,
    ) {
        if (imported.profiles.isNotEmpty() && scaleProfileStore != null) {
            scaleProfileStore.replaceAll(imported.profiles)
        } else {
            // Only a pre-registry backup is evidence the device had no
            // profiles. A registry-era backup that carried none says nothing
            // about this device's, and clearing on that basis deletes consent
            // codes that can only be recovered by re-registering with the scale.
            if (previousAddress != null && !imported.supportsProfiles) consentStore.clear(previousAddress)
            imported.pairedDeviceAddress?.let { address ->
                imported.scaleCredential?.let { consentStore.save(address, it) }
            }
        }
        // Cleared before a same-host import is trusted with a new value: any
        // throw between here and the end of this function must never leave a
        // real, working credential pointed at whatever host this import is
        // still in the middle of configuring. On a host change, deliberately
        // left cleared — see importSettings's host-change handling.
        authTokenStore.clear()
        sessionCookieStore.clear()
        if (keepsSameHost) {
            when (imported.credentialType) {
                BackupCredentialType.NONE -> Unit
                BackupCredentialType.TOKEN -> authTokenStore.save(requireNotNull(imported.credentialValue))
                BackupCredentialType.SESSION -> sessionCookieStore.save(requireNotNull(imported.credentialValue))
            }
        }
    }
}
