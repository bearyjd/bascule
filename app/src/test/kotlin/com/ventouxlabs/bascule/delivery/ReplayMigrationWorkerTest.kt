package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.network.ContractVersion
import com.ventouxlabs.bascule.network.ReadingField
import com.ventouxlabs.bascule.ui.fake.readingFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-22 (`01-plan.md`)'s scheduling/decision logic — [ReplayMigrationWorker.shouldRun]
 * and [ReplayMigrationWorker.eligibleRowIds] — kept as plain JUnit, deliberately
 * split from [doWork], for the same reason [DeliveryWorkerResultTest] is:
 * `BasculeApplication` is not constructible in this project's JUnit lane
 * (`applicationContext as BasculeApplication` inside `doWork`), so only the
 * pure decision functions are unit-testable here. The DAO-SQL side of a
 * replay pass (`requeueForReplay`'s actual reset) is covered in
 * [com.ventouxlabs.bascule.data.ReadingDaoSqlTest] instead, against real
 * Room, not a Robolectric-wrapped worker.
 */
class ReplayMigrationWorkerTest {

    @Test
    fun isDisabledUnderContractV1() {
        // V1 supports only WEIGHT, which every row already delivers by
        // definition -- there is nothing an upgrade-from-V1 pass could ever
        // backfill, so this is a real early-out, not a placeholder.
        assertFalse(ReplayMigrationWorker.shouldRun(ContractVersion.V1_WEIGHT_ONLY, lastReplayedFor = null))
        assertFalse(
            ReplayMigrationWorker.shouldRun(
                ContractVersion.V1_WEIGHT_ONLY,
                lastReplayedFor = ContractVersion.V2_BODY_COMP,
            ),
        )
    }

    @Test
    fun runsAtMostOncePerContractVersionChange() {
        // Never run for this contract yet -- due.
        assertTrue(ReplayMigrationWorker.shouldRun(ContractVersion.V2_BODY_COMP, lastReplayedFor = null))
        // Already ran for this exact contract -- not due again.
        assertFalse(
            ReplayMigrationWorker.shouldRun(
                ContractVersion.V2_BODY_COMP,
                lastReplayedFor = ContractVersion.V2_BODY_COMP,
            ),
        )
    }

    /**
     * Supersedes the plan's originally-named `isDisabledPendingIdempotencyEscalation`
     * (`01-plan.md`'s WP-22 section) now that A6 is resolved (`00-design.md`
     * §4.4, `vitalforge` PR #39) -- there is no longer a hard "always
     * disabled" gate to test, only [runsAtMostOncePerContractVersionChange]'s
     * real scheduling gate above. Repurposed to cover what was otherwise
     * untested: that a real replay pass touches exactly its eligible subset
     * of SENT rows, not every SENT row indiscriminately.
     */
    @Test
    fun onlyEligibleRowsAreRequeued() {
        val undelivered = readingFixture(
            id = "undelivered",
            status = ReadingStatus.SENT,
            bodyFatPct = 18.4,
            deliveredFields = setOf(ReadingField.WEIGHT, ReadingField.CAPTURED_AT),
        )
        val fullyDelivered = readingFixture(
            id = "fully-delivered",
            status = ReadingStatus.SENT,
            bodyFatPct = 18.4,
            deliveredFields = setOf(ReadingField.WEIGHT, ReadingField.CAPTURED_AT, ReadingField.BODY_FAT_PCT),
        )
        val duplicate = readingFixture(
            id = "remote-duplicate",
            status = ReadingStatus.SENT,
            bodyFatPct = 18.4,
            deliveredFields = emptySet(),
            remoteDuplicate = true,
        )
        val declined = readingFixture(id = "declined", status = ReadingStatus.DECLINED, bodyFatPct = 18.4)

        val ids = ReplayMigrationWorker.eligibleRowIds(
            listOf(undelivered, fullyDelivered, duplicate, declined),
            ContractVersion.V2_BODY_COMP,
        )

        assertEquals(listOf("undelivered"), ids)
    }
}
