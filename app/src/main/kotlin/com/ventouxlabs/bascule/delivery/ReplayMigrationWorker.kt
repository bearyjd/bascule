package com.ventouxlabs.bascule.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.network.ContractVersion
import kotlinx.coroutines.flow.first

/**
 * One-shot backfill for WP-22 (`01-plan.md`): re-queues already-`SENT` rows
 * as `PENDING` when a contract-version upgrade means fields they hold but
 * never delivered (per [ReplayEligibility]) could now reach VitalForge.
 *
 * **Not wired into any scheduling path in this build.** `00-design.md` §4.4's
 * A6 escalation — whether VitalForge is idempotent enough for a delayed
 * replay to be safe — is resolved (`vitalforge` PR #39: `client_id` +
 * `captured_at`). What is written and tested here is correct and safe to
 * run. What is *not* yet decided is operational: a residual gap survives A6
 * — a row whose *original* delivery was itself delayed past the dedup
 * window has no reliable capture-time proxy for a replay to match against,
 * so enabling this against a real backlog needs a human decision about
 * scope, not just a passing test suite (`01-plan.md`'s WP-22 section).
 * Nothing in this app currently calls [WorkManagerDeliveryScheduler] (or any
 * other scheduler) for this worker — that wiring, and the decision behind
 * it, is deliberately left for whoever makes that call.
 */
class ReplayMigrationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BasculeApplication
        val configStore = app.configStore
        val contract = configStore.contractVersion.first()
        val lastReplayedFor = configStore.lastReplayMigrationContractVersion.first()

        if (!shouldRun(contract, lastReplayedFor)) return Result.success()

        val dao = app.database.readingDao()
        val eligibleIds = eligibleRowIds(dao.sent(), contract)
        if (eligibleIds.isNotEmpty()) {
            dao.requeueForReplay(eligibleIds, System.currentTimeMillis())
        }
        configStore.saveLastReplayMigrationContractVersion(contract)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "replay-migration"

        /**
         * Pure, and separated from [doWork] for the same reason
         * [DeliveryWorker.resultFor] is: `BasculeApplication` is not
         * constructible in this project's JUnit lane, so the worker-shell
         * plumbing and the actual decision logic are tested separately.
         *
         * `V1_WEIGHT_ONLY` has nothing to backfill — its own contract
         * supports only `WEIGHT`, which every row already delivers by
         * definition — so there is no upgrade to react to yet. A real
         * early-out, not a leftover disabled flag from before A6.
         */
        fun shouldRun(contract: ContractVersion, lastReplayedFor: ContractVersion?): Boolean {
            if (contract == ContractVersion.V1_WEIGHT_ONLY) return false
            return contract != lastReplayedFor
        }

        fun eligibleRowIds(rows: List<ReadingEntity>, contract: ContractVersion): List<String> =
            rows.filter { ReplayEligibility.isEligible(it, contract) }.map { it.id }
    }
}
