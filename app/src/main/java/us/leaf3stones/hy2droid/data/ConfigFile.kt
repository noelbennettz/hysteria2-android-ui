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

    /** Sibling name used while writing; also what a crash can leave behind. */
    const val TEMP_SUFFIX = ".tmp"

    /** The temp file [write] uses for [target]. */
    fun tempFile(target: File): File = File(target.parentFile, target.name + TEMP_SUFFIX)

    /** Atomically replace [target] with [content]. Returns false on any IO error. */
    fun write(target: File, content: String): Boolean {
        val temp = tempFile(target)
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

    /**
     * Delete [target] and any leftover temp file. The temp file carries the same
     * auth secret, so it must not survive a crash — nor be picked up by a backup.
     */
    fun delete(target: File) {
        try {
            target.delete()
        } catch (_: Throwable) {
            // Nothing to clean up.
        }
        try {
            tempFile(target).delete()
        } catch (_: Throwable) {
            // Nothing to clean up.
        }
    }
}
