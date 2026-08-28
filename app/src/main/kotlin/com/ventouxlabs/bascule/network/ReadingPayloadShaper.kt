package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.WeightUnit
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
 * Contract v2, gated on the parallel VitalForge effort.
 *
 * The key strings below are the one thing not yet pinned: 00-design.md §4.2
 * requires them to come from VitalForge's Track A contract doc rather than be
 * invented here. They are the placeholder names that document names, and the
 * shaper is not selectable until that document lands.
 */
object V2Shaper : ReadingPayloadShaper {
    override fun shape(reading: ReadingEntity, unit: WeightUnit): ShapedPayload {
        val fields = mutableSetOf(ReadingField.WEIGHT, ReadingField.CAPTURED_AT)
        val json = buildJsonObject {
            put("weight", JsonPrimitive(unit.fromKilograms(reading.weightKg)))
            put("unit", JsonPrimitive(unit.wire))
            put("captured_at", JsonPrimitive(reading.capturedAtMillis))
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
