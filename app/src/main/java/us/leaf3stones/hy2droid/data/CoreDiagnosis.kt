package us.leaf3stones.hy2droid.data

/**
 * A failure that the Hysteria core's own log makes recognisable.
 *
 * The core is a proxy, so almost everything goes wrong as "it is slow" or "it
 * does not connect": a bad password, a certificate mismatch, a dead resolver on
 * the server and a blocked UDP port look identical from the outside. Naming the
 * probable cause is most of the support value this app can add.
 */
enum class CoreProblem {
    /** The server's name does not resolve (the app's own resolver, not the tunnel's). */
    HostNotFound,

    /** TLS: unknown CA, hostname mismatch, or an expired certificate. */
    Tls,

    /** The server rejected the password (or the obfs password). */
    Auth,

    /** Nothing came back: blocked UDP, wrong port, or a server that is down. */
    Timeout,

    /** Something answered, but not the proxy. */
    Refused,

    /** Salamander is on one side only, or the passwords differ. */
    Obfs,
}

/**
 * A deliberately small, substring-based classifier over core output.
 *
 * Heuristics beat silence here: the alternative is showing "Error" and asking
 * the user to read a log file. The rules only ever *suggest* a cause, and the
 * raw line stays available next to the hint (and in the diagnostics export).
 */
object CoreDiagnosis {

    private class Rule(val needles: List<String>, val problem: CoreProblem)

    // Order matters: the first match wins, so the more specific story is told
    // first (an obfs password mismatch also reads as "invalid password").
    private val RULES = listOf(
        Rule(listOf("no such host", "server misbehaving"), CoreProblem.HostNotFound),
        Rule(listOf("certificate", "x509", "tls:"), CoreProblem.Tls),
        Rule(listOf("salamander", "obfs"), CoreProblem.Obfs),
        Rule(listOf("connection refused"), CoreProblem.Refused),
        Rule(
            listOf("authentication failed", "auth error", "invalid password", "wrong password"),
            CoreProblem.Auth,
        ),
        Rule(
            listOf("i/o timeout", "timeout", "deadline exceeded", "no recent network activity"),
            CoreProblem.Timeout,
        ),
    )

    /** The probable cause of [line], or null when it is routine output. */
    fun classify(line: String): CoreProblem? {
        val text = line.lowercase()
        if (text.isBlank() || text.contains("redacted")) return null
        return RULES.firstOrNull { rule -> rule.needles.any { text.contains(it) } }?.problem
    }
}
