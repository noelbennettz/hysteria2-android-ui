package us.leaf3stones.hy2droid.data

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The DNS wire handling behind the in-tunnel cache. */
class DnsMessageTest {

    @Test
    fun `a query is parsed into a lower-case question`() {
        val question = DnsWire.question(query("Example.COM", type = 28))

        assertNotNull(question)
        assertEquals("example.com", question!!.name)
        assertEquals(28, question.type)
        assertEquals(1, question.dnsClass)
    }

    @Test
    fun `a message without exactly one question is ignored`() {
        val twoQuestions = query("a.test")
        writeU16(twoQuestions, 4, 2)

        assertNull(DnsWire.question(twoQuestions))
        assertNull(DnsWire.question(ByteArray(4)))
    }

    @Test
    fun `an answer is cached with the smallest answer TTL`() {
        val now = 1_000_000L
        val entry = DnsWire.cacheable(answer("example.com", ttl = 300, secondTtl = 120), now)

        assertNotNull(entry)
        assertEquals(120, entry!!.ttl)
        assertEquals(now + 120_000L, entry.expiresAt)
    }

    @Test
    fun `the answer TTL is clamped to the ceiling`() {
        val entry = DnsWire.cacheable(answer("example.com", ttl = 86400), 0L)

        assertNotNull(entry)
        assertEquals(DnsWire.TTL_CEILING, entry!!.ttl)
        // The bytes handed out must not claim a longer lifetime than the entry.
        assertEquals(DnsWire.TTL_CEILING, firstAnswerTtl(entry.packet))
    }

    @Test
    fun `a cached answer is re-addressed and aged`() {
        val stored = DnsWire.cacheable(answer("example.com", ttl = 120), 1_000L)!!

        val served = DnsWire.serve(stored, id = 0xBEEF, now = 31_000L)

        assertNotNull(served)
        assertEquals(0xBEEF, DnsWire.id(served!!))
        assertEquals(90, firstAnswerTtl(served))            // 30 s in the cache
        assertEquals("example.com", DnsWire.question(served)!!.name)
    }

    @Test
    fun `a negative answer follows the SOA`() {
        val entry = DnsWire.cacheable(nxdomain("nope.test", soaMinimum = 45, soaTtl = 900), 0L)

        assertNotNull(entry)
        assertEquals(45, entry!!.ttl)
    }

    @Test
    fun `a negative answer without an SOA is not cached`() {
        assertNull(DnsWire.cacheable(answer("example.com", ttl = 60, answers = 0), 0L))
    }

    @Test
    fun `transient and truncated responses are not cached`() {
        val servfail = answer("example.com", ttl = 60)
        writeU16(servfail, 2, 0x8182)                       // RCODE 2
        assertNull(DnsWire.cacheable(servfail, 0L))

        val truncated = answer("example.com", ttl = 60)
        writeU16(truncated, 2, 0x8380)                      // TC set
        assertNull(DnsWire.cacheable(truncated, 0L))

        // A query is never a cache entry.
        assertNull(DnsWire.cacheable(query("example.com"), 0L))
    }

    @Test
    fun `an OPT record is passed through but neither aged nor counted`() {
        val packet = answer("example.com", ttl = 60, withOpt = true)

        val entry = DnsWire.cacheable(packet, 0L)

        assertNotNull(entry)
        assertEquals(60, entry!!.ttl)
        // Only the A record is aged; the OPT "TTL" is flags, not a lifetime.
        assertEquals(1, entry.ttlOffsets.size)
        val served = DnsWire.serve(entry, 7, 20_000L)!!
        assertEquals(firstAnswerTtl(packet) - 20, firstAnswerTtl(served))
    }

    @Test
    fun `malformed input never throws`() {
        val valid = answer("example.com", ttl = 60)
        for (length in 0 until valid.size) {
            val prefix = valid.copyOfRange(0, length)
            DnsWire.question(prefix)
            DnsWire.cacheable(prefix, 0L)
        }
        assertNull(DnsWire.cacheable(ByteArray(0), 0L))
        assertNull(DnsWire.question(ByteArray(0)))
    }

    @Test
    fun `an expired entry is not served`() {
        val stored = DnsWire.cacheable(answer("example.com", ttl = 60), 0L)!!

        assertNull(DnsWire.serve(stored, id = 1, now = 60_000L))
        assertNull(DnsWire.serve(stored, id = 1, now = 90_000L))
    }

    // --- synthetic packets --------------------------------------------------

    private fun query(name: String, type: Int = 1, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, 0x0100)                               // RD
        writeU16(out, 1)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        writeName(out, name)
        writeU16(out, type)
        writeU16(out, 1)
        return out.toByteArray()
    }

    private fun answer(
        name: String,
        ttl: Int,
        secondTtl: Int? = null,
        answers: Int = if (secondTtl == null) 1 else 2,
        withOpt: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, 0x1234)
        writeU16(out, 0x8180)                               // QR, RD, RA
        writeU16(out, 1)
        writeU16(out, answers)
        writeU16(out, 0)
        writeU16(out, if (withOpt) 1 else 0)
        writeName(out, name)
        writeU16(out, 1)
        writeU16(out, 1)
        repeat(answers) { index ->
            writeU16(out, 0xC00C)                           // owner = question name
            writeU16(out, 1)                                // A
            writeU16(out, 1)                                // IN
            writeU32(out, if (index == 0) ttl else secondTtl ?: ttl)
            writeU16(out, 4)
            out.write(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        }
        if (withOpt) {
            out.write(0)                                    // root name
            writeU16(out, 41)                               // OPT
            writeU16(out, 4096)                             // payload size
            writeU32(out, 0x00008000)                       // DO flag
            writeU16(out, 0)
        }
        return out.toByteArray()
    }


    private fun nxdomain(name: String, soaMinimum: Int, soaTtl: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, 0x1234)
        writeU16(out, 0x8183)                               // QR, RD, RA + NXDOMAIN
        writeU16(out, 1)
        writeU16(out, 0)
        writeU16(out, 1)
        writeU16(out, 0)
        writeName(out, name)
        writeU16(out, 1)
        writeU16(out, 1)
        writeU16(out, 0xC00C)                               // SOA owner
        writeU16(out, 6)                                    // SOA
        writeU16(out, 1)
        writeU32(out, soaTtl)
        writeU16(out, 24)                                   // 2 name pointers + 5 u32
        writeU16(out, 0xC00C)
        writeU16(out, 0xC00C)
        repeat(4) { writeU32(out, 60) }
        writeU32(out, soaMinimum)
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU16(packet: ByteArray, at: Int, value: Int) {
        packet[at] = (value ushr 8).toByte()
        packet[at + 1] = value.toByte()
    }

    /** TTL of the first answer record, read the way a client would. */
    private fun firstAnswerTtl(packet: ByteArray): Int {
        var at = 12
        while (packet[at].toInt() != 0) at += 1 + (packet[at].toInt() and 0xFF)
        at += 1 + 4                                        // root label + qtype/qclass
        at += 2                                            // compressed owner name
        return ((packet[at + 4].toInt() and 0xFF) shl 24) or
            ((packet[at + 5].toInt() and 0xFF) shl 16) or
            ((packet[at + 6].toInt() and 0xFF) shl 8) or
            (packet[at + 7].toInt() and 0xFF)
    }
}

