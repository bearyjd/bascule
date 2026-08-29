package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.network.ContractVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-3 MEDIUM #11. `V2Shaper`'s KDoc asserts it "is not selectable until"
 * VitalForge's Track A contract doc lands, but the settings dropdown was the
 * actual selection mechanism and gated nothing — so the invariant was false
 * and a user could post body-composition data under placeholder field names.
 * These tests are what makes the KDoc's claim true.
 */
class ContractVersionSelectionTest {

    @Test
    fun withholdsTheUnfinishedV2Contract() {
        assertFalse(
            "V2Shaper's body-composition field names are placeholders, not the server's",
            ContractVersion.V2_BODY_COMP in selectableContractVersions,
        )
    }

    @Test
    fun offersTheShippedV1Contract() {
        assertTrue(
            "gating v2 must not leave the import path with no valid contract",
            ContractVersion.V1_WEIGHT_ONLY in selectableContractVersions,
        )
    }

    /**
     * A denylist, not an allowlist: a contract version added after this filter
     * was written is offered by default. That is the intended direction — the
     * one entry withheld is the one with a documented unfinished shaper, and a
     * new version arriving with a real contract doc should not need this file
     * edited to become reachable.
     */
    @Test
    fun offersEveryContractVersionExceptTheWithheldOne() {
        assertEquals(
            ContractVersion.entries.filterNot { it == ContractVersion.V2_BODY_COMP },
            selectableContractVersions,
        )
    }

    /**
     * The Settings dropdown was removed because this list has exactly one
     * entry, making it a control the user cannot change (spec §5.1). If a
     * second version ever becomes selectable this fails, which is the signal
     * to bring the control back — not to relax the assertion.
     */
    @Test
    fun exactlyOneContractVersionIsSelectableSoNoControlIsWarranted() {
        assertEquals(listOf(ContractVersion.V1_WEIGHT_ONLY), selectableContractVersions)
    }
}
