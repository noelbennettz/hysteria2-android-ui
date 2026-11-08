package us.leaf3stones.hy2droid.proxy

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.ui.MainActivity

/** Quick Settings tile that toggles the tunnel with a single tap. */
class VpnTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        // While the panel is open the tile is bound, and asking the system to
        // re-bind (requestListeningState) is a no-op. Collect the state here so
        // the tile follows the tunnel even with Quick Settings open.
        stateJob?.cancel()
        stateJob = scope.launch {
            TunnelStatus.state.collect { updateTile() }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = TunnelStatus.state.value
        tile.state = if (state.isActive()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = statusText(state)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_connection)
        tile.updateTile()
    }

    private fun statusText(state: TunnelState): String = getString(
        when (state) {
            TunnelState.Idle -> R.string.status_idle
            TunnelState.Connecting -> R.string.status_connecting
            TunnelState.Connected -> R.string.status_connected
            TunnelState.Disconnecting -> R.string.status_disconnecting
            is TunnelState.Error -> R.string.status_error
        },
    )

    private fun TunnelState.isActive(): Boolean =
        this is TunnelState.Connected || this is TunnelState.Connecting || this is TunnelState.Disconnecting

    override fun onClick() {
        super.onClick()
        try {
            if (TunnelStatus.state.value.isActive()) {
                startService(
                    Intent(this, Hysteria2VpnService::class.java)
                        .setAction(Hysteria2VpnService.ACTION_STOP_VPN),
                )
            } else if (VpnService.prepare(this) != null) {
                // VPN permission not granted yet: open the app so the user can grant it.
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            } else {
                // The service rebuilds the config from the last selected profile if needed.
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, Hysteria2VpnService::class.java)
                        .setAction(Hysteria2VpnService.ACTION_START_VPN),
                )
            }
        } catch (_: Throwable) {
            // Never crash the Quick Settings panel.
        }
        updateTile()
    }
}
