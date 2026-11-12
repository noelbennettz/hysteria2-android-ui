package us.leaf3stones.hy2droid.proxy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    private var networkJob: Job? = null

    /**
     * The config file the running core was started from. A network handover
     * restarts the process, and it has to come back with the same profile.
     */
    private var hysteriaConfigFile: File? = null

    /** The in-tunnel DNS cache/resolver, when the tunnel was started with one. */
    private var localDns: LocalDns? = null


    /** Live, usable (non-VPN) transports; the value is a [NetworkSwitchPolicy] token. */
    private val networkTokenCache = ConcurrentHashMap<Network, String>()

    /**
     * The current set of usable transports, as one token (null = no network).
     * Snapshot, not event: a handover fires several callbacks in no fixed order,
     * and only the settled value is actionable — see [NetworkSwitchPolicy].
     */
    private val networkTokens = MutableStateFlow<String?>(null)

    private val networkSwitch = NetworkSwitchPolicy()

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

    /**
     * When the user last asked us to stop. A system-initiated start shortly after
     * that is always-on VPN bringing the tunnel back (the setting itself is not
     * readable from an app since Android 12).
     */
    @Volatile
    private var lastUserStopAt = 0L

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
                lastUserStopAt = SystemClock.elapsedRealtime()
                startForegroundSafely(getString(R.string.status_disconnecting))
                requestStop()
                return START_NOT_STICKY
            }

            ACTION_START_VPN -> {
                // An explicit start (app or tile), possibly for a specific profile.
                requestedProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                TunnelStatus.setRestartedBySystem(false)
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
                // Right after a user-initiated stop this can only be always-on VPN,
                // which is the one way to learn about it on Android 12+.
                val sinceStop = SystemClock.elapsedRealtime() - lastUserStopAt
                if (lastUserStopAt > 0 && sinceStop in 0..ALWAYS_ON_RESTART_WINDOW_MS) {
                    Log.i(TAG, "Restarted by the system ${sinceStop}ms after a stop; always-on VPN")
                    TunnelStatus.setRestartedBySystem(true)
                }
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
        val mode = try {
            dnsMode(
                customEnabled = settings.customDnsEnabled.first(),
                cacheEnabled = settings.dnsCacheEnabled.first(),
            )
        } catch (_: Exception) {
            DnsMode.Forwarded
        }
        // Claim port 53 *before* the interface exists: the TUN address cannot be
        // bound yet, and a device that refuses the port has to fall back to plain
        // forwarding before the resolver list is baked into the tunnel.
        val cache = if (mode == DnsMode.Cache) {
            LocalDns(SettingsStore.DEFAULT_DNS_SERVERS, Tun.SOCKS_PORT)
                .takeIf { it.bind() }
                ?: run {
                    Log.w(TAG, "Local DNS cache unavailable; forwarding queries instead")
                    null
                }
        } else {
            null
        }
        localDns = cache
        val dnsServers = try {
            // Always at least one resolver. Android has no "no DNS means use the
            // tunnel" mode: with an empty list the VPN network has no resolver at
            // all, so resolution falls back to the underlying network's DNS (a
            // leak — the queries leave over Wi-Fi, not through the server).
            dnsServersFor(
                mode,
                settings.customDnsServer.first(),
                settings.customDnsServer2.first(),
                cacheAvailable = cache != null,
            )
        } catch (_: Exception) {
            SettingsStore.DEFAULT_DNS_SERVERS
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
        hysteriaConfigFile = configFile
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
        // The resolver may only claim its address now that the interface is up.
        localDns?.attach(Tun.CACHE_DNS)
        registerNetworkTracking()
        startNetworkTracking()

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
        // The resolver answers on the TUN address, which only exists now, and its
        // upstream needs the core's SOCKS port — so start it last.
        localDns?.start(serviceScope)
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
     * Watch the physical networks the tunnel runs over.
     *
     * The Hysteria core is a separate process, so nothing Android does to the
     * TUN can fix a core whose QUIC path vanished: a handover has to restart it.
     * This only collects *what* is usable; [networkJob] decides *when*.
     */
    private fun registerNetworkTracking() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        if (networkCallback != null) return
        networkTokenCache.clear()
        networkTokens.value = null
        networkSwitch.reset()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = record(network)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                record(network)

            override fun onLost(network: Network) {
                networkTokenCache.remove(network)
                publishToken()
            }

            /**
             * Add or drop one transport. `VALIDATED` is the gate: a network that
             * is merely "available" (cellular still attaching, captive portal)
             * would give the restarted core a handshake that cannot work yet.
             */
            private fun record(network: Network) {
                val caps = manager.getNetworkCapabilities(network)
                if (caps == null ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    networkTokenCache.remove(network)
                    publishToken()
                    return
                }
                networkTokenCache[network] = NetworkSwitchPolicy.transportToken(
                    // `NetworkCapabilities.getTransportTypes()` is not in the
                    // public SDK at compileSdk 34, so ask per transport.
                    NetworkSwitchPolicy.transportMask(
                        TRACKED_TRANSPORTS.filter { caps.hasTransport(it) },
                    ),
                    manager.getLinkProperties(network)?.interfaceName,
                )
                publishToken()
            }
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

    private fun publishToken() {
        // Callbacks arrive on binder threads; assigning a StateFlow is safe and
        // never blocks, and the latest snapshot is by definition the truth.
        networkTokens.value = NetworkSwitchPolicy.networkToken(networkTokenCache.values)
    }

    /**
     * Feed the settled snapshot to [networkSwitch].
     *
     * The settle window is the debounce for the burst a single handover produces
     * (`onLost` + `onAvailable` in either order, sometimes three deep). It is a
     * settle, not a cooldown: a second real handover right afterwards is still
     * honoured — dropping it would leave the core on a dead path until some
     * unrelated event showed up.
     */
    private fun startNetworkTracking() {
        networkJob?.cancel()
        networkJob = serviceScope.launch {
            var lastSeen = networkTokens.value
            while (isActive) {
                networkTokens.first { it != lastSeen }
                delay(HANDOVER_SETTLE_MS)
                val settled = networkTokens.value
                lastSeen = settled
                // Same lock as start/stop: a restart must never interleave with
                // an in-flight connect or a user disconnect.
                tunnelMutex.withLock { applyNetworkToken(settled) }
            }
        }
    }

    private fun unregisterNetworkTracking() {
        networkJob?.cancel()
        networkJob = null
        networkTokenCache.clear()
        networkTokens.value = null
        networkSwitch.reset()
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    private suspend fun applyNetworkToken(token: String?) {
        if (shuttingDown || tunInterface == null) return
        if (token == null) {
            // Nothing usable: show it instead of a stale "Connected". The core is
            // kept alive — a returning network is adopted below, and killing it
            // here would only make the recovery slower.
            if (TunnelStatus.state.value == TunnelState.Connected) {
                updateState(TunnelState.Connecting)
                refreshNotification()
            }
            return
        }
        when (networkSwitch.onCurrent(token)) {
            NetworkAction.Adopt -> {
                pinUnderlying()
                restoreConnected()
            }

            NetworkAction.Restart -> {
                pinUnderlying()
                updateState(TunnelState.Connecting)
                refreshNotification()
                restartCore()
                // restartCore() switches to Error on its own failures; a
                // successful restart leaves the state at Connecting.
                restoreConnected()
            }

            // Same transports as before. If the transport came back unchanged
            // (airplane mode off), the tunnel is up again without a restart.
            NetworkAction.Ignore -> restoreConnected()
        }

    }

    /**
     * Let Android route the tunnel over the system's default network.
     *
     * The previous code pinned whichever network's callback fired last, which
     * with Wi-Fi and cellular both up could pin the tunnel to cellular while
     * Wi-Fi was the default — and made every capability update look like a
     * network change. Null means "the network the system picked", which follows
     * handovers by itself; the restart above is what actually matters.
     */
    private fun pinUnderlying() {
        try {
            setUnderlyingNetworks(null)
            Log.i(TAG, "Underlying network: system default (${networkSwitch.adopted})")
        } catch (e: Exception) {
            Log.w(TAG, "setUnderlyingNetworks failed", e)
        }
    }

    /** Show Connected again after a loss/adopt cycle, without touching Error/Idle. */
    private fun restoreConnected() {
        if (TunnelStatus.state.value != TunnelState.Connecting) return
        updateState(TunnelState.Connected)
        refreshNotification()
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

    /**
     * Replace the core process after a transport handover.
     *
     * Hysteria 2 has no rebind API, so the only way to get a QUIC session onto
     * the new path is a new process; the TUN and hev stay up, so the SOCKS port
     * is dark for the ~0.1–0.3 s the core needs to start and bind. hev's
     * sessions seen during that window are reset, and the apps behind them
     * retry — a short, visible failure instead of the 30 s idle-timeout black
     * hole this replaces.
     */
    private suspend fun restartCore() {
        if (shuttingDown) return
        val old = coreProcess ?: return
        val configFile = hysteriaConfigFile
        if (configFile == null) {
            fail(getString(R.string.error_core_launch))
            return
        }
        // Make sure the old process is *gone* before a new one binds the same
        // port: a core that refuses to die would otherwise leave a fresh process
        // that cannot listen, which looks exactly like a dead tunnel.
        //
        // Order matters: `monitorJob` is blocked inside `process.waitFor()`,
        // which is not a cancellation point, so joining it before the process
        // is dead would hang here forever.
        coreStoppedByUs = true
        try {
            old.destroy()
        } catch (_: Throwable) {
            // Already dead.
        }
        if (!old.waitFor(CORE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Core ignored SIGTERM; killing it")
            try {
                old.destroyForcibly()
            } catch (_: Throwable) {
                // Already dead.
            }
            if (!old.waitFor(CORE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Core survived SIGKILL; not restarting")
                fail(getString(R.string.error_core_launch))
                return
            }
        }
        // Now unblocked; join so the old monitor cannot race the flag reset.
        monitorJob?.cancelAndJoin()
        monitorJob = null

        val fresh = launchCore(configFile)
        if (fresh == null) {
            fail(getString(R.string.error_core_launch))
            return
        }
        coreProcess = fresh
        // Reset *before* the monitor starts: a crash of the new core is a real
        // failure and must not be swallowed by the flag that hid the old exit.
        coreStoppedByUs = false
        monitorJob = serviceScope.launch { monitorCore(fresh) }
        Log.i(TAG, "Core restarted after network switch")
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
        // The resolver outlives the interface only as a bound socket; closing it
        // first keeps a reconnect from finding port 53 still held.
        localDns?.stop()
        localDns = null

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
        // minSdk is 30, so notification channels always exist.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
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

        /**
         * How long a handover may keep changing before the tunnel acts on it.
         * One handover produces several callbacks (`onLost` + `onAvailable`, in
         * either order); acting on the first one would restart the core onto a
         * network that is about to be replaced.
         */
        private const val HANDOVER_SETTLE_MS = 600L

        /** Grace period for the old core to exit before it is killed. */
        private const val CORE_STOP_TIMEOUT_MS = 2_000L

        /**
         * Transports the network token is built from. `NetworkCapabilities` only
         * exposes `hasTransport()` in the public SDK at this compileSdk, so the
         * set is enumerated; the ones that can carry a phone's egress are enough
         * to tell Wi-Fi from cellular (and Ethernet from a hotspot link).
         */
        private val TRACKED_TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
        )


        private const val CORE_BINARY = "libhysteria.so"
        private const val STATS_INTERVAL_MS = 1000L

        /** Stats ticks between two notification refreshes (5 s at 1 s cadence). */
        private const val NOTIFICATION_REFRESH_TICKS = 5

        /**
         * How soon after a user-initiated stop a system-initiated start still
         * counts as always-on VPN bringing the tunnel back.
         */
        private const val ALWAYS_ON_RESTART_WINDOW_MS = 10_000L
        private const val CHANNEL_ID = "hy2droid_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "Hysteria2VpnService"
        private const val CORE_TAG = "hysteria"
    }
}
