package us.leaf3stones.hy2droid.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * Parses and emits `hysteria2://` share links.
 *
 * Link shape:
 * `hysteria2://<auth>@<host>:<port>/?sni=<sni>&obfs=salamander&obfs-password=<pw>&insecure=1#<name>`
 *
 * Pure Kotlin/`java.net` only: unit-testable on the JVM.
 */
object HysteriaUri {
    private const val SCHEME = "hysteria2"

    /** `hy2://` is the short alias other clients emit for the same link. */
    private val SCHEMES = setOf("hysteria2", "hy2")
    private const val DEFAULT_PORT = 443

    /** Parse a share link into a [Profile], or `null` if it is not a valid hysteria2 link. */
    fun parse(link: String): Profile? {
        val uri = try {
            URI(link.trim())
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme == null || uri.scheme.lowercase() !in SCHEMES) return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        val auth = decode(uri.userInfo ?: "")

        val params = parseQuery(uri.rawQuery)
        val sni = params["sni"].orEmpty()
        val obfsPassword = params["obfs-password"].orEmpty()
        val insecure = params["insecure"].let { it == "1" || it.equals("true", ignoreCase = true) }
        // Bandwidth is not part of the official scheme (it says client-specific
        // values must not be shared), but other clients put it in anyway. Accept
        // both the short and the legacy names, and only positive values.
        val tx = bandwidth(params["up"] ?: params["upmbps"])
        val rx = bandwidth(params["down"] ?: params["downmbps"])
        val name = decode(uri.fragment.orEmpty()).ifBlank { host }

        return Profile(
            id = UUID.randomUUID().toString(),
            name = name,
            server = host,
            port = port,
            auth = auth,
            sni = sni,
            obfsPassword = obfsPassword,
            insecure = insecure,
            brutal = tx != null || rx != null,
            txMbps = tx ?: 0,
            rxMbps = rx ?: 0,
        )
    }

    /** `20`, `20Mbps` and `20 mbps` all mean the same thing in the wild. */
    private val BANDWIDTH = Regex("""^(\d+)\s*(?:mbps)?$""", RegexOption.IGNORE_CASE)

    private fun bandwidth(raw: String?): Int? =
        raw?.trim()?.let { BANDWIDTH.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 }

    /** Emit a share link for [profile]. */
    fun build(profile: Profile): String {
        val sb = StringBuilder()
        sb.append(SCHEME).append("://")
        if (profile.auth.isNotBlank()) {
            sb.append(encode(profile.auth)).append('@')
        }
        sb.append(profile.authority).append('/')

        val query = buildList {
            if (profile.sni.isNotBlank()) add("sni=" + encode(profile.sni))
            if (profile.hasObfs) {
                add("obfs=salamander")
                add("obfs-password=" + encode(profile.obfsPassword))
            }
            if (profile.insecure) add("insecure=1")
        }
        if (query.isNotEmpty()) {
            sb.append('?').append(query.joinToString("&"))
        }
        if (profile.name.isNotBlank()) {
            sb.append('#').append(encode(profile.name))
        }
        return sb.toString()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            decode(pair.substring(0, idx)).lowercase() to decode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

    private fun encode(value: String): String =
        try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
}
