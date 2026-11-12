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
 */
class NetworkSwitchPolicy {

    /** The set of transports the core was last started (or adopted) on. */
    var adopted: String? = null
        private set

    /** True between "no usable network" and the network coming back. */
    private var lost = false

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
            return NetworkAction.Adopt
        }
        if (previous == token && !lost) return NetworkAction.Ignore

        // Either the transports changed, or the path died and came back on the
        // same one (airplane mode off, Wi-Fi re-association): the QUIC session
        // did not survive the gap even though the token looks identical.
        adopted = token
        lost = false
        return NetworkAction.Restart
    }

    fun reset() {
        adopted = null
        lost = false
    }


    companion object {
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
