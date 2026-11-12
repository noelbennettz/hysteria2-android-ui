package us.leaf3stones.hy2droid.proxy

/**
 * Decides what the tunnel does about the underlying (non-VPN) network.
 *
 * The Hysteria core is a separate process whose QUIC session is bound to the
 * path it was started on: when Wi-Fi disappears, `setUnderlyingNetworks()` only
 * re-pins the TUN, the core keeps sending into a dead interface until its idle
 * timeout (30 s) expires. There is no rebind API, so a handover has to restart
 * the core — and this is the part that decides *when* that is warranted.
 *
 * The input is deliberately a snapshot ("this is the set of usable
 * transports right now"), not a stream of Android callbacks. A handover fires
 * `onAvailable` and `onLost` in no fixed order, and often several times; only
 * the settled set is meaningful. That is why there is no cooldown here: the
 * caller debounces the set, and a real second handover must restart too —
 * a cooldown that *drops* the event instead of deferring it leaves the core on
 * a dead path until the next unrelated network change.
 *
 * Pure Kotlin (no Android types) so the policy and the token format are
 * unit-tested on the JVM — see `NetworkSwitchPolicyTest`.
 */
enum class NetworkAction {
    /** Nothing worth doing (unknown event, or the same network as before). */
    Ignore,

    /** Re-pin the TUN to the new network; the core is still fine. */
    Adopt,

    /** Re-pin, then restart the core: its QUIC path is gone. */
    Restart,
}

/**
 * The state machine behind [NetworkAction]. One instance per tunnel: [reset]
 * when the tunnel goes down, so the next connect starts from a clean slate.
 *
 * Restarts are **rate limited**, and a throttled one is *deferred*, never
 * dropped: a phone that keeps handing over (a train, a lift, a flapping Wi-Fi
 * link) would otherwise tear down every session every few seconds, which is
 * worse than the 30 s idle timeout this replaced. [pendingDelayMs] tells the
 * caller how long to wait before asking again with [takeDueRestart].
 */
class NetworkSwitchPolicy(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val restartIntervalMs: Long = DEFAULT_RESTART_INTERVAL_MS,
) {

    /** The set of transports the core was last started (or adopted) on. */
    var adopted: String? = null
        private set

    /** True between "no usable network" and the network coming back. */
    private var lost = false

    /** A change that was adopted but still owes a restart. */
    private var pending: String? = null

    private var lastRestartAt = Long.MIN_VALUE / 2

    /** Feed the current snapshot (null = nothing usable). */
    fun onCurrent(token: String?): NetworkAction {
        // No network at all: keep what we had. The next usable network is
        // adopted (or restarted on) when it shows up, and tearing the core down
        // here would only make the recovery slower.
        if (token == null) {
            lost = true
            return NetworkAction.Ignore
        }

        val previous = adopted ?: run {
            adopted = token
            lost = false
            pending = null
            return NetworkAction.Adopt
        }
        if (previous == token && !lost) {
            pending = null
            return NetworkAction.Ignore
        }

        // Either the transports changed, or the path died and came back on the
        // same one (airplane mode off, Wi-Fi re-association): the QUIC session
        // did not survive the gap even though the token looks identical.
        return if (clock() - lastRestartAt < restartIntervalMs) {
            // Adopt now (the pin is free), restart later: dropping the event
            // would leave the core on a dead path until something else happens.
            adopted = token
            lost = false
            pending = token
            NetworkAction.Adopt
        } else {
            adoptAndMarkRestarted(token)
            NetworkAction.Restart
        }
    }

    /** Milliseconds until the deferred restart is due, or null when none is owed. */
    fun pendingDelayMs(): Long? {
        if (pending == null) return null
        return (restartIntervalMs - (clock() - lastRestartAt))
            .coerceIn(0, restartIntervalMs)
    }

    /** True when a deferred restart is due; consumes it. */
    fun takeDueRestart(): Boolean {
        val wanted = pending ?: return false
        if (clock() - lastRestartAt < restartIntervalMs) return false
        pending = null
        adoptAndMarkRestarted(wanted)
        return true
    }

    fun reset() {
        adopted = null
        lost = false
        pending = null
        lastRestartAt = Long.MIN_VALUE / 2
    }

    private fun adoptAndMarkRestarted(token: String) {
        adopted = token
        lost = false
        pending = null
        lastRestartAt = clock()
    }

    companion object {
        /** At most one restart per this interval; extra handovers are deferred. */
        const val DEFAULT_RESTART_INTERVAL_MS = 10_000L

        /** Fold `NetworkCapabilities.transportTypes` into a stable bitmask. */
        fun transportMask(types: Collection<Int>): Int =
            types.fold(0) { acc, type -> if (type in 0..30) acc or (1 shl type) else acc }

        /**
         * One transport as a token: the bitmask plus the interface name, e.g.
         * `2:wlan0` (Wi-Fi) or `1:rmnet_data0` (cellular). The interface name
         * separates a Wi-Fi P2P/hotspot link from the station link and keeps
         * two simultaneously active transports from collapsing into one token.
         */
        fun transportToken(mask: Int, interfaceName: String?): String =
            "$mask:${interfaceName ?: "unknown"}"

        /**
         * The snapshot the policy compares: the sorted, de-duplicated set of
         * live transports, or null when none is usable. Ordering matters — the
         * callbacks that produce the parts arrive in arbitrary order.
         */
        fun networkToken(parts: Collection<String>): String? =
            parts.filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .joinToString("+")
                .ifEmpty { null }
    }
}
