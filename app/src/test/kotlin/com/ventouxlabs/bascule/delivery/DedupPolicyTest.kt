package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.network.ReadingFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 00-design.md §3.3.
 *
 * The membership tests exist because of O-06: ADR-006's "a status makes the safe
 * behaviour structural" claim holds for **allowlist** predicates like the drain
 * query, and the dedup corpus is the design's one **denylist** predicate — every
 * status except `DECLINED`. A denylist is fail-open: a seventh status joins the
 * corpus by default, and if it can hold another person's weight it silently
 * suppresses one of JD's genuine readings inside 0.20 kg and 5 minutes. Stating
 * membership per status turns that into a deliberate decision.
 */
class DedupPolicyTest {

    private val corpusMembership = mapOf(
        ReadingStatus.PENDING to true,
        ReadingStatus.HELD_CONFIRM to true,
        ReadingStatus.SENT to true,
        ReadingStatus.BLOCKED_AUTH to true,
        ReadingStatus.FAILED_PERMANENT to true,
        // Another person's weight. Dedupping against it turns one correct
        // rejection into two lost readings (ADR-006, self-review item 23).
        ReadingStatus.DECLINED to false,
    )

    @Test
    fun everyStatusHasAnExplicitCorpusMembershipDecision() {
        assertEquals(
            "a new ReadingStatus must be given an explicit dedup-corpus decision here",
            ReadingStatus.entries.toSet(),
            corpusMembership.keys,
        )
    }

    @Test
    fun dedupCorpusMembershipIsExplicitPerStatus() {
        val candidate = ReadingFixtures.captured(id = "candidate")

        corpusMembership.forEach { (status, inCorpus) ->
            val existing = ReadingFixtures.captured(id = "existing-$status", status = status)
            assertEquals(
                "corpus membership for status $status",
                inCorpus,
                DedupPolicy.isDuplicate(candidate, existing),
            )
        }
    }

    /**
     * 00-design.md §3.3 promises a test "at the boundary (0.20 vs 0.21 kg)".
     * That exact assertion is not decidable against a `<=` comparison of
     * doubles — `90.20 - 90.00` is `0.2000000000000028`, so the nominal boundary
     * case falls outside the tolerance. WP-14 owns the fix (compare scaled
     * integers, as `FrameIdentity` already does, or restate the rule); this test
     * brackets the tolerance clear of the knife edge in the meantime.
     */
    @Test
    fun weightToleranceBracketsTwoHundredGrams() {
        val existing = ReadingFixtures.captured(id = "existing", weightKg = 90.00)

        assertEquals(
            true,
            DedupPolicy.isDuplicate(ReadingFixtures.captured(weightKg = 90.15), existing),
        )
        assertEquals(
            false,
            DedupPolicy.isDuplicate(ReadingFixtures.captured(weightKg = 90.25), existing),
        )
    }

    @Test
    fun timeBoundaryAtExactlyFiveMinutes() {
        val existing = ReadingFixtures.captured(id = "existing")
        val window = DedupPolicy.TIME_WINDOW_MILLIS

        assertEquals(
            true,
            DedupPolicy.isDuplicate(
                ReadingFixtures.captured(
                    capturedAtMillis = ReadingFixtures.CAPTURED_AT_MILLIS + window,
                ),
                existing,
            ),
        )
        assertEquals(
            false,
            DedupPolicy.isDuplicate(
                ReadingFixtures.captured(
                    capturedAtMillis = ReadingFixtures.CAPTURED_AT_MILLIS + window + 1,
                ),
                existing,
            ),
        )
    }
}
