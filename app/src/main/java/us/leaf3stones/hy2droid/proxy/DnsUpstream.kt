package us.leaf3stones.hy2droid.proxy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.data.DnsWire
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * DNS over the tunnel, for cache misses.
 *
 * Each [Link] is one persistent TCP connection to the core's SOCKS5 listener,
 * CONNECTed to an upstream resolver on port 53 — DNS over TCP (RFC 1035) needs
 * only a two-byte length prefix, so this avoids re-implementing SOCKS5 UDP
 * framing and keeps large answers (truncated over UDP) intact.
 *
 * A browser's first page load fires a dozen queries at once, so two properties
 * matter more than raw speed: a small **pool** (one serialised connection made
 * every query wait behind the slowest one) and a **short** read timeout (a
 * resolver that stops answering costs 2.5 s once instead of stalling the page).
 * A failed attempt closes its link and rotates the server, so the next query
 * fails over by itself.
 *
 * Connections die with every network handover and core restart; the next miss
 * reconnects.
 */
class DnsUpstream(
    private val servers: List<String>,
    private val socksPort: Int,
    private val dnsPort: Int = Tun.CACHE_DNS_PORT,
) {

    private val permits = Semaphore(MAX_CONNECTIONS)
    private val idle = ConcurrentLinkedDeque<Link>()
    private val open = mutableSetOf<Link>()
    private var nextServer = 0

    suspend fun exchange(query: ByteArray): ByteArray? = permits.withPermit {
        withContext(Dispatchers.IO) {
            val link = idle.pollFirst() ?: Link(servers, socksPort, dnsPort, nextServer).also {
                synchronized(this@DnsUpstream) { open.add(it) }
            }
            val answer = try {
                link.exchange(query)
            } catch (e: IOException) {
                Log.d(TAG, "DNS upstream failed: ${e.message}")
                null
            }
            if (answer != null) {
                idle.addLast(link)
            } else {
                // A timed-out stream can still hold a late answer, so the link is
                // not reused; the next query starts on the other resolver.
                link.close()
                synchronized(this@DnsUpstream) { open.remove(link) }
                if (servers.size > 1) nextServer = (nextServer + 1) % servers.size
            }
            answer
        }
    }

    /** Drop every connection; the next exchange reconnects. */
    fun close() {
        val links = synchronized(this) {
            val snapshot = open.toList()
            open.clear()
            snapshot
        }
        links.forEach { it.close() }
        idle.clear()
    }

    /** One SOCKS5 + TCP/53 connection, reused across queries. */
    private class Link(
        private val servers: List<String>,
        private val socksPort: Int,
        private val dnsPort: Int,
        private var serverIndex: Int,
    ) {
        private var socket: Socket? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null

        fun exchange(query: ByteArray): ByteArray? {
            if (!open()) return null
            val outs = output ?: return null
            val ins = input ?: return null
            outs.write((query.size ushr 8).toByte().toInt())
            outs.write(query.size.toByte().toInt())
            outs.write(query)
            outs.flush()
            val id = DnsWire.id(query)
            repeat(MAX_READS) {
                val header = ByteArray(2)
                readFully(ins, header, 0, 2) ?: return null
                val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                if (length <= 0) return null
                val body = ByteArray(length)
                readFully(ins, body, 0, length) ?: return null
                // A late answer to an earlier query can be in front of ours.
                if (DnsWire.id(body) == id) return body
            }
            return null
        }

        fun close() {
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
        }

        private fun open(): Boolean {
            if (socket?.isClosed == false) return true
            repeat(servers.size) {
                val server = servers[serverIndex]
                try {
                    val candidate = Socket()
                    candidate.soTimeout = READ_TIMEOUT_MS
                    candidate.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort),
                        CONNECT_TIMEOUT_MS,
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
                    serverIndex = (serverIndex + 1) % servers.size
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
    }

    companion object {
        /** Enough for a page load's parallel lookups without a connection per query. */
        private const val MAX_CONNECTIONS = 4

        /** Frames to skip before giving up on the stream (bounded, never a loop). */
        private const val MAX_READS = 8

        /** A dead resolver costs this much, once, instead of stalling the page. */
        private const val READ_TIMEOUT_MS = 2_500

        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val TAG = "LocalDns"
    }
}
