package us.leaf3stones.hy2droid.proxy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.leaf3stones.hy2droid.data.DnsCache
import us.leaf3stones.hy2droid.data.DnsWire
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A caching DNS resolver that lives inside the tunnel.
 *
 * The VPN pushes the TUN's *own* address as the resolver, so Android's resolver
 * sends its queries to `10.0.88.88:53`. Because that address belongs to the tun
 * interface, the kernel delivers those datagrams to this socket instead of
 * writing them into the TUN — hev never sees them. A cache hit is answered from
 * memory in microseconds; a miss is forwarded to a real resolver *through the
 * Hysteria core* (SOCKS5, so the query leaves from the server's egress and the
 * name is never resolved on the phone).
 *
 * That is the difference to hev's `mapdns`: no fake addresses, so an app that
 * caches an address and connects later still reaches the right host, and AAAA
 * works because negative answers are real answers here.
 *
 * Binds port 53 on all interfaces before the tunnel is established (the TUN
 * address does not exist yet), so a device that refuses the bind falls back to
 * forwarded resolvers instead of losing DNS.
 */
class LocalDns(private val upstreams: List<String>, private val socksPort: Int) {

    private val cache = DnsCache()
    private val client = DnsUpstream(upstreams, socksPort)
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    /** Reserve port 53. False when another process (or the OS) has it. */
    fun bind(): Boolean {
        if (socket != null) return true
        return try {
            socket = open(null)
            available = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind UDP/$PORT for the local resolver", e)
            available = false
            false
        }
    }

    /**
     * Re-bind to the TUN address once it exists.
     *
     * The reply must carry the address the client sent the query to: Android's
     * resolver `connect()`s its UDP socket, so an answer whose source were the
     * wildcard's choice (`127.0.0.1`, because the destination was a local
     * address) would be dropped as coming from a different peer. The old socket
     * stays bound until the new one is up, so the port is never released.
     */
    fun attach(address: String): Boolean {
        val previous = socket ?: return false
        return try {
            socket = open(address)
            runCatching { previous.close() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not bind the resolver to $address; DNS may not work", e)
            false
        }
    }

    private fun open(address: String?): DatagramSocket {
        val bound = DatagramSocket(null)
        bound.reuseAddress = true
        bound.bind(if (address == null) InetSocketAddress(PORT) else InetSocketAddress(address, PORT))
        bound.soTimeout = 0
        return bound
    }

    /** Start answering. Call after [bind] and after the core is listening. */
    fun start(scope: CoroutineScope) {
        val bound = socket ?: return
        job?.cancel()
        job = scope.launch {
            val buffer = ByteArray(MAX_UDP)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    bound.receive(packet)
                } catch (e: IOException) {
                    // Closed by stop(), or the interface went away.
                    if (!isActive) return@launch
                    Log.w(TAG, "Resolver socket failed; giving up", e)
                    return@launch
                }
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                launch { handle(query, packet.address, packet.port) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
        client.close()
        cache.clear()
    }

    private suspend fun handle(query: ByteArray, from: InetAddress, port: Int) {
        // Only queries (a response here would be a loop); one question each.
        if (query.size < 12 || (query[2].toInt() and 0x80) != 0) return
        val question = DnsWire.question(query) ?: return
        val id = DnsWire.id(query)
        val target = socket ?: return

        val cached = cache.lookup(question, id, System.currentTimeMillis())
        if (cached != null) {
            reply(target, cached, from, port)
            return
        }

        val response = client.exchange(query) ?: run {
            Log.d(TAG, "No answer for ${question.name}/${question.type}; letting the client retry")
            return
        }
        DnsWire.cacheable(response, System.currentTimeMillis())
            ?.let { cache.store(question, it) }
        reply(target, response, from, port)
    }

    private fun reply(socket: DatagramSocket, data: ByteArray, to: InetAddress, port: Int) {
        try {
            socket.send(DatagramPacket(data, data.size, to, port))
        } catch (e: IOException) {
            Log.d(TAG, "Could not answer a DNS query", e)
        }
    }

    companion object {
        /**
         * False when the platform refuses port 53 (Android reserves privileged
         * ports, so this is the normal case on a non-rooted phone). The settings
         * screen uses it to stop claiming the cache is on.
         */
        @Volatile
        var available: Boolean = false
            private set

        const val PORT = Tun.CACHE_DNS_PORT
        private const val MAX_UDP = 4096
        private const val TAG = "LocalDns"
    }
}
