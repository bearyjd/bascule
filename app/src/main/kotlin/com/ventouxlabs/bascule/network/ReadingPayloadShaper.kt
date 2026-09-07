package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.WeightUnit
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class ShapedPayload(val json: JsonObject, val fields: Set<ReadingField>)

fun interface ReadingPayloadShaper {
    fun shape(reading: ReadingEntity, unit: WeightUnit): ShapedPayload
}

/**
 * Contract v1 as VitalForge ships today: exactly `{"weight", "unit"}`
 * (00-design.md §4.1).
 *
 * No `client_id`. If the Python route validates strictly, an unknown field is a
 * 422, which 00-design.md §4.5 maps to `FAILED_PERMANENT` on the first attempt —
 * total data loss from an "extra fields are free" assumption (§4.4, self-review
 * item 15).
 */
object V1Shaper : ReadingPayloadShaper {
    override fun shape(reading: ReadingEntity, unit: WeightUnit): ShapedPayload {
        val json = buildJsonObject {
            put("weight", JsonPrimitive(unit.fromKilograms(reading.weightKg)))
            put("unit", JsonPrimitive(unit.wire))
        }
        return ShapedPayload(json, setOf(ReadingField.WEIGHT))
    }
}

/**
 * Contract v2: weight plus body composition.
 *
 * The key strings below were verified against VitalForge's real `WeightIn`
 * model on 2026-09-03; the three it lacked (`bmi`/`bmr`/`amr`) were added
 * server-side in `vitalforge` PR #40, merged 2026-09-07. A server older than
 * that PR rejects the whole reading with a 422 — `ConfigViewModel.
 * saveContractVersion` is what lets a row rejected for its shape be retried
 * once the contract is switched.
 */
object V2Shaper : ReadingPayloadShaper {
    override fun shape(reading: ReadingEntity, unit: WeightUnit): ShapedPayload {
        val fields = mutableSetOf(ReadingField.WEIGHT, ReadingField.CAPTURED_AT)
        val json = buildJsonObject {
            put("weight", JsonPrimitive(unit.fromKilograms(reading.weightKg)))
            put("unit", JsonPrimitive(unit.wire))
            // VitalForge's captured_at is a Pydantic `datetime`, which parses a
            // bare number as a Unix *seconds* timestamp -- reading.capturedAtMillis
            // sent raw would be misread as ~30,000 years in the future and
            // rejected. An ISO-8601 instant with an explicit offset (Instant's
            // own toString(), e.g. "…Z") is what the field actually expects
            // (VitalForge vitalforge-weight/app.py WeightIn.captured_at).
            put("captured_at", JsonPrimitive(Instant.ofEpochMilli(reading.capturedAtMillis).toString()))
            put("client_id", JsonPrimitive(reading.id))
            putOptional("body_fat_pct", reading.bodyFatPct, ReadingField.BODY_FAT_PCT, fields)
            putOptional("body_water_pct", reading.bodyWaterPct, ReadingField.BODY_WATER_PCT, fields)
            putOptional("muscle_pct", reading.musclePct, ReadingField.MUSCLE_PCT, fields)
            putOptional("bone_mass_kg", reading.boneMassKg, ReadingField.BONE_MASS_KG, fields)
            putOptional("bmi", reading.bmi, ReadingField.BMI, fields)
            putOptional("bmr", reading.bmr, ReadingField.BMR, fields)
            putOptional("amr", reading.amr, ReadingField.AMR, fields)
        }
        return ShapedPayload(json, fields.toSet())
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putOptional(
        key: String,
        value: Double?,
        field: ReadingField,
        into: MutableSet<ReadingField>,
    ) {
        if (value == null) return
        put(key, JsonPrimitive(value))
        into += field
    }
}
