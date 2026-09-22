package com.ventouxlabs.bascule.ui.config

import android.util.Log
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.network.ContractVersion

private const val CONFIG_VIEW_MODEL_TAG = "ConfigViewModel"

/**
 * Rows the *previous* contract's server rejected are a different matter from
 * rows this one rejects: a 422 is a statement about the contract, not the
 * reading, so a switch — however it happens — is the moment such a row earns
 * a fresh attempt. Shared by
 * [com.ventouxlabs.bascule.ui.ConfigViewModel.saveContractVersion] and
 * [SettingsBackupCoordinator.importSettings], which both change the stored
 * contract and both owe this recovery. Top-level rather than a member: it
 * needs nothing from `ConfigViewModel` but its two collaborators, and the
 * class was already at that file's function-count ceiling. Best-effort — the
 * config write is the setting, and the requeue must never be the reason it
 * did not stick.
 */
internal suspend fun requeueRowsRejectedUnderOtherContract(
    dao: ReadingDao,
    deliveryTrigger: DeliveryTrigger,
    version: ContractVersion,
    nowMillis: () -> Long,
) {
    runCatching {
        val stranded = dao.failedPermanentlyUnderOtherContract(version.wire)
        if (stranded.isNotEmpty()) {
            dao.requeueForReplay(stranded, nowMillis())
            deliveryTrigger.triggerImmediateDrain()
        }
    }.onFailure { Log.w(CONFIG_VIEW_MODEL_TAG, "could not requeue rows rejected under the previous contract", it) }
}
