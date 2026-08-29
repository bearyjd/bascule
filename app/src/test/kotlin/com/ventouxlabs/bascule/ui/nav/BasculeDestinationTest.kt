package com.ventouxlabs.bascule.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasculeDestinationTest {

    @Test
    fun bottomBarShowsHistoryScaleAndSettingsOnly() {
        assertEquals(
            listOf(BasculeDestination.History, BasculeDestination.Scale, BasculeDestination.Config),
            BasculeDestination.bottomBarEntries,
        )
    }

    /**
     * Manual entry is reachable only from History's FAB. Keeping it out of the
     * bar is the behavioural half of P25 — two entry points to one destination
     * gave it two different back-stack contracts.
     */
    @Test
    fun manualEntryIsStillARouteButNotABarItem() {
        assertFalse(BasculeDestination.ManualEntry.inBottomBar)
        assertTrue(BasculeDestination.entries.contains(BasculeDestination.ManualEntry))
        assertEquals("manual_entry", BasculeDestination.ManualEntry.route)
    }

    @Test
    fun everyRouteIsUnique() {
        val routes = BasculeDestination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
