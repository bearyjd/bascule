package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.model.ContractVersion
import com.ventouxlabs.bascule.model.ReadingField
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L1. `DataStoreConfigStore` reads two persisted enums back by name. A value
 * that matches no constant of the current build — corrupted storage, or a
 * constant renamed by a refactor — reverts to the enum's default with no signal.
 *
 * These tests pin that behaviour rather than endorsing it. For [WeightUnit] the
 * revert is cosmetic; for [ContractVersion] it silently downgrades a V2 user to
 * `V1_WEIGHT_ONLY`, which stops every body-composition field from being
 * delivered. [readStoredEnum] is tested directly because it is the whole of the
 * decision — the surrounding `store.data.map` adds no behaviour — and because it
 * is pure, so no DataStore or `Context` is needed.
 */
class ConfigStoreTest {

    @Test
    fun absentValueIsDistinguishedFromAnUnreadableOne() {
        assertEquals(StoredEnum.Absent, readStoredEnum(null, ContractVersion.entries))
        assertEquals(
            StoredEnum.Unreadable("V2_BODY_COMPOSITION"),
            readStoredEnum("V2_BODY_COMPOSITION", ContractVersion.entries),
        )
    }

    @Test
    fun everyConstantOfBothEnumsRoundTripsByName() {
        for (unit in WeightUnit.entries) {
            assertEquals(StoredEnum.Parsed(unit), readStoredEnum(unit.name, WeightUnit.entries))
        }
        for (version in ContractVersion.entries) {
            assertEquals(StoredEnum.Parsed(version), readStoredEnum(version.name, ContractVersion.entries))
        }
    }

    /**
     * The non-cosmetic case: a stored `V2_BODY_COMP` that stops parsing reads
     * back as V1, and V1 advertises only `WEIGHT`. The assertion on
     * `supportedFields` is what makes the silent scope reduction visible here
     * rather than only at the delivery boundary.
     */
    @Test
    fun corruptedContractVersionSilentlyDowngradesToWeightOnly() {
        val stored = readStoredEnum("garbage", ContractVersion.entries)

        assertEquals(StoredEnum.Unreadable("garbage"), stored)
        assertEquals(ContractVersion.V1_WEIGHT_ONLY, stored.valueOr(ContractVersion.V1_WEIGHT_ONLY))
        assertEquals(setOf(ReadingField.WEIGHT), ContractVersion.V1_WEIGHT_ONLY.supportedFields)
        // The value the user had configured advertises strictly more.
        assertEquals(true, ContractVersion.V2_BODY_COMP.supportedFields.size > 1)
    }

    @Test
    fun corruptedWeightUnitRevertsToKilograms() {
        val stored = readStoredEnum("STONES", WeightUnit.entries)

        assertEquals(StoredEnum.Unreadable("STONES"), stored)
        assertEquals(WeightUnit.KILOGRAMS, stored.valueOr(WeightUnit.KILOGRAMS))
    }

    /** An empty string is a written value, not an absent one. */
    @Test
    fun emptyStringIsUnreadableRatherThanAbsent() {
        assertEquals(StoredEnum.Unreadable(""), readStoredEnum("", WeightUnit.entries))
    }

    /** Matching is exact — enum names are case-sensitive, as `valueOf` was. */
    @Test
    fun matchingIsCaseSensitive() {
        assertEquals(StoredEnum.Unreadable("kilograms"), readStoredEnum("kilograms", WeightUnit.entries))
    }
}
