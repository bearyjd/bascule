package com.ventouxlabs.bascule.data

/**
 * The persistence boundary: `ScaleReading` (raw SIG units) to [ReadingEntity]
 * (PRP §5's schema).
 *
 * This is where the two unit conversions live that
 * docs/prp/02-interface-revision.md §3 deliberately kept out of the decoder —
 * basal metabolism kJ to the `bmr` column's kcal, and body water *mass* to the
 * `bodyWaterPct` column, which needs the weight to divide by. Keeping them here
 * leaves the parsers verifiable field-for-field against the SIG specification.
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-13 alongside the insert path, with
 * the dedup gate (00-design.md §3.3) and the user-attribution gate (§7) that
 * decide whether a row is written at all.
 */
object ReadingMapper {
    /** Body Composition Service 1.0 reports basal metabolism in kilojoules. */
    const val KJ_PER_KCAL = 4.184
}
