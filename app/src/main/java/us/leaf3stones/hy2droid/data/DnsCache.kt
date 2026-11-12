package us.leaf3stones.hy2droid.data

/**
 * Bounded, TTL-aware cache of DNS responses keyed by question.
 *
 * LRU-bounded and touched from one resolver coroutine plus its per-query
 * children, so every entry point is synchronised. Nothing here knows about
 * sockets or Android: see `proxy/LocalDns.kt` for the resolver that uses it.
 */
class DnsCache(private val maxEntries: Int = 256) {

    private val entries =
        object : LinkedHashMap<DnsWire.Question, DnsWire.Response>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<DnsWire.Question, DnsWire.Response>,
            ): Boolean = size > maxEntries
        }

    /**
     * An aged copy of the cached answer for [question], re-addressed to [id], or
     * null when there is no entry or it has expired (expired entries are
     * dropped on the way out).
     */
    @Synchronized
    fun lookup(question: DnsWire.Question, id: Int, now: Long): ByteArray? {
        val entry = entries[question] ?: return null
        val packet = DnsWire.serve(entry, id, now)
        if (packet == null) entries.remove(question)
        return packet
    }

    /** Keep [response] for [question]; a newer answer replaces an older one. */
    @Synchronized
    fun store(question: DnsWire.Question, response: DnsWire.Response) {
        entries[question] = response
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
