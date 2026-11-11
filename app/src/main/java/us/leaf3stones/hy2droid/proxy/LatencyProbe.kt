package us.leaf3stones.hy2droid.proxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.data.HysteriaConfigBuilder
import us.leaf3stones.hy2droid.data.Profile
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID

/**
 * Measures one profile's round-trip latency by actually talking to the node.
 *
 * A Hysteria 2 server only listens on UDP/QUIC, so a TCP handshake would measure
 * nothing. Instead a throwaway core instance is started for [profile] (its own
 * SOCKS/HTTP ports, `lazy` so it does not connect until asked), a single
 * `GET …/generate_204` goes through its local HTTP proxy, and the time to the
 * first response byte is reported. The hostname is resolved by the *server*, so
 * a polluted local resolver cannot skew the result.
 *
 * The probe is deliberately cheap and disposable: the process is killed and the
 * temporary config deleted as soon as one number is in hand.
 */
class LatencyProbe(private val context: Context) {

    /** Returns the measured latency in milliseconds, or `null` when the probe failed. */
    suspend fun measure(profile: Profile): Int? = withContext(Dispatchers.IO) {
        if (profile.server.isBlank()) return@withContext null
        val ports = freePorts()
        if (ports == null) {
            Log.w(TAG, "No free local ports for the latency probe")
            return@withContext null
        }
        val (socksPort, httpPort) = ports
        val configFile = File(context.cacheDir, "latency-${UUID.randomUUID()}.yaml")
        var process: Process? = null
        try {
            configFile.writeText(HysteriaConfigBuilder.build(profile, socksPort, httpPort))
            val binary = File(context.applicationInfo.nativeLibraryDir, CORE_BINARY)
            val proc = ProcessBuilder(binary.absolutePath, "-c", configFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            process = proc
            drainQuietly(proc)
            if (!awaitListening(proc, httpPort)) {
                Log.w(TAG, "Latency probe core never listened on 127.0.0.1:$httpPort")
                return@withContext null
            }
            for (target in PROBE_TARGETS) {
                val ms = roundTrip(httpPort, target)
                if (ms != null) return@withContext ms
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Latency probe failed: ${e.message}")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // Already gone.
            }
            try {
                configFile.delete()
            } catch (_: Throwable) {
                // Cache dir is wiped by the system anyway.
            }
        }
    }

    /** Time from request write to first response byte, or `null` on error/timeout. */
    private fun roundTrip(httpPort: Int, target: String): Int? = try {
        val host = URI(target).host
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(LOOPBACK, httpPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val request = buildString {
                append("GET ").append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: Hy2droid-latency\r\n")
                append("Connection: close\r\n\r\n")
            }
            val output = socket.getOutputStream()
            val started = System.nanoTime()
            output.write(request.toByteArray())
            output.flush()
            val firstByte = socket.getInputStream().read()
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (firstByte < 0) null else elapsed.toInt()
        }
    } catch (_: Exception) {
        null
    }

    /** Block until the local proxy port accepts connections, the core dies, or we time out. */
    private fun awaitListening(process: Process, port: Int): Boolean {
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return false
            try {
                Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS) }
                return true
            } catch (_: Exception) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        return false
    }

    /** The core is chatty; read its output so the pipe never fills up, and drop it. */
    private fun drainQuietly(process: Process) {
        Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) >= 0) {
                        // Discarded: the probe's own output is not worth logging.
                    }
                }
            } catch (_: Exception) {
                // Expected when the process is destroyed.
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Two free loopback ports, bound at the same time so they differ. */
    private fun freePorts(): Pair<Int, Int>? = try {
        ServerSocket(0).use { first ->
            ServerSocket(0).use { second -> first.localPort to second.localPort }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not reserve local ports: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "LatencyProbe"
        private const val CORE_BINARY = "libhysteria.so"
        private const val LOOPBACK = "127.0.0.1"

        /**
         * `generate_204` endpoints, tried in order. Both are served by the node's
         * egress, so a working node answers one of them in about one RTT.
         */
        private val PROBE_TARGETS = listOf(
            "http://www.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
        )

        private const val STARTUP_TIMEOUT_MS = 4_000L
        private const val READ_TIMEOUT_MS = 3_000
        private const val CONNECT_TIMEOUT_MS = 800
        private const val POLL_INTERVAL_MS = 50L
    }
}
