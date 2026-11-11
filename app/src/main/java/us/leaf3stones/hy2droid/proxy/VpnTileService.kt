package us.leaf3stones.hy2droid.proxy

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.ProfileStore
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

    // The Intent overload of startActivityAndCollapse() is deprecated on API 34 and
    // the PendingIntent overload does not exist below it, so the pre-34 branch is
    // the only option there — hence the lint suppression.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        try {
            when {
                TunnelStatus.state.value.isActive() -> {
                    // The service already runs in the foreground, but plain startService()
                    // trips the background-start restriction on Android 12+; the
                    // foreground variant keeps this path working from Quick Settings.
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, Hysteria2VpnService::class.java)
                            .setAction(Hysteria2VpnService.ACTION_STOP_VPN),
                    )
                }

                VpnService.prepare(this) != null -> {
                    // VPN permission not granted yet: open the app so the user can grant it.
                    // A launcher intent so the app lands on its home screen.
                    val intent = Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                        startActivityAndCollapse(pi)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(intent)
                    }
                }

                // Reading the stored profiles is I/O, so the start happens off the tap.
                // The service rebuilds the config from the last selected profile.
                else -> scope.launch { connectSelectedProfile() }
            }
        } catch (e: Throwable) {
            // Never crash the Quick Settings panel; the log line is what tells us
            // the toggle was swallowed (the tile would just stay out of sync).
            Log.w(TAG, "Quick Settings toggle failed", e)
        }
        updateTile()
    }

    /**
     * Start the tunnel for the last selected profile. With an empty list the tap
     * is a no-op: no foreground notification, no error state, nothing to undo.
     */
    private suspend fun connectSelectedProfile() {
        val hasProfile = withContext(Dispatchers.IO) {
            try {
                ProfileStore(applicationContext).profiles.first().isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }
        if (!hasProfile) {
            Log.i(TAG, "No profile stored; ignoring the tile tap")
            return
        }
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, Hysteria2VpnService::class.java)
                    .setAction(Hysteria2VpnService.ACTION_START_VPN),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Quick Settings toggle failed", e)
        }
        updateTile()
    }

    private companion object {
        private const val TAG = "VpnTileService"
    }
}
