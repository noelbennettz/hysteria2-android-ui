package us.leaf3stones.hy2droid.proxy

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.ui.MainActivity

/** Quick Settings tile that toggles the tunnel with a single tap. */
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = isActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_connection)
        tile.updateTile()
    }

    private fun isActive(): Boolean {
        val state = TunnelStatus.state.value
        return state is TunnelState.Connected ||
            state is TunnelState.Connecting ||
            state is TunnelState.Disconnecting
    }

    override fun onClick() {
        super.onClick()
        try {
            if (isActive()) {
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
