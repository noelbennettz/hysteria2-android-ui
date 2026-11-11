package us.leaf3stones.hy2droid.data

import java.io.File

/**
 * Writes the core config so a half-finished write can never be mistaken for a
 * good one: the content goes to a sibling `.tmp` first and is then renamed over
 * the target (rename within one directory is atomic). A crash mid-write leaves
 * the previous file — or nothing — behind, never a truncated YAML that
 * `ensureConfigFile()` would accept because it merely exists.
 *
 * Pure JVM, no Android dependencies.
 */
object ConfigFile {

    /** Atomically replace [target] with [content]. Returns false on any IO error. */
    fun write(target: File, content: String): Boolean {
        val temp = File(target.parentFile, target.name + ".tmp")
        return try {
            temp.writeText(content)
            if (!temp.renameTo(target)) {
                // Some filesystems refuse the rename; fall back to a direct write
                // rather than leaving the service without a config.
                target.writeText(content)
                temp.delete()
            }
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }
}
