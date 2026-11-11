package us.leaf3stones.hy2droid.proxy

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the Hysteria core's stdout/stderr so a user can hand a log file to
 * whoever debugs their connection. logcat alone is useless after a reboot or an
 * app crash, and the core is the part that knows why a handshake failed.
 *
 * The live file is capped at [MAX_BYTES]; when it overflows it becomes
 * `hysteria.1.log` and a fresh file starts, so at most ~2× MAX stays on disk.
 * Callers must redact sensitive lines first (see `data.Redact.logLine`).
 */
class CoreLog(context: Context) : Closeable {

    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val file = File(dir, FILE_NAME)
    private val writer: BufferedWriter
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var closed = false

    init {
        rotate()
        writer = BufferedWriter(FileWriter(file, true))
    }

    @Synchronized
    fun write(line: String) {
        if (closed) return
        try {
            writer.write(stamp.format(Date()))
            writer.write(' '.code)
            writer.write(line)
            writer.newLine()
            // Flushed per line: an export right after a crash must have the tail.
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Could not append to the core log: ${e.message}")
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            writer.close()
        } catch (_: Exception) {
            // Nothing left to do.
        }
    }

    private fun rotate() {
        if (file.length() < MAX_BYTES) return
        val archive = File(dir, ARCHIVE_NAME)
        archive.delete()
        file.renameTo(archive)
    }

    companion object {
        const val DIR = "logs"
        const val FILE_NAME = "hysteria.log"
        const val ARCHIVE_NAME = "hysteria.1.log"
        private const val MAX_BYTES = 512 * 1024L
        private const val TAG = "CoreLog"

        /** The live core log file (may not exist yet). */
        fun liveFile(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

        /** The rotated core log file (may not exist). */
        fun archiveFile(context: Context): File = File(File(context.filesDir, DIR), ARCHIVE_NAME)
    }
}
