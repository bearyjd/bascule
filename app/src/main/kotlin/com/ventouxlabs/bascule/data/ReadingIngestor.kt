package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.ScaleReading
import com.ventouxlabs.bascule.delivery.DedupPolicy
import java.util.UUID

sealed interface IngestResult {
    data class Inserted(val reading: ReadingEntity) : IngestResult
    data class Held(val reading: ReadingEntity) : IngestResult
    data object Duplicate : IngestResult
    data class Rejected(val reason: String) : IngestResult
}

/** Attribution, conversion, deduplication, and durable insertion in one synchronous boundary. */
class ReadingIngestor(
    private val dao: ReadingDao,
    private val profiles: ScaleProfileStore,
    private val unitProvider: suspend () -> WeightUnit,
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun ingest(deviceAddress: String, measurement: ScaleReading): IngestResult {
        if (!measurement.weightKg.isFinite() ||
            measurement.weightKg !in WeightUnit.PLAUSIBLE_WEIGHT_KG_RANGE
        ) {
            return IngestResult.Rejected("implausible weight")
        }
        val active = profiles.activeProfile.value
        val matched = measurement.userIndex?.let { index ->
            profiles.profiles.value.firstOrNull {
                it.deviceAddress.equals(deviceAddress, true) && it.scaleIndex == index
            }
        }
        val status = if (matched != null && matched.id == active?.id) {
            ReadingStatus.PENDING
        } else {
            ReadingStatus.HELD_CONFIRM
        }
        val candidate = ReadingMapper.map(
            measurement = measurement,
            unit = unitProvider(),
            status = status,
            profileId = matched?.id,
            id = idProvider(),
        )
        // Checked after mapping because two of the five fields — bmr and
        // bodyWaterPct — only exist in the mapped row's units.
        BodyCompositionPlausibility.implausibleField(candidate)?.let { field ->
            return IngestResult.Rejected("implausible $field")
        }
        val corpus = dao.dedupCandidates(
            ReadingSource.SCALE.name,
            candidate.capturedAtMillis - DedupPolicy.TIME_WINDOW_MILLIS,
            candidate.capturedAtMillis + DedupPolicy.TIME_WINDOW_MILLIS,
        )
        if (DedupPolicy.isDuplicateOfAny(candidate, corpus)) return IngestResult.Duplicate
        dao.insert(candidate)
        return if (status == ReadingStatus.PENDING) IngestResult.Inserted(candidate) else IngestResult.Held(candidate)
    }
}
