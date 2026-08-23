package com.ventouxlabs.bascule.ble.session

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO

/**
 * Pure arithmetic over `00-design.md` §2.5's timer table — no transport
 * involved. Pins the constants the rest of WP-06/07 consume.
 */
class SessionBudgetTest {

    @Test
    fun connectLadderFitsWithinConnectPhaseBudget() {
        val e1Worst = SessionBudget.CONNECT_ATTEMPT_TIMEOUT *
            (SessionBudget.CONNECT_TIMEOUT_MAX_RETRIES + 1) +
            SessionBudget.CONNECT_TIMEOUT_RETRY_DELAY * SessionBudget.CONNECT_TIMEOUT_MAX_RETRIES
        assertTrue(
            "E1's own worst case (8s x2 + 1.5s) must fit the 20s connect-phase budget",
            e1Worst <= SessionBudget.CONNECT_PHASE_BUDGET,
        )

        val e2Worst = SessionBudget.STATUS_133_RETRY_DELAYS.fold(ZERO) { acc, d -> acc + d }
        assertTrue(
            "E2's retry ladder (500ms/1s/2s) must fit the 20s connect-phase budget",
            e2Worst <= SessionBudget.CONNECT_PHASE_BUDGET,
        )

        val e3Worst = SessionBudget.CONTENTION_RETRY_DELAY * SessionBudget.CONTENTION_MAX_RETRIES
        assertTrue(
            "E3's single 2s retry must fit the 20s connect-phase budget",
            e3Worst <= SessionBudget.CONNECT_PHASE_BUDGET,
        )
    }

    /**
     * Each ladder fits the budget alone ([connectLadderFitsWithinConnectPhaseBudget]),
     * but nothing stops consecutive connect attempts from hitting *different*
     * edges before any single one exhausts its own retry cap — e.g. E2's full
     * ladder followed by a fresh E1 pair. That combination is what makes
     * `GattSession`'s own `withTimeoutOrNull(CONNECT_PHASE_BUDGET)` wrapper
     * load-bearing rather than decorative: without it, this combination would
     * run past 20s.
     */
    @Test
    fun individualLaddersFitButCanCombinePastTheBudget() {
        val e2ThenFreshE1Pair = SessionBudget.STATUS_133_RETRY_DELAYS.fold(ZERO) { acc, d -> acc + d } +
            SessionBudget.CONNECT_ATTEMPT_TIMEOUT * (SessionBudget.CONNECT_TIMEOUT_MAX_RETRIES + 1) +
            SessionBudget.CONNECT_TIMEOUT_RETRY_DELAY

        assertTrue(
            "E2's ladder ($e2ThenFreshE1Pair total combined with a fresh E1 pair) must exceed the " +
                "20s connect-phase budget, or the wrapper in GattSession.connectAndDiscover has nothing to do",
            e2ThenFreshE1Pair > SessionBudget.CONNECT_PHASE_BUDGET,
        )
    }

    @Test
    fun hardCeilingExceedsSumOfNonBondTimers() {
        val handshakeLadder = SessionBudget.HANDSHAKE_ACK_TIMEOUT *
            (SessionBudget.HANDSHAKE_ACK_MAX_RETRIES + 1)

        val sum = SessionBudget.CONNECT_PHASE_BUDGET +
            SessionBudget.DISCOVERY_TIMEOUT +
            handshakeLadder +
            SessionBudget.FIRST_INDICATION_TIMEOUT +
            SessionBudget.POST_EMISSION_IDLE

        assertTrue(
            "the 90s hard ceiling must exceed connect + discovery + handshake + " +
                "first-indication + post-emission-idle ($sum), or a session that never " +
                "bonds is arithmetically unreachable",
            sum < SessionBudget.HARD_SESSION_CEILING,
        )
    }

    /**
     * Named explicitly in 01-plan.md's WP-07 section. Models the *real* worst
     * case, not just one ack ladder: `BeurerDecoder`'s handshake can chain up
     * to three independently-ack'd UCP writes — a stale stored credential's
     * Consent (refused) → Register → Consent again — each with its own E6
     * ladder, plus the Current Time opening write. `HARD_SESSION_CEILING` is
     * not enforced anywhere in `GattSession` yet (that lands with WP-08's
     * worker), so exceeding it here is not a regression to silently fix — it
     * is the number WP-08 needs before adding that enforcement.
     */
    @Test
    fun handshakeLadderFitsWithinHardCeilingAfterConnectPhase() {
        val maxChainedHandshakeSteps = 3 // stale-credential Consent -> Register -> Consent again
        val handshakeLadder = SessionBudget.HANDSHAKE_ACK_TIMEOUT *
            (SessionBudget.HANDSHAKE_ACK_MAX_RETRIES + 1) *
            maxChainedHandshakeSteps

        val beforeFirstIndication = SessionBudget.CONNECT_PHASE_BUDGET +
            SessionBudget.DISCOVERY_TIMEOUT +
            SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT +
            handshakeLadder
        val withFirstIndication = beforeFirstIndication + SessionBudget.FIRST_INDICATION_TIMEOUT

        assertTrue(
            "connect + discovery + opening write + the full chained handshake ladder " +
                "($beforeFirstIndication) should still leave room for at least a fast first indication",
            beforeFirstIndication < SessionBudget.HARD_SESSION_CEILING,
        )
        assertTrue(
            "the full worst case ($withFirstIndication) exceeds the 90s hard ceiling — expected " +
                "today since nothing enforces it yet; WP-08 needs this number when it does",
            withFirstIndication >= SessionBudget.HARD_SESSION_CEILING,
        )
    }

    @Test
    fun bondWaitIsExcludedFromTheHardCeilingByHavingItsOwnBudget() {
        assertTrue(
            "the bonding path's own budget must be at least the hard ceiling plus a full bond wait",
            SessionBudget.BONDING_SESSION_BUDGET >= SessionBudget.HARD_SESSION_CEILING + SessionBudget.BOND_WAIT,
        )
    }
}
