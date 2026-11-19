package com.ventouxlabs.bascule.diagnostics

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Minimal persistent [DiagnosticsCounters]: one DataStore int per key, so counts
 * survive a process restart. Deliberately not the full WP-26 registry (no
 * ownership checks, no migration layer).
 *
 * The interface is synchronous, so live values are held in a [StateFlow] that
 * starts empty and is merged with the on-disk values by an asynchronous load on
 * [scope] (no blocking read on the constructing thread). Changes made before the
 * load finishes are kept: an increment adds to the stored count, a reset wins.
 * Writes wait for the load and persist the *latest* in-memory value for their
 * key. If the load fails, persistence is disabled for this instance so a later
 * write cannot overwrite the stored counts with a partial in-memory value; the
 * in-memory counters keep working. A failed write is logged.
 */
class DataStoreDiagnosticsCounters(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : DiagnosticsCounters {

    private val lock = Any()
    private val state = MutableStateFlow<Map<DiagnosticsCounterKey, Int>>(emptyMap())
    private val resetBeforeLoad = mutableSetOf<DiagnosticsCounterKey>()
    private val loaded = CompletableDeferred<Boolean>()

    init {
        scope.launch { load() }
    }

    override fun increment(key: DiagnosticsCounterKey): Int {
        val next = synchronized(lock) {
            val n = (state.value[key] ?: 0) + 1
            state.value = state.value + (key to n)
            n
        }
        persist(key)
        return next
    }

    override fun reset(key: DiagnosticsCounterKey) {
        synchronized(lock) {
            if (!loaded.isCompleted) resetBeforeLoad += key
            state.value = state.value + (key to 0)
        }
        persist(key)
    }

    override fun value(key: DiagnosticsCounterKey): Int = state.value[key] ?: 0

    override fun observeAll(): StateFlow<Map<DiagnosticsCounterKey, Int>> = state.asStateFlow()

    private fun persist(key: DiagnosticsCounterKey) {
        scope.launch {
            if (!loaded.await()) return@launch
            try {
                store.edit { prefs -> prefs[prefKey(key)] = value(key) }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to persist diagnostics counter $key", e)
            }
        }
    }

    private suspend fun load() {
        val prefs = try {
            store.data.first()
        } catch (e: IOException) {
            loaded.complete(false)
            Log.e(TAG, "Failed to read persisted diagnostics counters; persistence disabled", e)
            return
        }
        synchronized(lock) {
            val merged = state.value.toMutableMap()
            DiagnosticsCounterKey.entries.forEach { key ->
                val stored = prefs[prefKey(key)]?.takeIf { it >= 0 } ?: return@forEach
                if (key !in resetBeforeLoad) merged[key] = stored + (merged[key] ?: 0)
            }
            state.value = merged.toMap()
            resetBeforeLoad.clear()
            loaded.complete(true)
        }
    }

    private fun prefKey(key: DiagnosticsCounterKey) = intPreferencesKey(key.name)

    private companion object {
        const val TAG = "DiagnosticsCounters"
    }
}

val Context.diagnosticsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bascule_diagnostics")
