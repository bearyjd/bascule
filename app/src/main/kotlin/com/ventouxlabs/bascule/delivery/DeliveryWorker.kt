package com.ventouxlabs.bascule.delivery

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ReadingDao

/**
 * Drains PENDING rows. Runs independently of the session process so a killed
 * service never strands a captured reading (00-design.md §8.1). Thin
 * WorkManager adapter over [DeliveryDrainer], which owns the actual logic.
 */
class DeliveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BasculeApplication
        val dao = app.database.readingDao()
        val runtime = app.runtimeApiFactory.create()
        val outcome = DeliveryDrainer(dao, runtime, log = { Log.i(TAG, it) }).drain()
        scheduleRetryIfNeeded(outcome, dao, app.deliveryScheduler, System.currentTimeMillis())
        return resultFor(outcome)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "delivery-drain"

        private const val TAG = "DeliveryDrainer"

        /**
         * Every outcome succeeds. [DrainOutcome.MORE_PAGES] re-enqueues instead
         * of retrying, so pagination runs at full speed; [DrainOutcome.FAILED]
         * schedules a delayed kick instead of retrying, because a
         * `Result.retry()` is a delayed request under [UNIQUE_WORK_NAME] — in
         * WorkManager's own backoff, 30 s doubling to a 5-hour cap — and a
         * delayed request under the drain name is exactly what
         * `triggerImmediateDrain`'s `KEEP` collides with: every trigger is
         * dropped until it elapses. The rows' §3.4 ladder is the only retry
         * pacing; see [retryKickDelayFor].
         *
         * Pure, and separated from [doWork] because `doWork` casts its context
         * to `BasculeApplication`, which is not constructible in this project's
         * JUnit lane — the same split as
         * [com.ventouxlabs.bascule.ble.session.WorkManagerScaleSessionEnqueuer].
         */
        fun resultFor(outcome: DrainOutcome): Result = when (outcome) {
            DrainOutcome.DONE, DrainOutcome.MORE_PAGES, DrainOutcome.FAILED -> Result.success()
        }

        /**
         * How long the retry kick after this drain should wait, or null for no
         * kick: the drain did not fail, or nothing is due and nothing is waiting.
         *
         * A waiting row times the kick to the moment the row ladder says its
         * next attempt may happen — floored at zero, because the clock keeps
         * running between the DAO's query and this call. A row that is already
         * *due* times it to the ladder's base instead: a `Retry-After` of zero
         * parks its row at `now` and leaves the rest of the batch untouched, so
         * nothing is in the future for the DAO to find, and without this floor
         * those rows would sit until the 15-minute periodic net. The base is
         * also what keeps a server answering `429 Retry-After: 0` forever from
         * turning the kick into a hot loop: one drain per base, never tighter.
         * A waiting row wins over the base, not the other way round: after a
         * mid-batch rate limit the rows behind the parked one are still due,
         * and kicking them at the base would send the next one straight into
         * the same limiter every 30 s until the server's deadline — the exact
         * thing `BackOffDrain` ends the pass to avoid. So the base is only the
         * answer when nothing at all is waiting.
         *
         * Pure for the same reason as [resultFor].
         */
        fun retryKickDelayFor(
            outcome: DrainOutcome,
            earliestFutureAttemptMillis: Long?,
            anyRowDue: Boolean,
            nowMillis: Long,
        ): Long? {
            if (outcome != DrainOutcome.FAILED) return null
            return earliestFutureAttemptMillis?.let { (it - nowMillis).coerceAtLeast(0) }
                ?: DeliveryCoordinator.BACKOFF_BASE_MILLIS.takeIf { anyRowDue }
        }

        /**
         * The post-drain step: what the drain's outcome asks of the scheduler.
         * Separated from [doWork] so it can run under the fakes, for the reason
         * given on [resultFor]. A failed drain reads the rows once each way —
         * the soonest waiting row, and whether any row is due — and turns them
         * into one kick through [retryKickDelayFor]; a full page queues its
         * continuation; a finished drain asks for nothing.
         */
        suspend fun scheduleRetryIfNeeded(
            outcome: DrainOutcome,
            dao: ReadingDao,
            scheduler: DeliveryScheduler,
            nowMillis: Long,
        ) {
            when (outcome) {
                DrainOutcome.DONE -> Unit
                DrainOutcome.MORE_PAGES -> scheduler.enqueueContinuation()
                DrainOutcome.FAILED -> {
                    val delay = retryKickDelayFor(
                        outcome = outcome,
                        earliestFutureAttemptMillis = dao.earliestFutureAttemptMillis(nowMillis),
                        anyRowDue = dao.pending(nowMillis, limit = 1).isNotEmpty(),
                        nowMillis = nowMillis,
                    )
                    if (delay != null) scheduler.scheduleRetryKick(delay)
                }
            }
        }
    }
}
