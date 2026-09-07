package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.network.ContractVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-3 MEDIUM #11 made `selectableContractVersions` the single gate for
 * both the Settings selector and the import path. v2 was withheld there while
 * its field names were unverified; they were checked against VitalForge's real
 * model and the missing three added server-side (`vitalforge` PR #40), so the
 * list is now every version. These tests pin the two things that still matter:
 * both paths read one list, and the list is worth a control.
 */
class ContractVersionSelectionTest {

    @Test
    fun offersTheBodyCompositionContract() {
        assertTrue(ContractVersion.V2_BODY_COMP in selectableContractVersions)
    }

    @Test
    fun stillOffersTheWeightOnlyContract() {
        assertTrue(
            "a server older than vitalforge PR #40 needs v1 to remain reachable",
            ContractVersion.V1_WEIGHT_ONLY in selectableContractVersions,
        )
    }

    /** An allowlist of everything, in declaration order, so the selector's order is the enum's. */
    @Test
    fun offersEveryContractVersionInDeclarationOrder() {
        assertEquals(ContractVersion.entries, selectableContractVersions)
    }

    /**
     * The inverse of the tripwire that removed the Settings control: it was
     * taken out because this list had exactly one entry (spec §5.1). With more
     * than one, a control is warranted and `ContractSection` renders it. If
     * this ever drops back to one, that is the signal to remove the control
     * again — not to relax this assertion.
     */
    @Test
    fun moreThanOneVersionIsSelectableSoTheControlIsWarranted() {
        assertTrue(selectableContractVersions.size > 1)
    }

    /** Every offered version has a human label; the selector must never show an enum name. */
    @Test
    fun everySelectableVersionHasALabel() {
        selectableContractVersions.forEach { assertTrue(contractVersionLabel(it).isNotBlank()) }
    }
}
