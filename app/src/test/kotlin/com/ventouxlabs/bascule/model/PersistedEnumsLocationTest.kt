package com.ventouxlabs.bascule.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ReadingField and ContractVersion are persisted by the data layer, so they
 * live in a neutral package rather than in network (which depends on data).
 */
class PersistedEnumsLocationTest {
    @Test
    fun `persisted enums live in the model package`() {
        assertEquals("com.ventouxlabs.bascule.model.ReadingField", ReadingField::class.qualifiedName)
        assertEquals("com.ventouxlabs.bascule.model.ContractVersion", ContractVersion::class.qualifiedName)
    }
}
