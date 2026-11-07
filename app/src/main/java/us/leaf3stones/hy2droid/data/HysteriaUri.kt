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
    private const val DEFAULT_PORT = 443

    /** Parse a share link into a [Profile], or `null` if it is not a valid hysteria2 link. */
    fun parse(link: String): Profile? {
        val uri = try {
            URI(link.trim())
        } catch (_: Exception) {
            return null
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        val auth = decode(uri.userInfo ?: "")

        val params = parseQuery(uri.rawQuery)
        val sni = params["sni"].orEmpty()
        val obfsPassword = params["obfs-password"].orEmpty()
        val insecure = params["insecure"].let { it == "1" || it.equals("true", ignoreCase = true) }
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
        )
    }

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
            decode(pair.substring(0, idx)) to decode(pair.substring(idx + 1))
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
