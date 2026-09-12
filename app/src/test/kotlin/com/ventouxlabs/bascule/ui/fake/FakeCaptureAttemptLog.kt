package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.diagnostics.CaptureAttemptLog
import com.ventouxlabs.bascule.diagnostics.CaptureOutcome
import com.ventouxlabs.bascule.diagnostics.LastCaptureAttempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory stand-in for `SharedPreferencesCaptureAttemptLog`. The durability
 * the real one provides is covered by
 * `com.ventouxlabs.bascule.diagnostics.CaptureAttemptLogTest` against real
 * SharedPreferences; what a ViewModel test needs is only that the flow emits.
 */
class FakeCaptureAttemptLog(initial: LastCaptureAttempt? = null) : CaptureAttemptLog {

    private val state = MutableStateFlow(initial)

    override val last: StateFlow<LastCaptureAttempt?> = state.asStateFlow()

    override fun record(outcome: CaptureOutcome, technicalReason: String?, atMillis: Long) {
        state.value = LastCaptureAttempt(atMillis, outcome, technicalReason)
    }
}
