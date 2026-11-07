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
    val txMbps: Int = 0,
    val rxMbps: Int = 0,
    val fastOpen: Boolean = false,
    val port: Int = 443,
) {
    /** True when Salamander obfuscation is configured. */
    val hasObfs: Boolean get() = obfsPassword.isNotBlank()

    /** True when a Brutal bandwidth hint is present (otherwise BBR is used). */
    val hasBandwidth: Boolean get() = txMbps > 0 || rxMbps > 0

    /** `host:port` authority, tolerant of a [server] that already embeds a port. */
    val authority: String get() = if (server.contains(':')) server else "$server:$port"
}
