package com.ventouxlabs.bascule.ble.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `00-design.md` §2.5's timer table as named constants, so the arithmetic that
 * makes each retry ladder fit inside its budget is testable in one place
 * (`SessionBudgetTest`) instead of scattered across [GattSession] as magic
 * numbers.
 */
object SessionBudget {

    /** E1: per-attempt connect timeout. */
    val CONNECT_ATTEMPT_TIMEOUT: Duration = 8.seconds

    /** E1: delay before the single connect-timeout retry. */
    val CONNECT_TIMEOUT_RETRY_DELAY: Duration = 1500.milliseconds

    /** E1: "retry once" — one retry after the first timeout. */
    const val CONNECT_TIMEOUT_MAX_RETRIES: Int = 1

    /** E2: the 500 ms / 1 s / 2 s ladder after a status-133 `GATT_ERROR`. */
    val STATUS_133_RETRY_DELAYS: List<Duration> = listOf(500.milliseconds, 1.seconds, 2.seconds)

    /** E2: "up to 3 retries" before the connect phase gives up. */
    val STATUS_133_MAX_RETRIES: Int = STATUS_133_RETRY_DELAYS.size

    /** E3: single retry delay for busy/contention statuses. */
    val CONTENTION_RETRY_DELAY: Duration = 2.seconds

    /** E3: "one retry", deliberately non-aggressive (ADR-003). */
    const val CONTENTION_MAX_RETRIES: Int = 1

    /** Hard cap across every E1/E2/E3 retry combined — whichever ends the phase first. */
    val CONNECT_PHASE_BUDGET: Duration = 20.seconds

    /** E4: service discovery timeout. */
    val DISCOVERY_TIMEOUT: Duration = 5.seconds

    /** E4: consecutive `Incompatible` outcomes that suspend scan arming. */
    const val INCOMPATIBLE_STREAK_SUSPEND_THRESHOLD: Int = 3

    /** E5: max wait for `BOND_BONDED` after `createBond()`. */
    val BOND_WAIT: Duration = 30.seconds

    /** E6: ack timeout for a Register/Consent write on the User Control Point. */
    val HANDSHAKE_ACK_TIMEOUT: Duration = 3.seconds

    /** E6: "max 2 retries" of an unacknowledged handshake write. */
    const val HANDSHAKE_ACK_MAX_RETRIES: Int = 2

    /**
     * A plain characteristic write's GATT-level completion (the Current Time
     * opening write, §4.4) — deliberately its own constant rather than reusing
     * [HANDSHAKE_ACK_TIMEOUT], because it waits on `WriteComplete`, not a UCP
     * indication, and tuning one must not silently move the other.
     */
    val OPENING_WRITE_COMPLETE_TIMEOUT: Duration = 2.seconds

    /** E7: first measurement indication, counted from `SUBSCRIBED`. */
    val FIRST_INDICATION_TIMEOUT: Duration = 45.seconds

    /** E7: consecutive `NoMeasurement` sessions that raise a re-pairing notice. */
    const val NO_MEASUREMENT_STREAK_NOTIFY_THRESHOLD: Int = 3

    /** E17: correlation window for a buffered Weight Measurement. */
    val BODY_COMPOSITION_CORRELATION_WINDOW: Duration = 4.seconds

    /** Idle teardown timer once a reading has been `EMITTED`. */
    val POST_EMISSION_IDLE: Duration = 10.seconds

    /**
     * Unconditional teardown from worker start. Counts radio time only — the
     * bond wait is excluded and the ceiling's clock is suspended while a bond is
     * pending (§2.5); [BONDING_SESSION_BUDGET] governs that path instead.
     */
    val HARD_SESSION_CEILING: Duration = 90.seconds

    /** Sessions that enter `BONDING` are governed by this budget instead of the hard ceiling. */
    val BONDING_SESSION_BUDGET: Duration = 150.seconds
}
