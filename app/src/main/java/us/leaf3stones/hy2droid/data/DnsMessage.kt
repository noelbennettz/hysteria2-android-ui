package us.leaf3stones.hy2droid.data

/**
 * Minimal DNS wire handling for the in-tunnel resolver cache.
 *
 * Deliberately small and opinionated: one question per message (what Android's
 * resolver sends), responses cached by question with their per-record TTLs aged
 * on the way out, EDNS0 OPT records passed through untouched and never aged
 * (their "TTL" field is flags, not a lifetime), truncated and error responses
 * never cached (except NXDOMAIN/NODATA, which follow the SOA).
 *
 * Pure byte handling with no Android types, so the whole thing is unit-tested
 * on the JVM — see `DnsMessageTest`.
 */
object DnsWire {

    const val TYPE_SOA = 6
    const val TYPE_OPT = 41

    /** Longest TTL the cache hands out; a day-old answer helps nobody. */
    const val TTL_CEILING = 3600

    /** RFC 2181: a TTL of 2^31-1 or more must be treated as zero. */
    private const val TTL_MAX = 0x7FFFFFFF

    /** The question of a message, normalised (lower-case name) for use as a key. */
    data class Question(val name: String, val type: Int, val dnsClass: Int)

    /**
     * A response kept for reuse: the bytes, where their TTL fields live, and the
     * smallest TTL (the entry's lifetime).
     */
    class Response(
        val packet: ByteArray,
        val storedAt: Long,
        val ttlOffsets: IntArray,
        val ttlSeconds: IntArray,
        val ttl: Int,
    ) {
        val expiresAt: Long get() = storedAt + ttl * 1000L
    }

    fun id(packet: ByteArray): Int =
        if (packet.size >= 2) readU16(packet, 0) else -1

    /**
     * The question of [packet] (a query or a response), or null when the message
     * is malformed or carries anything other than exactly one question.
     */
    fun question(packet: ByteArray): Question? {
        if (packet.size < 12) return null
        if (readU16(packet, 4) != 1) return null
        return readQuestion(packet, 12)?.first
    }

    /**
     * Turn a response into a cache entry, or null when it must not be cached.
     *
     * A positive answer lives for the smallest TTL among its answers; a negative
     * one (NXDOMAIN or no answer) follows the SOA that explains it. TTLs are
     * clamped to [TTL_CEILING] here, so what clients see and what the cache
     * honours cannot disagree.
     */
    fun cacheable(packet: ByteArray, now: Long): Response? {
        if (packet.size < 12) return null
        val flags = readU16(packet, 2)
        if (flags and 0x8000 == 0) return null          // not a response
        if (flags and 0x0200 != 0) return null          // truncated
        val rcode = flags and 0x000F
        if (rcode != 0 && rcode != 3) return null       // SERVFAIL etc. are transient
        if (readU16(packet, 4) != 1) return null        // one question only
        val question = readQuestion(packet, 12) ?: return null

        val copy = packet.copyOf()
        val offsets = ArrayList<Int>()
        val values = ArrayList<Int>()
        var minAnswerTtl = TTL_MAX
        var answered = 0
        var soaMinimum = -1
        var soaTtl = -1
        var at = question.second
        val counts = intArrayOf(readU16(packet, 6), readU16(packet, 8), readU16(packet, 10))

        for (section in counts.indices) {
            for (index in 0 until counts[section]) {
                val record = readRecord(copy, at) ?: return null
                at = record.next
                if (record.type == TYPE_OPT) continue
                val clamped = record.ttl.coerceIn(0, TTL_CEILING)
                writeU32(copy, record.ttlOffset, clamped)
                offsets += record.ttlOffset
                values += clamped
                if (section == 0) {
                    answered++
                    if (clamped < minAnswerTtl) minAnswerTtl = clamped
                } else if (section == 1 && record.type == TYPE_SOA && record.rdataLength >= 4) {
                    // RFC 2308: the negative TTL is min(SOA TTL, SOA MINIMUM).
                    val minimum = readU32(copy, record.rdataStart + record.rdataLength - 4)
                        .coerceAtMost(TTL_MAX.toLong())
                        .toInt()
                    soaMinimum = if (soaMinimum < 0) minimum else minOf(soaMinimum, minimum)
                    soaTtl = if (soaTtl < 0) clamped else minOf(soaTtl, clamped)
                }
            }
        }

        val ttl = if (answered > 0) {
            minAnswerTtl
        } else {
            if (soaMinimum < 0 || soaTtl < 0) return null
            minOf(soaMinimum, soaTtl)
        }.coerceIn(0, TTL_CEILING)

        if (ttl <= 0) return null
        return Response(copy, now, offsets.toIntArray(), values.toIntArray(), ttl)
    }

    /**
     * A cached response re-addressed to [id], with every TTL reduced by the time
     * spent in the cache. Null once the entry has expired.
     */
    fun serve(response: Response, id: Int, now: Long): ByteArray? {
        val elapsed = ((now - response.storedAt) / 1000L).toInt().coerceAtLeast(0)
        if (response.ttl - elapsed <= 0) return null
        val out = response.packet.copyOf()
        writeU16(out, 0, id)
        for (index in response.ttlOffsets.indices) {
            writeU32(
                out,
                response.ttlOffsets[index],
                (response.ttlSeconds[index] - elapsed).coerceAtLeast(0),
            )
        }
        return out
    }

    // --- wire helpers -------------------------------------------------------

    private class Record(
        val type: Int,
        val ttl: Int,
        val ttlOffset: Int,
        val rdataStart: Int,
        val rdataLength: Int,
        val next: Int,
    )

    private fun readU16(packet: ByteArray, at: Int): Int =
        ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)

    private fun readU32(packet: ByteArray, at: Int): Long =
        ((packet[at].toLong() and 0xFF) shl 24) or
            ((packet[at + 1].toLong() and 0xFF) shl 16) or
            ((packet[at + 2].toLong() and 0xFF) shl 8) or
            (packet[at + 3].toLong() and 0xFF)

    private fun writeU16(packet: ByteArray, at: Int, value: Int) {
        packet[at] = (value ushr 8).toByte()
        packet[at + 1] = value.toByte()
    }

    private fun writeU32(packet: ByteArray, at: Int, value: Int) {
        packet[at] = (value ushr 24).toByte()
        packet[at + 1] = (value ushr 16).toByte()
        packet[at + 2] = (value ushr 8).toByte()
        packet[at + 3] = value.toByte()
    }

    /** The question and the offset just past it; handles compression. */
    private fun readQuestion(packet: ByteArray, start: Int): Pair<Question, Int>? {
        val (name, afterName) = readName(packet, start) ?: return null
        if (afterName + 4 > packet.size) return null
        return Question(name, readU16(packet, afterName), readU16(packet, afterName + 2)) to
            afterName + 4
    }

    private fun readName(packet: ByteArray, start: Int): Pair<String, Int>? {
        val name = StringBuilder()
        var at = start
        var end = -1
        var jumps = 0
        while (true) {
            if (at >= packet.size) return null
            val length = packet[at].toInt() and 0xFF
            if (length == 0) {
                if (end < 0) end = at + 1
                break
            }
            if (length and 0xC0 == 0xC0) {
                if (at + 1 >= packet.size) return null
                val pointer = ((length and 0x3F) shl 8) or (packet[at + 1].toInt() and 0xFF)
                if (end < 0) end = at + 2
                if (++jumps > 8 || pointer >= packet.size) return null
                at = pointer
                continue
            }
            if (at + 1 + length > packet.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (index in 0 until length) {
                name.append((packet[at + 1 + index].toInt() and 0xFF).toChar())
            }
            at += 1 + length
        }
        return name.toString().lowercase() to end
    }

    private fun readRecord(packet: ByteArray, start: Int): Record? {
        val (_, afterName) = readName(packet, start) ?: return null
        if (afterName + 10 > packet.size) return null
        val ttlOffset = afterName + 4
        val ttl = readU32(packet, ttlOffset).coerceAtMost(TTL_MAX.toLong()).toInt()
        val rdataLength = readU16(packet, afterName + 8)
        val rdataStart = afterName + 10
        val next = rdataStart + rdataLength
        if (next > packet.size) return null
        return Record(readU16(packet, afterName), ttl, ttlOffset, rdataStart, rdataLength, next)
    }
}

