package us.leaf3stones.hy2droid.data

/**
 * Strips credentials out of the things that end up in logs or in the diagnostics
 * export. Pure Kotlin so the rules are unit-testable on the JVM.
 *
 * Two call sites matter: the core's stdout/stderr (which prints the auth secret
 * in its share-URI banner line) and the on-disk `hysteria.yaml` that we attach to
 * an exported diagnostics archive.
 */
object Redact {

    private const val MASK = "\"***\""
    private const val REDACTED_LINE = "[redacted: line contains credentials]"

    /**
     * Return [line] unchanged unless it carries a secret: the core's banner line
     * with the `hysteria2://` URI, or its Salamander password echo.
     */
    fun logLine(line: String): String =
        if (line.contains("hysteria2://") || line.contains("obfs-password")) {
            REDACTED_LINE
        } else {
            line
        }

    /**
     * Mask the `auth` and obfuscation `password` scalars of a Hysteria YAML
     * document while keeping the shape (indentation, key order) readable.
     */
    fun config(yaml: String): String = yaml.lines().joinToString("\n") { line ->
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val body = line.substring(indent.length)
        when {
            body.startsWith("auth:") -> "${indent}auth: $MASK"
            body.startsWith("password:") -> "${indent}password: $MASK"
            line.contains("hysteria2://") -> "$indent$REDACTED_LINE"
            else -> line
        }
    }
}
