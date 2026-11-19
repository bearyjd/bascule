package com.ventouxlabs.bascule.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Minimal persistence for [DiagnosticsCounters] (not the full WP-26 registry):
 * counts must survive a process restart, modelled by a second instance over the
 * same store.
 */
class DataStoreDiagnosticsCountersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.root, "diag.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { file }

    private fun awaitStored(store: DataStore<Preferences>, key: DiagnosticsCounterKey, expected: Int) =
        runBlocking {
            withTimeout(5_000) {
                store.data.first { it[intPreferencesKey(key.name)] == expected }
            }
        }

    private fun awaitValue(counters: DiagnosticsCounters, key: DiagnosticsCounterKey, expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (counters.value(key) != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, counters.value(key))
    }

    @Test
    fun incrementReturnsNewValueAndResetZeroes() {
        val counters = DataStoreDiagnosticsCounters(newStore(), scope)
        assertEquals(1, counters.increment(DiagnosticsCounterKey.MISSED_QUOTA))
        assertEquals(2, counters.increment(DiagnosticsCounterKey.MISSED_QUOTA))
        assertEquals(2, counters.value(DiagnosticsCounterKey.MISSED_QUOTA))
        counters.reset(DiagnosticsCounterKey.MISSED_QUOTA)
        assertEquals(0, counters.value(DiagnosticsCounterKey.MISSED_QUOTA))
    }

    @Test
    fun countsSurviveRestart() {
        val store = newStore()
        val first = DataStoreDiagnosticsCounters(store, scope)
        repeat(3) { first.increment(DiagnosticsCounterKey.MALFORMED_COUNT) }
        first.increment(DiagnosticsCounterKey.NO_MEASUREMENT)
        awaitStored(store, DiagnosticsCounterKey.MALFORMED_COUNT, 3)
        awaitStored(store, DiagnosticsCounterKey.NO_MEASUREMENT, 1)

        // A fresh instance over the same store seeds itself from disk, as after a restart.
        val second = DataStoreDiagnosticsCounters(store, scope)
        awaitValue(second, DiagnosticsCounterKey.MALFORMED_COUNT, 3)
        assertEquals(1, second.value(DiagnosticsCounterKey.NO_MEASUREMENT))
        assertEquals(0, second.value(DiagnosticsCounterKey.MISSED_QUOTA))
        assertEquals(3, second.observeAll().value[DiagnosticsCounterKey.MALFORMED_COUNT])
    }

    @Test
    fun resetIsPersisted() {
        val store = newStore()
        val counters = DataStoreDiagnosticsCounters(store, scope)
        counters.increment(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)
        awaitStored(store, DiagnosticsCounterKey.INCOMPATIBLE_STREAK, 1)
        counters.reset(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)
        awaitStored(store, DiagnosticsCounterKey.INCOMPATIBLE_STREAK, 0)
    }

    @Test
    fun unknownOrNegativeStoredValuesAreIgnored() {
        val store = newStore()
        runBlocking {
            store.edit {
                it[intPreferencesKey("NOT_A_KEY")] = 9
                it[intPreferencesKey(DiagnosticsCounterKey.DROPPED_OTHER_USER.name)] = -4
            }
        }
        val counters = DataStoreDiagnosticsCounters(store, scope)
        // Make the load observable: a valid key is merged in, the bad ones are ignored.
        runBlocking { store.edit { it[intPreferencesKey(DiagnosticsCounterKey.MISSED_QUOTA.name)] = 2 } }
        val fresh = DataStoreDiagnosticsCounters(store, scope)
        awaitValue(fresh, DiagnosticsCounterKey.MISSED_QUOTA, 2)
        assertEquals(0, fresh.value(DiagnosticsCounterKey.DROPPED_OTHER_USER))
        assertEquals(
            setOf(DiagnosticsCounterKey.MISSED_QUOTA),
            fresh.observeAll().value.keys,
        )
        assertEquals(0, counters.value(DiagnosticsCounterKey.DROPPED_OTHER_USER))
    }

    @Test
    fun incrementBeforeLoadCompletesIsMergedWithStoredCount() {
        val real = newStore()
        val key = DiagnosticsCounterKey.MALFORMED_COUNT
        runBlocking { real.edit { it[intPreferencesKey(key.name)] = 100 } }
        val gate = CompletableDeferred<Unit>()
        val slow = object : DataStore<Preferences> {
            override val data = flow { gate.await(); emitAll(real.data) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val counters = DataStoreDiagnosticsCounters(slow, scope)
        assertEquals(1, counters.increment(key))
        gate.complete(Unit)
        awaitValue(counters, key, 101)
        awaitStored(real, key, 101)
    }

    @Test
    fun resetBeforeLoadCompletesWins() {
        val real = newStore()
        val key = DiagnosticsCounterKey.MALFORMED_COUNT
        runBlocking { real.edit { it[intPreferencesKey(key.name)] = 100 } }
        val gate = CompletableDeferred<Unit>()
        val slow = object : DataStore<Preferences> {
            override val data = flow { gate.await(); emitAll(real.data) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val counters = DataStoreDiagnosticsCounters(slow, scope)
        counters.reset(key)
        gate.complete(Unit)
        awaitStored(real, key, 0)
        assertEquals(0, counters.value(key))
    }

    @Test
    fun failedLoadDoesNotOverwriteStoredCounts() {
        val real = newStore()
        val key = DiagnosticsCounterKey.MALFORMED_COUNT
        runBlocking { real.edit { it[intPreferencesKey(key.name)] = 100 } }
        val broken = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("read failed") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val counters = DataStoreDiagnosticsCounters(broken, scope)
        assertEquals(1, counters.increment(key))
        Thread.sleep(300)
        assertEquals(100, runBlocking { real.data.first()[intPreferencesKey(key.name)] })
        assertEquals(1, counters.value(key))
    }
}
