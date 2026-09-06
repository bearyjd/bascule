package com.ventouxlabs.bascule.diagnostics

/**
 * What to tell a user who never opens the app, when capture needs them.
 *
 * The Scale tab already shows the last attempt in words, but only to
 * someone looking at it. Two outcomes cannot fix themselves and are
 * invisible otherwise: the scale asking to pair (a system request that
 * expires in seconds if nobody notices) and the app not being set up to
 * capture at all. Everything else — an idle listen, a scale that dropped
 * the link, a transient miss — resolves on its own and must not nag.
 */
data class CaptureAttention(val title: String, val body: String)

/** Pure, so the notifier's wiring is the only Android-bound piece. */
fun attentionFor(outcome: CaptureOutcome): CaptureAttention? = when (outcome) {
    CaptureOutcome.NEEDS_PAIRING -> CaptureAttention(
        title = "Your scale wants to pair with this phone",
        body = "Accept the Bluetooth pairing request the next time you step on, " +
            "or step on now and watch for it.",
    )
    CaptureOutcome.NOT_READY -> CaptureAttention(
        title = "Bascule can't capture right now",
        body = "Check that Bluetooth is on, permissions are granted, and a scale profile is active.",
    )
    CaptureOutcome.CAPTURED,
    CaptureOutcome.NO_READING,
    CaptureOutcome.MISSED_THE_WINDOW,
    CaptureOutcome.INCOMPATIBLE,
    CaptureOutcome.FAILED,
    CaptureOutcome.IDLE,
    -> null
}

sealed interface AttentionTransition {
    data class Post(val attention: CaptureAttention) : AttentionTransition
    data object Clear : AttentionTransition
    data object None : AttentionTransition
}

/**
 * Decides on each recorded attempt whether the notification changes.
 * Posted once per *distinct* problem — not once per attempt, because the
 * pairing case recurs every session until the user acts, and a fresh
 * notification each time would be exactly the nag that trains people to
 * mute the app. Cleared the moment an attempt no longer needs them.
 */
fun attentionTransition(previous: CaptureOutcome?, current: CaptureOutcome): AttentionTransition {
    val now = attentionFor(current)
    val before = previous?.let(::attentionFor)
    return when {
        now != null && now != before -> AttentionTransition.Post(now)
        now == null && before != null -> AttentionTransition.Clear
        else -> AttentionTransition.None
    }
}
