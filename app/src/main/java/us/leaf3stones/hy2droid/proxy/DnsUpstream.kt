package us.leaf3stones.hy2droid.proxy

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.data.DnsWire

/**
 * DNS over the tunnel, for cache misses.
 *
 * One persistent TCP connection to the core's SOCKS5 listener, CONNECTed to the
 * upstream resolver on port 53 — DNS over TCP (RFC 1035) needs only a two-byte
 * length prefix, so this avoids re-implementing SOCKS5 UDP framing and keeps
 * large answers (truncated over UDP) intact. Exchanges are serialised: a miss is
 * a few milliseconds and Android sends few queries, so pipelining would buy
 * nothing but a response-matching bug.
 *
 * The connection dies with every network handover; the next miss reconnects.
 */
class DnsUpstream(
    private val servers: List<String>,
    private val socksPort: Int,
    private val dnsPort: Int = Tun.CACHE_DNS_PORT,
) {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val mutex = Mutex()

    suspend fun exchange(query: ByteArray): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                try {
                    if (open()) send(query)?.let { return@withContext it }
                } catch (e: IOException) {
                    // Dead connection (handover, core restart): rebuild it once.
                    if (attempt == 1) Log.d(TAG, "DNS upstream failed: ${e.message}")
                }
                close()
            }
            null
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun open(): Boolean {
        if (socket?.isClosed == false) return true
        for (server in servers) {
            try {
                val candidate = Socket()
                candidate.soTimeout = TIMEOUT_MS
                candidate.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort),
                    TIMEOUT_MS,
                )
                val outs = candidate.getOutputStream()
                val ins = candidate.getInputStream()
                handshake(ins, outs, server)
                socket = candidate
                input = ins
                output = outs
                return true
            } catch (e: IOException) {
                Log.d(TAG, "Could not reach the resolver via SOCKS5: $server (${e.message})")
            }
        }
        return false
    }

    /** The SOCKS5 CONNECT to `server:dnsPort`; throws when the proxy refuses. */
    private fun handshake(ins: InputStream, outs: OutputStream, server: String) {
        outs.write(byteArrayOf(0x05, 0x01, 0x00))
        outs.flush()
        val greeting = ByteArray(2)
        readFully(ins, greeting, 0, 2) ?: throw IOException("SOCKS5 greeting truncated")
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            throw IOException("SOCKS5 method rejected")
        }
        val address = InetAddress.getByName(server).address
        if (address.size != 4) throw IOException("Only IPv4 resolvers are supported")
        val request = ByteArray(10)
        request[0] = 0x05
        request[1] = 0x01
        request[2] = 0x00
        request[3] = 0x01
        System.arraycopy(address, 0, request, 4, 4)
        request[8] = (dnsPort ushr 8).toByte()
        request[9] = dnsPort.toByte()
        outs.write(request)
        outs.flush()
        val reply = ByteArray(4)
        readFully(ins, reply, 0, 4) ?: throw IOException("SOCKS5 reply truncated")
        if (reply[1] != 0x00.toByte()) throw IOException("SOCKS5 CONNECT failed: ${reply[1]}")
        // Skip BND.ADDR + BND.PORT (the length depends on the address type).
        val skip = when (reply[3].toInt()) {
            0x01 -> 4 + 2
            0x04 -> 16 + 2
            0x03 -> {
                val length = ByteArray(1)
                readFully(ins, length, 0, 1) ?: throw IOException("SOCKS5 reply truncated")
                (length[0].toInt() and 0xFF) + 2
            }
            else -> throw IOException("SOCKS5 reply has an unknown address type")
        }
        readFully(ins, ByteArray(skip), 0, skip) ?: throw IOException("SOCKS5 reply truncated")
    }

    private fun send(query: ByteArray): ByteArray? {
        val outs = output ?: return null
        val ins = input ?: return null
        outs.write((query.size ushr 8).toByte().toInt())
        outs.write(query.size.toByte().toInt())
        outs.write(query)
        outs.flush()
        val id = DnsWire.id(query)
        // Only one exchange is in flight (see the mutex), so anything else is a
        // stale answer to a request that already timed out.
        while (true) {
            val header = ByteArray(2)
            readFully(ins, header, 0, 2) ?: return null
            val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            if (length <= 0) return null
            val body = ByteArray(length)
            readFully(ins, body, 0, length) ?: return null
            if (DnsWire.id(body) == id) return body
        }
    }

    private fun readFully(ins: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean? {
        var read = 0
        while (read < length) {
            val n = try {
                ins.read(buffer, offset + read, length - read)
            } catch (e: java.net.SocketTimeoutException) {
                return null
            }
            if (n < 0) return null
            read += n
        }
        return true
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val TAG = "LocalDns"
    }
}
