package com.ventouxlabs.bascule.service

/**
 * Who currently wants [BridgeForegroundService] running.
 *
 * This service produced three bugs of one shape, and the shape was not
 * `startId` being hard to use — it was having *several* stop paths, each
 * caller reasoning independently about whether its own stop should still
 * take effect. Every fix taught one more caller to get `startId` right,
 * which is why the third instance was introduced by the fix for the second.
 * See `docs/prp/06-owner-aware-bridge-lifecycle.md`.
 *
 * Naming the callers is what lets exactly one function decide to stop:
 * [BridgeForegroundService.releaseOwner], which stops only when the last
 * owner lets go.
 */
enum class BridgeOwner {
    /**
     * The "Always-on foreground fallback" toggle. Wants the scan held open
     * until the user turns it off, and is the only owner restored after a
     * sticky restart.
     */
    ALWAYS_ON,

    /**
     * A bounded "Weigh now" window. Releases itself when the window ends,
     * and is deliberately *not* restored after a process death.
     */
    WEIGH_NOW,
}

/**
 * The live service's ownership, as much of it as anything outside the service
 * needs.
 *
 * Exists as an interface rather than a direct [BridgeForegroundService]
 * reference for the same reason [BridgeForegroundService.enqueuerFactory] and
 * `activeAddressProvider` are injectable seams: `AndroidBridgeServiceController`
 * has to be unit-testable in a lane where a real service instance is not
 * constructible.
 */
interface BridgeOwnership {
    /**
     * Who is holding the bridge open right now. Empty means nothing wants it
     * and it is already stopping.
     */
    val owners: Set<BridgeOwner>

    /**
     * Give up [owner]'s claim. Releasing a claim nobody holds is a no-op, and
     * the service stops only when this empties the set.
     */
    fun releaseOwner(owner: BridgeOwner)
}
