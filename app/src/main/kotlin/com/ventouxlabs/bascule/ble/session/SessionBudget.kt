package com.ventouxlabs.bascule.ble.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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

    /**
     * E7: how long a subscribed session listens for a measurement.
     *
     * Was 45 s, on the assumption that a session begins when the user steps
     * on. Hardware showed the opposite: the BF720 advertises continuously
     * while awake, so sessions begin whenever the phone happens to reconnect,
     * and it only ever indicates a *live* weigh-in to a client that is already
     * consented and subscribed — nothing stored is forwarded on the SIG path.
     * A 45 s listen every few minutes therefore made capture a lottery on
     * whether the user stepped on inside the window (observed: 105 straight
     * idle sessions, one capture ever). Coverage — the fraction of time a
     * session is connected — is what decides whether stepping on works, and
     * the scale itself holds a link open for minutes.
     */
    val FIRST_INDICATION_TIMEOUT: Duration = 8.minutes

    /** E7: consecutive `NoMeasurement` sessions that raise a re-pairing notice. */
    const val NO_MEASUREMENT_STREAK_NOTIFY_THRESHOLD: Int = 3

    /**
     * E8: the window for the single reconnect attempt allowed after the link
     * drops mid-`MEASURING`. The scale is usually still powered at that point,
     * which is why one attempt is worth making and a second is not.
     */
    val RECONNECT_ONCE_WINDOW: Duration = 5.seconds

    /** E8: "exactly one" reconnect attempt before the session gives up. */
    const val RECONNECT_MAX_ATTEMPTS: Int = 1

    /** E17: correlation window for a buffered Weight Measurement. */
    val BODY_COMPOSITION_CORRELATION_WINDOW: Duration = 4.seconds

    /** Idle teardown timer once a reading has been `EMITTED`. */
    val POST_EMISSION_IDLE: Duration = 10.seconds

    /**
     * Sized so the ceiling still contains connect + discovery + handshake +
     * the full listen + post-emission idle (`SessionBudgetTest`), while the
     * *chained* worst case — three independently-acked handshake steps ahead
     * of a full listen — still runs past it, which is what keeps the ceiling
     * load-bearing rather than decorative. Both bounds are asserted; the
     * headroom is the value that satisfies them, not a round number.
     *
     * The whole budget stays under WorkManager's 10-minute worker limit. The
     * worker runs as a foreground service, which lifts that limit, but a
     * session that fits inside it regardless is one fewer platform behaviour
     * to depend on.
     */
    private val CEILING_HEADROOM: Duration = 50.seconds

    /**
     * Unconditional teardown from worker start. Counts radio time only — the
     * bond wait is excluded and the ceiling's clock is suspended while a bond is
     * pending (§2.5); [BONDING_SESSION_BUDGET] governs that path instead.
     */
    val HARD_SESSION_CEILING: Duration = FIRST_INDICATION_TIMEOUT + CEILING_HEADROOM

    /** Sessions that enter `BONDING` are governed by this budget instead of the hard ceiling. */
    val BONDING_SESSION_BUDGET: Duration = HARD_SESSION_CEILING + BOND_WAIT + 30.seconds

}
