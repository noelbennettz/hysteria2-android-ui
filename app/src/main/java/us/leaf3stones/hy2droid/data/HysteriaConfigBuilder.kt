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

    /** Build the full YAML config for [profile]. */
    fun build(
        profile: Profile,
        socksPort: Int = DEFAULT_SOCKS_PORT,
        httpPort: Int = DEFAULT_HTTP_PORT,
    ): String {
        val sb = StringBuilder()
        sb.append("server: ").append(quote(profile.server)).append('\n')
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
        sb.append("quic:\n")
        sb.append("  maxIdleTimeout: 30s\n")
        sb.append("  keepAlivePeriod: 20s\n")
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
