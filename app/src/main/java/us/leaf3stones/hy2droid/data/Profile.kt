package us.leaf3stones.hy2droid.data

/**
 * A single Hysteria 2 connection profile. This is the pure, framework-free model
 * shared across the data layer; it carries no Android types so it can be exercised
 * from plain JVM unit tests.
 *
 * @property id stable unique identifier (a v4 UUID).
 * @property name user-facing display name.
 * @property server `host:port` of the Hysteria 2 server.
 * @property auth authentication secret sent during the HTTP/3 handshake.
 * @property sni TLS Server Name Indication; blank means "derive from [server]".
 * @property obfsPassword Salamander obfuscation password; blank means no obfs.
 * @property insecure whether to skip TLS certificate verification (dangerous).
 * @property brutal whether Brutal congestion control is used at all (otherwise BBR).
 * @property txMbps uplink bandwidth in Mbps for Brutal congestion control (0 = unset).
 * @property rxMbps downlink bandwidth in Mbps for Brutal congestion control (0 = unset).
 * @property fastOpen whether to enable QUIC fast open.
 */
data class Profile(
    val id: String,
    val name: String,
    val server: String,
    val auth: String,
    val sni: String = "",
    val obfsPassword: String = "",
    val insecure: Boolean = false,
    val brutal: Boolean = false,
    val txMbps: Int = 0,
    val rxMbps: Int = 0,
    val fastOpen: Boolean = false,
    val port: Int = 443,
) {
    /** True when Salamander obfuscation is configured. */
    val hasObfs: Boolean get() = obfsPassword.isNotBlank()

    /** True when both rates a Brutal profile needs are present. */
    val hasBandwidth: Boolean get() = txMbps > 0 || rxMbps > 0

    /**
     * True when the core should be told to pace at the configured rates.
     *
     * Brutal is a fixed-rate algorithm: it does not probe capacity and does not
     * back off on loss, so a rate above what the path can carry turns into loss
     * and retransmissions. Keeping the switch explicit means a profile cannot
     * end up on Brutal just because somebody typed a number once.
     */
    val usesBrutal: Boolean get() = brutal && hasBandwidth

    /** `host:port` authority, tolerant of a [server] that already embeds a port. */
    val authority: String get() = if (server.contains(':')) server else "$server:$port"
}
