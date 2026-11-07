package us.leaf3stones.hy2droid.proxy

/**
 * The fixed tun2socks config that bridges the Android TUN device to the core's
 * local SOCKS5 listener. Kept byte-for-byte compatible with what the bundled
 * `libtun2socks.so` expects; the SOCKS5 port must match
 * [us.leaf3stones.hy2droid.data.HysteriaConfigBuilder.DEFAULT_SOCKS_PORT].
 */
object Tun2socksConfig {
    /** File name (under `filesDir`) the native library reads. */
    const val FILE_NAME = "tun2socks.yaml"

    /** The full YAML body. */
    val CONTENT: String = """
        tunnel:
          name: tun0
          mtu: 1500
          multi-queue: false
          ipv4: 10.0.88.88
          ipv6: 'fc00::1'

        socks5:
          port: 1080
          address: 127.0.0.1
          udp: 'udp'
    """.trimIndent()
}
