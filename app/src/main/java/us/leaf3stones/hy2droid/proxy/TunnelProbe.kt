package us.leaf3stones.hy2droid.proxy

import android.util.Log
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.data.HysteriaConfigBuilder
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/** One line of the tunnel report; the UI maps it to a label. */
enum class BenchStep { Name, ConnectCold, ConnectWarm, Ttfb, Throughput, Direct }

/** One measured step: [value] is the number, [detail] the error when [ok] is false. */
data class BenchResult(
    val step: BenchStep,
    val value: String,
    val ok: Boolean,
    val detail: String? = null,
)

/**
 * Measures the live tunnel from inside the app, the way a browser uses it.
 *
 * Every step goes through the running core's local listeners (SOCKS5, the HTTP
 * proxy), because the app's own UID is excluded from the VPN: a direct socket
 * would measure the phone's connection, not the tunnel. That is also why the
 * probe must not "just query the pushed resolver": the packet would leave over
 * Wi-Fi and nothing would answer.
 *
 * The order is the diagnosis. The first step is a *name*: a SOCKS5 CONNECT with
 * a domain, which is what a browser asks for here (the engine answers DNS
 * locally and forwards the name, so the server resolves it). Comparing it with
 * the connect-to-an-IP steps isolates the server's resolver from the tunnel: if
 * name and IP cost the same, resolution is free; if only the name is slow, the
 * server's resolver is. A slow *cold* connect blames the QUIC session start (the
 * "first page after idle takes seconds" case); a slow warm connect blames the
 * path; a fast connect with a slow first byte blames the server's egress.
 */
class TunnelProbe(
    private val socksPort: Int = Tun.SOCKS_PORT,
    private val httpPort: Int = HysteriaConfigBuilder.DEFAULT_HTTP_PORT,
) {

    suspend fun run(onStep: (BenchResult) -> Unit): List<BenchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<BenchResult>()
            fun step(result: BenchResult) {
                results += result
                onStep(result)
            }
            step(timed(BenchStep.Name) { socksConnect(TARGET_HOST, TARGET_PORT) })
            step(timed(BenchStep.ConnectCold) { socksConnect(IP_HOST, TARGET_PORT) })
            step(timed(BenchStep.ConnectWarm) { socksConnect(IP_HOST, TARGET_PORT) })
            step(timed(BenchStep.Ttfb) { firstByte() })
            step(download(THROUGHPUT_BYTES))
            // The app's own UID is outside the VPN, so this one goes straight out
            // over Wi-Fi/cellular: it is the baseline the tunnel number is
            // compared against.
            step(directDownload(THROUGHPUT_BYTES))
            results
        }

    /** Run [block], timing it in milliseconds; failure becomes a failed step. */
    private fun timed(step: BenchStep, block: () -> Unit): BenchResult = try {
        val started = System.nanoTime()
        block()
        BenchResult(step, "${(System.nanoTime() - started) / 1_000_000} ms", ok = true)
    } catch (e: Exception) {
        Log.d(TAG, "$step failed: ${e.message}")
        BenchResult(step, "failed", ok = false, detail = e.message ?: e.javaClass.simpleName)
    }

    /** SOCKS5 CONNECT by name, so the *server* resolves it. */
    private fun socksConnect(host: String, port: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), socksPort), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                throw IOException("SOCKS5 method rejected")
            }
            out.write(if (isIpv4(host)) ipv4Request(host, port) else domainRequest(host, port))
            out.flush()
            val reply = ByteArray(4)
            readFully(input, reply)
            if (reply[1] != 0x00.toByte()) throw IOException("SOCKS5 CONNECT failed: ${reply[1]}")
        }
    }

    /** A CONNECT that carries the name, i.e. the server resolves it. */
    private fun domainRequest(host: String, port: Int): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        return ByteArray(7 + name.size).also { request ->
            request[0] = 0x05
            request[1] = 0x01                        // CONNECT
            request[2] = 0x00
            request[3] = 0x03                        // domain name
            request[4] = name.size.toByte()
            System.arraycopy(name, 0, request, 5, name.size)
            request[5 + name.size] = (port ushr 8).toByte()
            request[6 + name.size] = port.toByte()
        }
    }

    /** A CONNECT for a literal address: no resolver is involved anywhere. */
    private fun ipv4Request(host: String, port: Int): ByteArray {
        val octets = host.split('.').map { it.toInt() }
        return ByteArray(10).also { request ->
            request[0] = 0x05
            request[1] = 0x01                        // CONNECT
            request[2] = 0x00
            request[3] = 0x01                        // IPv4 address
            for (i in 0..3) request[4 + i] = octets[i].toByte()
            request[8] = (port ushr 8).toByte()
            request[9] = port.toByte()
        }
    }

    private fun isIpv4(host: String): Boolean =
        host.split('.').let { parts ->
            parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }

    /** Time to the first body byte of a real HTTPS request through the tunnel. */
    private fun firstByte() {
        withTls { tls ->
            writeRequest(tls)
            if (tls.inputStream.read() < 0) throw IOException("no response body")
        }
    }

    private fun download(bytes: Int): BenchResult = try {
        var total = 0L
        val started = System.nanoTime()
        withTls { tls ->
            writeRequest(tls)
            val body = tls.inputStream
            val buffer = ByteArray(16 * 1024)
            while (total < bytes) {
                val want = minOf(buffer.size.toLong(), bytes - total).toInt()
                val read = body.read(buffer, 0, want)
                if (read < 0) break
                total += read
            }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val mbps = if (ms > 0) total * 8.0 / ms / 1000.0 else 0.0
        BenchResult(
            BenchStep.Throughput,
            "$total bytes, ${String.format(Locale.US, "%.1f", mbps)} Mbps",
            ok = total > 0,
        )
    } catch (e: Exception) {
        Log.d(TAG, "download failed: ${e.message}")
        BenchResult(BenchStep.Throughput, "failed", ok = false, detail = e.message)
    }

    /** Same download, but straight out of the phone (the app is outside the VPN). */
    private fun directDownload(bytes: Int): BenchResult = try {
        var total = 0L
        val started = System.nanoTime()
        val raw = Socket()
        var tls: SSLSocket? = null
        try {
            raw.connect(InetSocketAddress(TARGET_HOST, TARGET_PORT), TIMEOUT_MS)
            raw.soTimeout = TIMEOUT_MS
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, TARGET_HOST, TARGET_PORT, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.soTimeout = TIMEOUT_MS
            tls.startHandshake()
            writeRequest(tls)
            val body = tls.inputStream
            val buffer = ByteArray(16 * 1024)
            while (total < bytes) {
                val want = minOf(buffer.size.toLong(), bytes - total).toInt()
                val read = body.read(buffer, 0, want)
                if (read < 0) break
                total += read
            }
        } finally {
            runCatching { tls?.close() ?: raw.close() }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val mbps = if (ms > 0) total * 8.0 / ms / 1000.0 else 0.0
        BenchResult(
            BenchStep.Direct,
            "$total bytes, ${String.format(Locale.US, "%.1f", mbps)} Mbps",
            ok = total > 0,
        )
    } catch (e: Exception) {
        Log.d(TAG, "direct download failed: ${e.message}")
        BenchResult(BenchStep.Direct, "failed", ok = false, detail = e.message)
    }

    /**
     * Open a TLS connection to [TARGET_HOST] through the core's HTTP proxy
     * (CONNECT) and hand it to [block]. This is the path a browser takes.
     */
    private inline fun withTls(block: (SSLSocket) -> Unit) {
        val plain = Socket()
        var tls: SSLSocket? = null
        try {
            plain.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), httpPort), TIMEOUT_MS)
            plain.soTimeout = TIMEOUT_MS
            val authority = "$TARGET_HOST:$TARGET_PORT"
            plain.getOutputStream().apply {
                write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n\r\n".toByteArray())
                flush()
            }
            // Drain the proxy's response up to the blank line.
            val input = plain.getInputStream()
            while (true) {
                val line = readLine(input) ?: throw IOException("proxy closed the CONNECT")
                if (line.isEmpty()) break
            }
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, TARGET_HOST, TARGET_PORT, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.soTimeout = TIMEOUT_MS
            tls.startHandshake()
            block(tls)
        } finally {
            runCatching { tls?.close() ?: plain.close() }
        }
    }

    /** Send the request and leave the stream at the body (headers skipped). */
    private fun writeRequest(socket: SSLSocket) {
        val request = buildString {
            append("GET ").append(DOWNLOAD_PATH).append(" HTTP/1.1\r\n")
            append("Host: ").append(TARGET_HOST).append("\r\n")
            append("User-Agent: Hy2droid-tunnel-test\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.outputStream.write(request.toByteArray())
        socket.outputStream.flush()
        while (true) {
            val line = readLine(socket.inputStream) ?: throw IOException("no response")
            if (line.isEmpty()) break
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) throw IOException("truncated")
            read += n
        }
    }

    private companion object {
        const val TAG = "TunnelProbe"
        const val TIMEOUT_MS = 5_000

        /** The core listens on IPv4 loopback only; `getLoopbackAddress()` can be ::1. */
        const val LOOPBACK = "127.0.0.1"
        const val TARGET_HOST = "speed.cloudflare.com"

        /** A literal for the connect steps: Cloudflare, never resolved. */
        const val IP_HOST = "1.1.1.1"
        const val TARGET_PORT = 443
        const val DOWNLOAD_PATH = "/__down?bytes=5242880"
        const val THROUGHPUT_BYTES = 5 * 1024 * 1024
    }
}
