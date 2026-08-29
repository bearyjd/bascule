package com.ventouxlabs.bascule

import kotlinx.coroutines.CancellationException

/**
 * Runs [block], routing any [Throwable] other than [CancellationException]
 * (which must always propagate to preserve structured concurrency) to
 * [onError] instead of letting it escape.
 *
 * Shared by the boundaries this codebase has decided must never crash the
 * process or drop work silently:
 * [BasculeApplication.onCreate], [BootReceiver][com.ventouxlabs.bascule.service.BootReceiver]'s
 * launched coroutine, [ScanBroadcastReceiver][com.ventouxlabs.bascule.ble.ScanBroadcastReceiver]'s,
 * and [ScaleSessionWorker][com.ventouxlabs.bascule.ble.session.ScaleSessionWorker]'s
 * `setForeground` call. Each of those previously reimplemented the same
 * `catch (CancellationException) { throw error }` guard by hand.
 *
 * Deliberately does not log or classify [Error] specially itself — callers
 * that care about that distinction (an [Error] says something about device
 * state, not about the feature that happened to be running) do it in their
 * own [onError], since what "specially" means differs by call site.
 */
internal inline fun <T> runNonCancelling(onError: (Throwable) -> T, block: () -> T): T =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onError(error)
    }
