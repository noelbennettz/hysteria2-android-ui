package us.leaf3stones.hy2droid.proxy

import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.Notification
import android.text.format.Formatter
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.leaf3stones.hy2droid.BuildConfig
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.ConfigFile
import us.leaf3stones.hy2droid.data.HysteriaConfigBuilder
import us.leaf3stones.hy2droid.data.LanRanges
import us.leaf3stones.hy2droid.data.ProfileStore
import us.leaf3stones.hy2droid.data.Redact
import us.leaf3stones.hy2droid.data.SettingsStore
import us.leaf3stones.hy2droid.ui.MainActivity
import java.io.File
import java.net.InetAddress
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
    private external fun startTun2socks(configPath: String, fd: Int): Boolean
    private external fun stopTun2socks(): Boolean

    /**
     * Traffic counters of the tunnel engine, in order:
     * `[tx packets, tx bytes, rx packets, rx bytes]`.
     */
    private external fun getTun2socksStats(): LongArray

    private var tunInterface: ParcelFileDescriptor? = null
    private var coreProcess: Process? = null
    private var logDrainThread: Thread? = null
    private var statsJob: Job? = null
    private var monitorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var shuttingDown = false

    /** True while *we* are tearing the core down, so its exit is not an error. */
    @Volatile
    private var coreStoppedByUs = false

    /**
     * Set when the user swipes the ongoing notification away (Android 14+ allows
     * that). Updating a dismissed notification would bring it straight back, so
     * throughput refreshes stop until the next connect.
     */
    @Volatile
    private var notificationDismissed = false

    /** Clears [notificationDismissed] when the notification is dismissed. */
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_NOTIFICATION_DISMISSED) {
                notificationDismissed = true
            }
        }
    }

    /**
     * Profile id carried by the start intent. Authoritative over the stored
     * selection: the user can tap another row in the moment between "config
     * written" and "service starts", and the tunnel must report the profile it
     * actually built.
     */
    @Volatile
    private var requestedProfileId: String? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            // SupervisorJob only keeps siblings alive; without this handler an
            // unexpected exception (notification, JNI, DataStore) would take the
            // whole app down instead of surfacing as a failed tunnel.
            Log.e(TAG, "Unhandled error in the VPN service", error)
            runCatching {
                updateState(TunnelState.Error(error.message ?: getString(R.string.status_error)))
            }
            runCatching { teardown() }
            runCatching { stopForegroundSafely() }
            stopSelf()
        },
    )

    /**
     * Serialises "bring the tunnel up" against anything else. Without it a
     * double tap (app + Quick Settings tile) let two starts run concurrently:
     * the `tunInterface` guard is only set after `establish()`, so both would
     * create a TUN and a core, and the loser's teardown left an orphaned core
     * behind.
     */
    private val tunnelMutex = Mutex()

    /** The start attempt in flight, so a stop can cancel it instead of racing it. */
    private var startJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                // Also reached through startForegroundService (notification action,
                // Quick Settings tile), so satisfy the foreground contract first.
                startForegroundSafely(getString(R.string.status_disconnecting))
                requestStop()
                return START_NOT_STICKY
            }

            ACTION_START_VPN -> {
                // An explicit start (app or tile), possibly for a specific profile.
                requestedProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                startForegroundSafely()
                launchStart()
                return START_STICKY
            }

            // System-initiated start: always-on VPN, or a restart after the process
            // was killed. Always-on means the system owns the lifecycle — refusing
            // to come up here only makes Android show "always-on VPN is not
            // connected" and retry, so connect whenever a profile exists.
            SYS_SYSTEM_VPN, null -> {
                // No request to honour: whatever is selected is the answer.
                requestedProfileId = null
                startForegroundSafely()
                launchStart()
                return START_STICKY
            }

            else -> {
                Log.w(TAG, "Unrecognized intent action: ${intent.action}")
                return START_NOT_STICKY
            }
        }
    }

    /** Begin a start attempt unless one is already running. */
    private fun launchStart() {
        if (startJob?.isActive == true) {
            Log.d(TAG, "A start is already in flight; ignoring duplicate request")
            return
        }
        startJob = serviceScope.launch { startTunnel() }
    }

    /**
     * Cancel a start that has not finished yet, then tear down. Runs off the main
     * thread: `teardown()` does JNI work and kills a process, which is not
     * something to block `onStartCommand` with.
     */
    private fun requestStop() {
        val pending = startJob
        startJob = null
        serviceScope.launch {
            pending?.cancelAndJoin()
            // Same lock as the start: otherwise a stop can interleave with a switch
            // and tear down the tunnel that start just created.
            tunnelMutex.withLock { stopTunnel() }
        }
    }

    /** Establish the tunnel, launch the core and start tun2socks. Runs on IO. */
    private suspend fun startTunnel() = tunnelMutex.withLock { startTunnelLocked() }

    private suspend fun startTunnelLocked() {
        // The profile this start is for: the one the app asked for (exact, even if
        // the user moved the selection on in the meantime), else whatever is
        // selected. Both the switch decision and the "in use" marker use it.
        val target = requestedProfileId ?: activeProfileId()
        if (tunInterface != null) {
            // A second start while connected means "bring up the selected profile",
            // which is how a switched profile takes effect. Same profile: ignore.
            if (target == null || target == TunnelStatus.activeProfileId.value) {
                Log.d(TAG, "Tunnel already running; ignoring duplicate start")
                return
            }
            Log.i(TAG, "Switching profile; restarting the tunnel")
            // Tear down without stopSelf(): the service must stay alive to build
            // the new tunnel (and the foreground notification stays posted).
            teardownTunnel()
        }
        shuttingDown = false
        coreStoppedByUs = false
        notificationDismissed = false
        Log.i(TAG, "Starting tunnel · Hysteria core ${BuildConfig.CORE_VERSION}")
        updateState(TunnelState.Connecting)

        val settings = SettingsStore(applicationContext)
        val excluded = try {
            settings.excluded.first()
        } catch (_: Exception) {
            emptySet()
        }
        val dnsServers = try {
            if (settings.customDnsEnabled.first()) {
                listOfNotNull(
                    settings.customDnsServer.first().takeIf { it.isNotBlank() },
                    settings.customDnsServer2.first().takeIf { it.isNotBlank() },
                )
            } else {
                // No custom DNS: don't inject a resolver, let DNS use the upstream
                // (hysteria server egress) instead of a hardcoded public resolver.
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        val bypassLan = try {
            settings.bypassLan.first()
        } catch (_: Exception) {
            false
        }

        val mtu = try {
            settings.mtu.first()
        } catch (_: Exception) {
            SettingsStore.DEFAULT_MTU
        }

        val blockRules = try {
            settings.blockRules.first()
        } catch (_: Exception) {
            emptyList()
        }

        val configFile = File(filesDir, HYSTERIA_CONFIG_FILE)
        when (ensureConfigFile(configFile)) {
            ConfigPrep.Ready -> Unit

            ConfigPrep.NoProfile -> {
                // Nothing stored to connect to (the tile can be tapped with an
                // empty list): undo the "connecting" state quietly instead of
                // flashing an error notification.
                Log.i(TAG, "No profile stored; nothing to connect")
                updateState(TunnelState.Idle)
                stopForegroundSafely()
                stopSelf()
                return
            }

            ConfigPrep.Failed -> {
                fail(getString(R.string.error_no_config))
                return
            }
        }

        val tun2socksFile = File(filesDir, Tun2socksConfig.FILE_NAME)
        try {
            tun2socksFile.writeText(Tun2socksConfig.build(blockRules, mtu))
        } catch (e: Exception) {
            fail(getString(R.string.error_tun2socks_config, e.message ?: ""))
            return
        }

        // A stop may have landed while we were reading settings; cancellation only
        // takes effect at suspension points, so check explicitly before creating
        // anything the user did not ask for any more.
        if (shuttingDown) {
            Log.i(TAG, "Stop requested before the interface came up; aborting")
            stopForegroundSafely()
            stopSelf()
            return
        }

        val pfd = establishTunnel(excluded, dnsServers, bypassLan, mtu)
        if (pfd == null) {
            fail(getString(R.string.error_establish))
            return
        }
        tunInterface = pfd
        registerNetworkTracking()

        val process = launchCore(configFile)
        if (process == null) {
            teardown()
            fail(getString(R.string.error_core_launch))
            return
        }
        coreProcess = process
        monitorJob = serviceScope.launch { monitorCore(process) }

        val started = try {
            startTun2socks(tun2socksFile.absolutePath, pfd.fd)
        } catch (e: Throwable) {
            teardown()
            fail(getString(R.string.error_tun2socks_start, e.message ?: ""))
            return
        }
        if (!started) {
            teardown()
            fail(getString(R.string.error_tun2socks_failed))
            return
        }

        updateState(TunnelState.Connected)
        TunnelStatus.setActiveProfile(target)
        startStatsPolling()
        refreshNotification()
        Log.d(TAG, "Tunnel established")
    }

    /** The profile the running config was built for, for the UI's "in use" marker. */
    private suspend fun activeProfileId(): String? = try {
        ProfileStore(applicationContext).selectedId.first()
    } catch (_: Exception) {
        null
    }

    /** Set the tunnel state and ask the Quick Settings tile to refresh its appearance. */
    private fun updateState(state: TunnelState) {
        TunnelStatus.setState(state)
        requestTileRefresh()
    }

    private fun requestTileRefresh() {
        try {
            TileService.requestListeningState(
                applicationContext,
                ComponentName(applicationContext, VpnTileService::class.java),
            )
        } catch (_: Throwable) {
            // Tile not added / not listening; nothing to do.
        }
    }

    private fun establishTunnel(
        excluded: Set<String>,
        dnsServers: List<String>,
        bypassLan: Boolean,
        mtu: Int,
    ): ParcelFileDescriptor? = try {
        val builder = Builder()
            .setMtu(mtu)
            .addAddress(Tun.IPV4, Tun.PREFIX4)
            .addAddress(Tun.IPV6, Tun.PREFIX6)
        for (dns in dnsServers) {
            if (dns.isBlank()) continue
            try {
                builder.addDnsServer(dns)
            } catch (_: Exception) {
                // Not a numeric address; skip.
            }
        }
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)
        if (bypassLan) applyLanBypass(builder)
        builder.addDisallowedApplication(packageName)
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

    /**
     * Keep local-network traffic out of the tunnel (NAS, printers, router pages,
     * casting discovery). `excludeRoute` only exists from Android 13, and the
     * settings switch is hidden below that, so this guard is belt and braces.
     */
    private fun applyLanBypass(builder: Builder) {
        if (Build.VERSION.SDK_INT < LanRanges.MIN_API) {
            Log.w(TAG, "LAN bypass needs Android ${LanRanges.MIN_API}+; ignoring")
            return
        }
        var applied = 0
        for ((address, prefixLength) in LanRanges.ALL) {
            try {
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefixLength))
                applied++
            } catch (e: Exception) {
                // One bad range must never take the whole tunnel down.
                Log.w(TAG, "excludeRoute failed for $address/$prefixLength", e)
            }
        }
        Log.i(TAG, "LAN bypass: excluded $applied of ${LanRanges.ALL.size} local ranges")
    }

    /**
     * Keep the tun pinned to the physical network the device is actually using.
     * Without this, a Wi-Fi ↔ mobile handover can leave the tunnel routing into a
     * network that is gone (no traffic until the app is toggled).
     */
    private fun registerNetworkTracking() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateUnderlying(network)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    updateUnderlying(network)
                }
            }

            override fun onLost(network: Network) = Unit
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not track the underlying network", e)
        }
    }

    private fun unregisterNetworkTracking() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    /** Point the tunnel at [network] when it is a real (non-VPN) transport. */
    private fun updateUnderlying(network: Network) {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val caps = manager.getNetworkCapabilities(network) ?: return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
        try {
            setUnderlyingNetworks(arrayOf(network))
            Log.i(TAG, "Underlying network: $network")
        } catch (e: Exception) {
            Log.w(TAG, "setUnderlyingNetworks failed", e)
        }
    }

    /** Outcome of preparing `hysteria.yaml` before the core is launched. */
    private enum class ConfigPrep { Ready, NoProfile, Failed }

    /** Ensure `hysteria.yaml` exists, rebuilding it from the selected profile if missing. */
    private suspend fun ensureConfigFile(configFile: File): ConfigPrep {
        if (configFile.exists()) return ConfigPrep.Ready
        val store = ProfileStore(applicationContext)
        val profiles = try {
            store.profiles.first()
        } catch (_: Exception) {
            emptyList()
        }
        if (profiles.isEmpty()) return ConfigPrep.NoProfile
        val selectedId = try {
            store.selectedId.first()
        } catch (_: Exception) {
            null
        }
        val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
        val pmtudDisabled = try {
            SettingsStore(applicationContext).disablePathMtuDiscovery.first()
        } catch (_: Exception) {
            false
        }
        val yaml = HysteriaConfigBuilder.build(profile, disablePathMtuDiscovery = pmtudDisabled)
        return if (ConfigFile.write(configFile, yaml)) ConfigPrep.Ready else ConfigPrep.Failed
    }

    private fun launchCore(configFile: File): Process? = try {
        val binary = File(applicationInfo.nativeLibraryDir, CORE_BINARY)
        val process = ProcessBuilder(binary.absolutePath, "-c", configFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        logDrainThread = Thread {
            // The core log is what the diagnostics export ships; without it a
            // failed handshake leaves no trace once logcat rolls over.
            val coreLog = try {
                CoreLog.open(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Could not open the core log file", e)
                null
            }
            try {
                process.inputStream.use { input ->
                    val scanner = Scanner(input)
                    while (!shuttingDown && scanner.hasNextLine()) {
                        val line = scanner.nextLine()
                        // The core prints a share URI containing the auth + obfs
                        // password; never let that reach logcat or the log file.
                        val safe = Redact.logLine(line)
                        Log.v(CORE_TAG, safe)
                        coreLog?.write(safe)
                    }
                }
            } catch (_: Exception) {
                // Reading from a closed pipe during shutdown is expected.
            } finally {
                coreLog?.close()
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
        if (shuttingDown || coreStoppedByUs) return
        // A reconnect can already own the tunnel by the time this fires; tearing
        // down then would kill the *new* core and TUN.
        if (coreProcess !== process) {
            Log.i(TAG, "A superseded core exited with code $exit; ignoring")
            return
        }
        Log.w(TAG, "Core exited unexpectedly with code $exit")
        serviceScope.launch {
            teardown()
            updateState(TunnelState.Error(getString(R.string.error_core_exited, exit)))
            stopForegroundSafely()
            stopSelf()
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var ticks = 0
            var lastTx = 0L
            var lastRx = 0L
            var lastAt = SystemClock.elapsedRealtime()
            while (isActive) {
                // Nothing displays the counters unless the user asked for them, so
                // do not touch the native counters (nor wake the UI) at 1 Hz for
                // nobody.
                val showTraffic = try {
                    SettingsStore(applicationContext).showTraffic.first()
                } catch (_: Exception) {
                    false
                }
                if (showTraffic) {
                    val values = try {
                        getTun2socksStats()
                    } catch (_: Throwable) {
                        null
                    }
                    if (values != null && values.size >= 4) {
                        // [tx packets, tx bytes, rx packets, rx bytes]
                        TunnelStatus.setStats(tx = values[1], rx = values[3])
                        if (++ticks >= NOTIFICATION_REFRESH_TICKS) {
                            val now = SystemClock.elapsedRealtime()
                            val seconds = ((now - lastAt) / 1000.0).coerceAtLeast(1.0)
                            val txRate = ((values[1] - lastTx) / seconds).toLong().coerceAtLeast(0)
                            val rxRate = ((values[3] - lastRx) / seconds).toLong().coerceAtLeast(0)
                            lastTx = values[1]
                            lastRx = values[3]
                            lastAt = now
                            ticks = 0
                            refreshNotification(txRate, rxRate)
                        }
                    }
                } else {
                    ticks = 0
                    lastTx = 0
                    lastRx = 0
                    lastAt = SystemClock.elapsedRealtime()
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    /** Release the tunnel but keep the service (and its notification) alive. */
    private fun teardownTunnel() {
        shuttingDown = true
        updateState(TunnelState.Disconnecting)
        teardown()
        updateState(TunnelState.Idle)
    }

    private fun stopTunnel() {
        teardownTunnel()
        stopForegroundSafely()
        stopSelf()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        teardown()
        updateState(TunnelState.Error(message))
        stopForegroundSafely()
        stopSelf()
    }

    /** Release every native/OS resource. Idempotent. */
    private fun teardown() {
        statsJob?.cancel()
        statsJob = null
        monitorJob?.cancel()
        monitorJob = null
        // Mark the core exit as expected *before* killing it, so monitorCore does
        // not overwrite the real error message (or the Idle state) afterwards.
        coreStoppedByUs = true
        unregisterNetworkTracking()
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
        TunnelStatus.setActiveProfile(null)
        // The config carries the auth secret and is rebuilt by the next connect
        // (MainViewModel writes it) or by ensureConfigFile on an always-on
        // restart, so it does not need to sit in filesDir while the tunnel is
        // down — nor be picked up by a cloud backup. The temp file from an
        // interrupted atomic write holds the same secret, so it goes too.
        ConfigFile.delete(File(filesDir, HYSTERIA_CONFIG_FILE))
    }

    override fun onRevoke() {
        shuttingDown = true
        requestStop()
        super.onRevoke()
    }

    override fun onCreate() {
        super.onCreate()
        // Dynamic receiver: a manifest-declared implicit broadcast is not delivered
        // any more, and this one only ever comes from our own notification.
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(ACTION_NOTIFICATION_DISMISSED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        shuttingDown = true
        unregisterReceiverSafely()
        teardown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun unregisterReceiverSafely() {
        try {
            unregisterReceiver(dismissReceiver)
        } catch (_: Exception) {
            // Already unregistered (onCreate never ran, or it is registered twice).
        }
    }

    private fun startForegroundSafely(text: String = getString(R.string.status_connecting)) {
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
            buildNotification(text),
            type,
        )
    }

    private fun stopForegroundSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // getForegroundService, not getService: the background start restriction
        // applies to a notification action as well, and the service calls
        // startForeground() first thing anyway.
        val stopIntent = PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, Hysteria2VpnService::class.java).setAction(ACTION_STOP_VPN),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(ACTION_NOTIFICATION_DISMISSED).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_connection)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .addAction(R.drawable.ic_vpn_connection, getString(R.string.action_disconnect), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Update the ongoing notification with the current state, and — when the
     * rates are known — the throughput. Updating at the 1 s stats cadence would
     * make the notification shade flicker, so the caller throttles it.
     */
    private fun refreshNotification(txRate: Long = -1, rxRate: Long = -1) {
        if (shuttingDown) return
        // The user swiped it away; re-posting would just make it come back.
        if (notificationDismissed) return
        // POST_NOTIFICATIONS is a runtime permission from Android 13: without the
        // explicit check the update would throw (and lint rightly complains).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val text = when (TunnelStatus.state.value) {
            TunnelState.Connected -> if (txRate >= 0 && rxRate >= 0) {
                getString(
                    R.string.notification_with_speed,
                    getString(R.string.status_connected),
                    Formatter.formatShortFileSize(this, rxRate) + "/s",
                    Formatter.formatShortFileSize(this, txRate) + "/s",
                )
            } else {
                getString(R.string.status_connected)
            }

            TunnelState.Connecting -> getString(R.string.status_connecting)
            TunnelState.Disconnecting -> getString(R.string.status_disconnecting)
            else -> return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Throwable) {
            // Channel disabled / blocked by policy: the tunnel is fine without it.
            Log.w(TAG, "Could not update the notification", e)
        }
    }

    companion object {
        init {
            System.loadLibrary("tun2socks")
        }

        const val ACTION_START_VPN = "us.leaf3stones.hy2droid.proxy.ACTION_START_VPN"
        const val ACTION_STOP_VPN = "us.leaf3stones.hy2droid.proxy.ACTION_STOP_VPN"

        /** Optional profile id on [ACTION_START_VPN]; falls back to the selection. */
        const val EXTRA_PROFILE_ID = "us.leaf3stones.hy2droid.proxy.EXTRA_PROFILE_ID"

        /** Broadcast by the notification's delete intent when the user swipes it away. */
        private const val ACTION_NOTIFICATION_DISMISSED =
            "us.leaf3stones.hy2droid.proxy.ACTION_NOTIFICATION_DISMISSED"

        /** The action Android sends when starting the service for always-on VPN. */
        private const val SYS_SYSTEM_VPN = "android.net.VpnService"

        /** The active Hysteria config file name under `filesDir`. */
        const val HYSTERIA_CONFIG_FILE = "hysteria.yaml"

        private const val CORE_BINARY = "libhysteria.so"
        private const val STATS_INTERVAL_MS = 1000L

        /** Stats ticks between two notification refreshes (5 s at 1 s cadence). */
        private const val NOTIFICATION_REFRESH_TICKS = 5
        private const val CHANNEL_ID = "hy2droid_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "Hysteria2VpnService"
        private const val CORE_TAG = "hysteria"
    }
}


