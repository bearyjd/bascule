package com.ventouxlabs.bascule.ble.decoders

import com.ventouxlabs.bascule.ble.ScaleReading
import com.ventouxlabs.bascule.ble.session.DecodeEvent

/**
 * Pairs a Weight Measurement with its Body Composition Measurement so one
 * physical weigh-in produces exactly one [DecodeEvent.Stable].
 *
 * ADR-007 established that a Body Composition frame carries no timestamp and no
 * user ID of its own — only the Weight frame does. Treating each notification as
 * an independently complete reading (00-design.md §2.6's original model) would
 * emit two `Stable` events for one weigh-in, the second of them unattributable.
 *
 * Correlation lives here rather than in `GattSession` because emission timing is
 * now a protocol fact, not a session policy: only the decoder knows that these
 * two characteristics describe one event. `GattSession` keeps a single rule —
 * persist on `Stable` — and gains no per-characteristic knowledge.
 *
 * Consequently the in-session duplicate latch of 00-design.md §2.3 E9 also moves
 * here, since this class now owns what "an emission" is.
 *
 * **One emission per session, full stop** (O-03 disposition (a),
 * docs/prp/02-phase2-dispositions.md). A Body Composition frame carries no signal
 * that could identify which weigh-in it belongs to, so as soon as a session holds
 * more than one candidate weight — a second distinct weight frame, or any frame
 * arriving after an emission — no later pairing is provable and correlation
 * closes. Frames after that point are counted and dropped, never speculatively
 * attached. The cost is real and accepted: if a household member weighs first and
 * JD second inside one session, JD's reading is lost where a speculative pairing
 * would have kept it. 00-design.md §8.4 states the asymmetry that decides it —
 * bad Garmin history is materially harder to clean up than a missed weigh-in is
 * to redo.
 */
internal class MeasurementCorrelator(
    private val decoderId: String,
    private val clock: () -> Long,
) {
    private var pendingWeight: WeightMeasurement? = null
    private var orphanBodyComposition: BodyCompositionMeasurement? = null
    private val emittedFrames = mutableSetOf<FrameIdentity>()
    private var emissions = 0

    var duplicateFramesSuppressed: Int = 0
        private set

    /** Frames dropped because correlation closed and they can no longer be paired. */
    var unpairableFramesDropped: Int = 0
        private set

    private val correlationClosed: Boolean get() = emissions >= MAX_EMISSIONS_PER_SESSION

    /** True while a completed weight is buffered waiting for its body-composition pair. */
    val hasPendingCorrelation: Boolean get() = pendingWeight != null

    fun onWeight(measurement: WeightMeasurement): DecodeEvent {
        val identity = FrameIdentity(measurement)
        if (identity in emittedFrames || identity == pendingWeight?.let(::FrameIdentity)) {
            duplicateFramesSuppressed++
            return DecodeEvent.Ignored
        }
        if (correlationClosed) {
            unpairableFramesDropped++
            return DecodeEvent.Ignored
        }

        val orphan = orphanBodyComposition
        if (orphan != null) {
            orphanBodyComposition = null
            return emit(measurement, orphan)
        }

        val superseded = pendingWeight
        if (superseded != null) {
            // A second distinct weight frame means the first weigh-in's
            // body-composition frame is never coming. Release the first
            // weight-only and close: any body-comp frame arriving from here on
            // could belong to either weigh-in, and the frame itself says which
            // one nowhere.
            pendingWeight = null
            unpairableFramesDropped++
            return emit(superseded, null)
        }

        pendingWeight = measurement
        return DecodeEvent.Ignored
    }

    fun onBodyComposition(measurement: BodyCompositionMeasurement): DecodeEvent {
        val weight = pendingWeight
        if (weight == null) {
            if (correlationClosed) {
                unpairableFramesDropped++
                return DecodeEvent.Ignored
            }
            // Arrived before its weight frame; hold it until one shows up.
            orphanBodyComposition = measurement
            return DecodeEvent.Ignored
        }
        pendingWeight = null
        return emit(weight, measurement)
    }

    /** Releases a buffered weight frame whose body-composition pair never arrived. */
    fun flush(): DecodeEvent? {
        val weight = pendingWeight
        pendingWeight = null
        orphanBodyComposition = null
        return weight?.let { emit(it, null) }
    }

    private fun emit(
        weight: WeightMeasurement,
        bodyComposition: BodyCompositionMeasurement?,
    ): DecodeEvent {
        emittedFrames += FrameIdentity(weight)
        emissions++
        return DecodeEvent.Stable(merge(weight, bodyComposition))
    }

    private fun merge(
        weight: WeightMeasurement,
        body: BodyCompositionMeasurement?,
    ) = ScaleReading(
        weightKg = weight.weightKg,
        userIndex = weight.userIndex,
        bodyFatPct = body?.bodyFatPct,
        musclePct = body?.musclePct,
        muscleMassKg = body?.muscleMassKg,
        fatFreeMassKg = body?.fatFreeMassKg,
        softLeanMassKg = body?.softLeanMassKg,
        bodyWaterMassKg = body?.bodyWaterMassKg,
        impedanceOhms = body?.impedanceOhms,
        basalMetabolismKj = body?.basalMetabolismKj,
        bmi = weight.bmi,
        heightM = weight.heightM ?: body?.heightM,
        // Not fields of the SIG Body Composition profile; a future non-SIG
        // decoder may supply them (docs/prp/02-interface-revision.md §3).
        boneMassKg = null,
        amr = null,
        capturedAtMillis = clock(),
        scaleTimestampMillis = weight.timestampMillis,
        decoderId = decoderId,
    )

    /**
     * Identity of one weigh-in as the scale reports it. The raw weight is used
     * rather than the scaled kilograms so equality is integer equality.
     */
    private data class FrameIdentity(
        val userIndex: Int?,
        val timestampMillis: Long?,
        val rawWeight: Int,
    ) {
        constructor(measurement: WeightMeasurement) : this(
            measurement.userIndex,
            measurement.timestampMillis,
            measurement.rawWeight,
        )
    }

    companion object {
        /**
         * 00-design.md §2.3 E9's "at most 2 distinct userIndexes per session" is
         * retired for this decoder: an unidentifiable Body Composition frame
         * makes a second weigh-in in one session unpairable rather than merely
         * unattributed. The latch is one emission per session.
         */
        const val MAX_EMISSIONS_PER_SESSION = 1
    }
}
