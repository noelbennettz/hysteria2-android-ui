package us.leaf3stones.hy2droid.data

/**
 * One entry of the "blocked traffic" list: the transport protocols it applies
 * to plus a single port or an inclusive port range.
 *
 * Pure Kotlin with no Android dependencies (unit-testable): a list of these is
 * rendered into the `misc.block-tcp` / `misc.block-udp` values that the bundled
 * tun2socks reads, and stored in the settings DataStore in [encode] form.
 *
 * Matching traffic is refused before it ever reaches the Hysteria core, so
 * clients fall back to another protocol. The motivating case is QUIC over
 * UDP/443: Hysteria 2 cannot accelerate UDP, so QUIC inside the tunnel is much
 * slower than TCP on lossy networks.
 */
data class BlockRule(
    val tcp: Boolean,
    val udp: Boolean,
    val portFrom: Int,
    val portTo: Int = portFrom,
) {
    init {
        require(tcp || udp) { "a rule must apply to at least one protocol" }
        require(portFrom in MIN_PORT..MAX_PORT && portTo in portFrom..MAX_PORT) {
            "port out of range: $portFrom-$portTo"
        }
    }

    /** True when this rule blocks [port] on the given transport. */
    fun matches(port: Int, udp: Boolean): Boolean =
        (if (udp) this.udp else tcp) && port in portFrom..portTo

    /** `443`, or `8000-8100` for a range. */
    val portSpec: String
        get() = if (portFrom == portTo) portFrom.toString() else "$portFrom-$portTo"

    /** Compact, locale independent form: `udp/443`, `both/8000-8100`. */
    fun encode(): String = "${protoToken()}/$portSpec"

    private fun protoToken(): String = when {
        tcp && udp -> "both"
        udp -> "udp"
        else -> "tcp"
    }

    companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        /** QUIC: browsers try UDP/443 first, Hysteria 2 forwards it poorly. */
        val QUIC = BlockRule(tcp = false, udp = true, portFrom = 443)

        /** Parse an [encode] string; returns `null` for anything malformed. */
        fun parse(text: String): BlockRule? {
            val trimmed = text.trim()
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash == trimmed.lastIndex) return null

            val proto = trimmed.substring(0, slash).lowercase()
            val spec = trimmed.substring(slash + 1)
            val (from, to) = parsePortSpec(spec) ?: return null

            return when (proto) {
                "tcp" -> BlockRule(tcp = true, udp = false, portFrom = from, portTo = to)
                "udp" -> BlockRule(tcp = false, udp = true, portFrom = from, portTo = to)
                "both" -> BlockRule(tcp = true, udp = true, portFrom = from, portTo = to)
                else -> null
            }
        }

        /**
         * Parse what the user typed into the port field ("443", "8000-8100",
         * spaces around the dash tolerated). Returns `null` when it is not a
         * valid port or range.
         */
        fun fromInput(tcp: Boolean, udp: Boolean, portText: String): BlockRule? {
            val (from, to) = parsePortSpec(portText.trim()) ?: return null
            if (!tcp && !udp) return null
            return try {
                BlockRule(tcp = tcp, udp = udp, portFrom = from, portTo = to)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** `"443"` / `"8000-8100"` to a port pair, or `null` when invalid. */
        private fun parsePortSpec(spec: String): Pair<Int, Int>? {
            if (spec.isEmpty()) return null
            val dash = spec.indexOf('-')
            val fromText = (if (dash < 0) spec else spec.substring(0, dash)).trim()
            val toText = if (dash < 0) fromText else spec.substring(dash + 1).trim()
            val from = fromText.toIntOrNull() ?: return null
            val to = toText.toIntOrNull() ?: return null
            if (from !in MIN_PORT..MAX_PORT || to !in from..MAX_PORT) return null
            return from to to
        }
    }
}

/** The `misc.block-tcp` value: comma separated ports/ranges, `""` when nothing applies. */
fun List<BlockRule>.tcpPortSpec(): String = portSpec(udp = false)

/** The `misc.block-udp` value: comma separated ports/ranges, `""` when nothing applies. */
fun List<BlockRule>.udpPortSpec(): String = portSpec(udp = true)

private fun List<BlockRule>.portSpec(udp: Boolean): String =
    filter { if (udp) it.udp else it.tcp }
        .sortedBy { it.portFrom }
        .joinToString(",") { it.portSpec }

/** Persisted form: one [BlockRule.encode] string per entry. */
fun List<BlockRule>.encodeRules(): List<String> = map { it.encode() }

/** Parse persisted strings, dropping anything malformed and any duplicates. */
fun decodeRules(values: Collection<String>): List<BlockRule> =
    values.mapNotNull(BlockRule::parse).distinct()
