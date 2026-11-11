package us.leaf3stones.hy2droid.data

/**
 * Renders a [Profile] into a Hysteria 2 client YAML config.
 *
 * Every user-supplied scalar is emitted as a double-quoted YAML string with
 * proper escaping, so values containing `:`, `#`, quotes, spaces or newlines can
 * no longer break or inject into the document. Optional sections (obfs, TLS,
 * bandwidth) are only emitted when they carry information, so the defaults fall
 * back to Hysteria's own defaults (e.g. BBR when no bandwidth hint is set).
 *
 * Pure Kotlin with no Android dependencies: unit-testable on the JVM.
 */
object HysteriaConfigBuilder {
    /** Local SOCKS5 port the core listens on; [us.leaf3stones.hy2droid.proxy.Tun2socksConfig] dials this. */
    const val DEFAULT_SOCKS_PORT = 1080

    /** Local HTTP proxy port the core listens on. */
    const val DEFAULT_HTTP_PORT = 1081

    /**
     * QUIC heartbeat interval. Upstream validates 2 s – 60 s. 20 s keeps the
     * session warm against the 30 s idle timeout without waking the radio as often
     * as 10 s did (10 s measurably increased battery drain on a phone).
     */
    const val KEEP_ALIVE = "20s"

    /** Build the full YAML config for [profile]. */
    fun build(
        profile: Profile,
        socksPort: Int = DEFAULT_SOCKS_PORT,
        httpPort: Int = DEFAULT_HTTP_PORT,
        /**
         * `lazy: true` only defers the **first** connection: upstream is explicit
         * that "the client will not connect until the first call to TCP() or
         * UDP()". Once the tunnel is up it stays up, and [KEEP_ALIVE] keeps it that
         * way. It stays on because paying a handshake at app start, with no traffic
         * yet, is wasted on a phone.
         */
        lazy: Boolean = true,
        /**
         * Workaround for MTU black holes (some mobile networks drop QUIC packets
         * above a certain size): emits `quic.disablePathMTUDiscovery: true`.
         */
        disablePathMtuDiscovery: Boolean = false,
    ): String {
        val sb = StringBuilder()
        sb.append("server: ").append(quote(profile.authority)).append('\n')
        sb.append("auth: ").append(quote(profile.auth)).append('\n')

        if (profile.hasObfs) {
            sb.append("obfs:\n")
            sb.append("  type: salamander\n")
            sb.append("  salamander:\n")
            sb.append("    password: ").append(quote(profile.obfsPassword)).append('\n')
        }

        if (profile.sni.isNotBlank() || profile.insecure) {
            sb.append("tls:\n")
            if (profile.sni.isNotBlank()) {
                sb.append("  sni: ").append(quote(profile.sni)).append('\n')
            }
            if (profile.insecure) {
                sb.append("  insecure: true\n")
            }
        }

        if (profile.hasBandwidth) {
            sb.append("bandwidth:\n")
            if (profile.txMbps > 0) {
                sb.append("  up: ").append(profile.txMbps).append(" mbps\n")
            }
            if (profile.rxMbps > 0) {
                sb.append("  down: ").append(profile.rxMbps).append(" mbps\n")
            }
        }

        sb.append("socks5:\n")
        sb.append("  listen: 127.0.0.1:").append(socksPort).append('\n')
        sb.append("http:\n")
        sb.append("  listen: 127.0.0.1:").append(httpPort).append('\n')
        sb.append("fastOpen: ").append(profile.fastOpen).append('\n')
        sb.append("lazy: ").append(lazy).append('\n')
        sb.append("quic:\n")
        sb.append("  maxIdleTimeout: 30s\n")
        // Heartbeat. 20 s keeps the session alive without waking the radio every
        // 10 s, which cost battery for no measurable latency gain.
        sb.append("  keepAlivePeriod: ").append(KEEP_ALIVE).append('\n')
        if (disablePathMtuDiscovery) {
            sb.append("  disablePathMTUDiscovery: true\n")
        }
        return sb.toString()
    }

    /** Wrap [value] as a double-quoted YAML scalar, escaping quotes/backslashes/control chars. */
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
