package us.leaf3stones.hy2droid.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.Notification
import android.text.format.Formatter
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.SettingsStore
import java.io.File
import java.util.Scanner

/**
 * The [VpnService] that owns the TUN interface and the Hysteria 2 core process.
 *
 * The native `libtun2socks.so` binds its JNI symbols to this exact class name and
 * to the three `external` methods below, so neither the fully-qualified name nor
 * the native method signatures may change without rebuilding the .so.
 *
 * Threading: `onStartCommand` only starts the foreground notification (which must
 * be immediate) and hands the rest to an IO coroutine, so no blocking work ever
 * runs on the main thread.
 */
class Hysteria2VpnService : VpnService() {

    // --- JNI surface (do NOT rename/relocate: symbols are bound to this FQCN) ---
    private external fun startTun2socks(configPath: String, fd: Int)
    private external fun stopTun2socks()
    private external fun getTun2socksStats(): LongArray

    private var tunInterface: ParcelFileDescriptor? = null
    private var coreProcess: Process? = null
    private var logDrainThread: Thread? = null
    private var statsJob: Job? = null
    private var monitorJob: Job? = null

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var latestSpeedText = ""

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                stopTunnel()
                return START_NOT_STICKY
            }

            ACTION_START_VPN, null -> {
                // A null intent means the system restarted us after the process was
                // killed while connected: resume from the persisted config files.
                startForegroundSafely()
                serviceScope.launch { startTunnel() }
                return START_STICKY
            }

            else -> {
                Log.w(TAG, "Unrecognized intent action: ${intent.action}")
                return START_NOT_STICKY
            }
        }
    }

    /** Establish the tunnel, launch the core and start tun2socks. Runs on IO. */
    private suspend fun startTunnel() {
        if (tunInterface != null) {
            Log.d(TAG, "Tunnel already running; ignoring duplicate start")
            return
        }
        shuttingDown = false
        TunnelStatus.setState(TunnelState.Connecting)

        val excluded = try {
            SettingsStore(applicationContext).excluded.first()
        } catch (_: Exception) {
            emptySet()
        }

        val configFile = File(filesDir, HYSTERIA_CONFIG_FILE)
        if (!configFile.exists()) {
            fail(getString(R.string.error_no_config))
            return
        }

        val tun2socksFile = File(filesDir, Tun2socksConfig.FILE_NAME)
        try {
            tun2socksFile.writeText(Tun2socksConfig.CONTENT)
        } catch (e: Exception) {
            fail(getString(R.string.error_tun2socks_config, e.message ?: ""))
            return
        }

        val pfd = establishTunnel(excluded)
        if (pfd == null) {
            fail(getString(R.string.error_establish))
            return
        }
        tunInterface = pfd

        val process = launchCore(configFile)
        if (process == null) {
            teardown()
            fail(getString(R.string.error_core_launch))
            return
        }
        coreProcess = process
        monitorJob = serviceScope.launch { monitorCore(process) }

        try {
            startTun2socks(tun2socksFile.absolutePath, pfd.fd)
        } catch (e: Throwable) {
            teardown()
            fail(getString(R.string.error_tun2socks_start, e.message ?: ""))
            return
        }

        TunnelStatus.setState(TunnelState.Connected)
        startStatsPolling()
        refreshNotification()
        Log.d(TAG, "Tunnel established")
    }

    private fun establishTunnel(excluded: Set<String>): ParcelFileDescriptor? = try {
        val builder = Builder()
            .setMtu(MTU)
            .addAddress(TUN_IPV4, TUN_PREFIX)
            .addDnsServer(DNS_SERVER)
            .addRoute("0.0.0.0", 0)
            .addDisallowedApplication(packageName)
        for (pkg in excluded) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: Exception) {
                // Package may have been uninstalled between selection and connect.
            }
        }
        builder.establish()
    } catch (e: Exception) {
        Log.e(TAG, "establish() failed", e)
        null
    }

    private fun launchCore(configFile: File): Process? = try {
        val binary = File(applicationInfo.nativeLibraryDir, CORE_BINARY)
        val process = ProcessBuilder(binary.absolutePath, "-c", configFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        logDrainThread = Thread {
            try {
                process.inputStream.use { input ->
                    val scanner = Scanner(input)
                    while (!shuttingDown && scanner.hasNextLine()) {
                        val line = scanner.nextLine()
                        // The core prints a share URI containing the auth + obfs
                        // password; never let that reach logcat.
                        val safe = if (line.contains("hysteria2://") || line.contains("obfs-password")) {
                            "[core log line redacted: contains credentials]"
                        } else {
                            line
                        }
                        Log.v(CORE_TAG, safe)
                    }
                }
            } catch (_: Exception) {
                // Reading from a closed pipe during shutdown is expected.
            }
        }.apply {
            isDaemon = true
            start()
        }
        process
    } catch (e: Exception) {
        Log.e(TAG, "Failed to exec the Hysteria core", e)
        null
    }

    private suspend fun monitorCore(process: Process) {
        val exit = try {
            process.waitFor()
        } catch (_: InterruptedException) {
            return
        }
        if (shuttingDown) return
        Log.w(TAG, "Core exited unexpectedly with code $exit")
        serviceScope.launch {
            teardown()
            TunnelStatus.setState(TunnelState.Error(getString(R.string.error_core_exited, exit)))
            stopForegroundSafely()
            stopSelf()
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var prevTx = 0L
            var prevRx = 0L
            var tick = 0
            while (isActive) {
                val values = try {
                    getTun2socksStats()
                } catch (_: Throwable) {
                    null
                }
                if (values != null && values.size >= 2) {
                    val tx = values[0]
                    val rx = values[1]
                    TunnelStatus.setStats(tx, rx)
                    val dTx = (tx - prevTx).coerceAtLeast(0L)
                    val dRx = (rx - prevRx).coerceAtLeast(0L)
                    prevTx = tx
                    prevRx = rx
                    latestSpeedText = "\u2191 " +
                        Formatter.formatFileSize(this@Hysteria2VpnService, dTx) + "/s   \u2193 " +
                        Formatter.formatFileSize(this@Hysteria2VpnService, dRx) + "/s"
                    if (tick++ % 2 == 0) refreshNotification()
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun stopTunnel() {
        shuttingDown = true
        TunnelStatus.setState(TunnelState.Disconnecting)
        teardown()
        TunnelStatus.setState(TunnelState.Idle)
        stopForegroundSafely()
        stopSelf()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        teardown()
        TunnelStatus.setState(TunnelState.Error(message))
        stopForegroundSafely()
        stopSelf()
    }

    /** Release every native/OS resource. Idempotent. */
    private fun teardown() {
        statsJob?.cancel()
        statsJob = null
        monitorJob?.cancel()
        monitorJob = null
        try {
            stopTun2socks()
        } catch (_: Throwable) {
            // Native may not have been started yet.
        }
        try {
            tunInterface?.close()
        } catch (_: Throwable) {
            // Already closed.
        }
        tunInterface = null
        try {
            coreProcess?.destroy()
        } catch (_: Throwable) {
            // Already dead.
        }
        coreProcess = null
        logDrainThread?.interrupt()
        logDrainThread = null
        TunnelStatus.resetStats()
    }

    override fun onRevoke() {
        shuttingDown = true
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        shuttingDown = true
        teardown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.status_connecting)),
            type,
        )
    }

    private fun stopForegroundSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_connection)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /** Update the ongoing notification with the current state / speed (no-op when idle). */
    private fun refreshNotification() {
        if (shuttingDown) return
        val text = when (TunnelStatus.state.value) {
            TunnelState.Connected ->
                if (latestSpeedText.isBlank()) {
                    getString(R.string.status_connected)
                } else {
                    getString(R.string.status_connected) + "  \u00b7  " + latestSpeedText
                }
            TunnelState.Connecting -> getString(R.string.status_connecting)
            TunnelState.Disconnecting -> getString(R.string.status_disconnecting)
            else -> return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        init {
            System.loadLibrary("tun2socks")
        }

        const val ACTION_START_VPN = "us.leaf3stones.hy2droid.proxy.ACTION_START_VPN"
        const val ACTION_STOP_VPN = "us.leaf3stones.hy2droid.proxy.ACTION_STOP_VPN"

        /** The active Hysteria config file name under `filesDir`. */
        const val HYSTERIA_CONFIG_FILE = "hysteria.yaml"

        private const val CORE_BINARY = "libhysteria.so"
        private const val MTU = 1500
        private const val TUN_IPV4 = "10.0.88.88"
        private const val TUN_PREFIX = 16
        private const val DNS_SERVER = "1.0.0.1"
        private const val STATS_INTERVAL_MS = 1000L
        private const val CHANNEL_ID = "hy2droid_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "Hysteria2VpnService"
        private const val CORE_TAG = "hysteria"
    }
}


