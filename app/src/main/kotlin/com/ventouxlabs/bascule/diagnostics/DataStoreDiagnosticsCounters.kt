package com.ventouxlabs.bascule.diagnostics

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Minimal persistent [DiagnosticsCounters]: one DataStore int per key, so counts
 * survive a process restart. Deliberately not the full WP-26 registry (no
 * ownership checks, no migration layer).
 *
 * The interface is synchronous, so live values are held in a [StateFlow] seeded
 * once from disk; every change is then written through asynchronously. Each
 * write persists the *latest* in-memory value for its key rather than the value
 * that triggered it, so out-of-order writes cannot leave stale data. A failed
 * read starts from zero and a failed write is logged; neither stops the
 * in-memory counters from working.
 */
class DataStoreDiagnosticsCounters(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : DiagnosticsCounters {

    private val state = MutableStateFlow(loadInitial())

    override fun increment(key: DiagnosticsCounterKey): Int {
        var next = 0
        state.update { current ->
            next = (current[key] ?: 0) + 1
            current + (key to next)
        }
        persist(key)
        return next
    }

    override fun reset(key: DiagnosticsCounterKey) {
        state.update { current -> current + (key to 0) }
        persist(key)
    }

    override fun value(key: DiagnosticsCounterKey): Int = state.value[key] ?: 0

    override fun observeAll(): StateFlow<Map<DiagnosticsCounterKey, Int>> = state.asStateFlow()

    private fun persist(key: DiagnosticsCounterKey) {
        scope.launch {
            try {
                store.edit { prefs -> prefs[prefKey(key)] = value(key) }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to persist diagnostics counter $key", e)
            }
        }
    }

    private fun loadInitial(): Map<DiagnosticsCounterKey, Int> {
        val prefs = try {
            runBlocking { store.data.first() }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read persisted diagnostics counters; starting from zero", e)
            return emptyMap()
        }
        return DiagnosticsCounterKey.entries.mapNotNull { key ->
            prefs[prefKey(key)]?.takeIf { it >= 0 }?.let { key to it }
        }.toMap()
    }

    private fun prefKey(key: DiagnosticsCounterKey) = intPreferencesKey(key.name)

    private companion object {
        const val TAG = "DiagnosticsCounters"
    }
}

val Context.diagnosticsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bascule_diagnostics")
