package com.ventouxlabs.bascule.data

/**
 * Decides what `ReadingEntity.capturedAtMillis` means: **when the weigh-in
 * happened**, not when the phone heard about it.
 *
 * The two are the same to within seconds for a live wake-on-advertisement
 * session, and were treated as interchangeable until #28. Since then a
 * weigh-in taken with no phone present is stored on the scale and handed over
 * at the next consent — possibly hours later. Verified on hardware
 * 2026-09-19: a 10:34:51 weigh-in delivered at 10:36:28 landed with
 * `capturedAtMillis` = 10:36:28. The scale's own timestamp is the only thing
 * that knows when the person stood on it, and it is what everything downstream
 * keys on: the History sort and its "2 hours ago" label, the §3.3 dedup window
 * (the same weigh-in delivered twice must collide on the weigh-in time, not on
 * two different delivery times), the remote-duplicate check, and the v2 wire
 * field `captured_at`, which VitalForge stores as sent and which then becomes
 * the reading's time on the Garmin timeline (A6, 00-design.md §4.4).
 *
 * The scale's clock is trustworthy because Bascule writes Current Time
 * (`2A2B`) as the first step of every session, and the probe showed the frame
 * timestamp matching the written value to the second
 * (03-hardware-validation.md §5). It is *bounded* because that write is the
 * only thing setting it: a scale whose batteries were changed before any
 * session synced its clock carries a default epoch, and a reading taken then
 * would land years in the past. A frame whose year field is zero already
 * decodes to null (`FrameReader.dateTimeMillis`), so the fallback here covers
 * the RTC that was *set* to something wrong, not the one that was never set.
 * Either way the received time is the honest answer: it is later than the
 * truth, never earlier, and it is at least a time the phone can vouch for.
 *
 * One known imprecision, accepted: `FrameReader.dateTimeMillis` interprets
 * the scale's wall-clock fields in the phone's *current* zone, while the
 * Current Time write that set them used the zone of that earlier session. A
 * stored weigh-in delivered across a DST change therefore resolves an hour
 * off — inside both bounds, so it is believed as-is. Pre-existing decoder
 * behaviour that this policy makes load-bearing; not worth a second column.
 */
object CaptureTimestampPolicy {

    /**
     * A scale timestamp older than this is not believed. The bound's one job
     * is to reject a reset RTC's default epoch: `FrameReader` accepts years
     * 2000..2100, so a scale that came up on 2000-01-01 after a battery change
     * decodes to a real instant, decades off. A year of slack loses none of
     * that protection and stops a correctly timed six-week-old weigh-in from
     * being silently re-stamped with its delivery time. Far longer than
     * `DeliveryCoordinator.EXPIRY_MILLIS` (14 days), safely: the expiry clock
     * anchors on `retryEpochMillis`, which `ReadingMapper` sets from the
     * received time, so an old-but-plausible weigh-in is still delivered with
     * its true time and a full retry window.
     */
    const val MAX_PAST_MILLIS = 365L * 24 * 60 * 60 * 1000

    /**
     * A scale timestamp further ahead than this is not believed. Small on
     * purpose: the received time is taken after the frame has arrived, so it
     * is never earlier than the true instant, and a scale time ahead of it is
     * pure skew — the received time is strictly the closer answer. The bound
     * is nonzero only so a second or two of ordinary skew does not flip which
     * clock a live session's row carries. It must also stay under VitalForge's
     * `CAPTURED_AT_FUTURE_TOLERANCE_SECONDS` (60 s, `vitalforge_weight/models.py`):
     * a `captured_at` further ahead than that is a 422, which the drainer
     * classifies as permanent and marks `FAILED_PERMANENT` on the first attempt.
     */
    const val MAX_FUTURE_MILLIS = 30L * 1000

    /**
     * The scale's timestamp when it is present and lands inside
     * `[receivedAt - MAX_PAST_MILLIS, receivedAt + MAX_FUTURE_MILLIS]`
     * (inclusive at both ends), otherwise the received time.
     */
    fun resolve(scaleTimestampMillis: Long?, receivedAtMillis: Long): Long {
        val scaleTimestamp = scaleTimestampMillis ?: return receivedAtMillis
        val believable = (receivedAtMillis - MAX_PAST_MILLIS)..(receivedAtMillis + MAX_FUTURE_MILLIS)
        return if (scaleTimestamp in believable) scaleTimestamp else receivedAtMillis
    }
}
