package us.leaf3stones.hy2droid.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The lifecycle state of the system VPN tunnel. */
sealed interface TunnelState {
    /** No tunnel is up. */
    data object Idle : TunnelState

    /** The tunnel is being brought up. */
    data object Connecting : TunnelState

    /** The tunnel is up and proxying traffic. */
    data object Connected : TunnelState

    /** The tunnel is being torn down. */
    data object Disconnecting : TunnelState

    /** The tunnel failed with a user-facing [message]. */
    data class Error(val message: String) : TunnelState
}

/** Throttled traffic counters (bytes) for the current session. */
data class TunnelStats(val tx: Long = 0, val rx: Long = 0)

/**
 * A single process-wide source of truth for tunnel state and stats, written by
 * [Hysteria2VpnService] and observed by the UI. Using a shared [StateFlow] (as
 * opposed to the old static observer set) means activity re-creation never leaks
 * observers and the UI always reflects the latest state.
 */
object TunnelStatus {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    /**
     * Id of the profile the current tunnel was built for, or `null` when no
     * tunnel is up. The list keeps its own selection, so the UI needs this to
     * tell "selected" apart from "actually in use".
     */
    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    /**
     * Set when the system brought the tunnel back on its own, which means
     * always-on VPN is enabled. Reading `always_on_vpn_app` is forbidden from
     * Android 12 (SecurityException), so this is detected behaviourally: a
     * system-initiated start right after a user-initiated stop.
     */
    private val _restartedBySystem = MutableStateFlow(false)
    val restartedBySystem: StateFlow<Boolean> = _restartedBySystem.asStateFlow()

    internal fun setState(state: TunnelState) {
        _state.value = state
    }

    internal fun setRestartedBySystem(value: Boolean) {
        _restartedBySystem.value = value
    }

    internal fun setActiveProfile(id: String?) {
        _activeProfileId.value = id
    }

    internal fun setStats(tx: Long, rx: Long) {
        _stats.value = TunnelStats(tx = tx, rx = rx)
    }

    internal fun resetStats() {
        _stats.value = TunnelStats()
    }
}
