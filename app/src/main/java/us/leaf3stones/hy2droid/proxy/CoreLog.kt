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
class CoreLog private constructor(context: Context) : Closeable {

    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val file = File(dir, FILE_NAME)
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var written = 0L
    private var writer: BufferedWriter = openWriter()
    private var closed = false
    private var users = 1

    @Synchronized
    fun write(line: String) {
        if (closed) return
        try {
            writer.write(stamp.format(Date()))
            writer.write(" ")
            writer.write(line)
            writer.newLine()
            // Flushed per line: an export right after a crash must have the tail.
            writer.flush()
            written += line.length + STAMP_RESERVE
            // A long session would otherwise grow without bound: the cap has to be
            // enforced while running, not only when the file is opened.
            if (written >= MAX_BYTES) rotate()
        } catch (e: Exception) {
            Log.w(TAG, "Could not append to the core log: ${e.message}")
        }
    }

    override fun close() {
        if (release()) forget(this)
    }

    /** Drop one user; returns true when this was the last one. */
    @Synchronized
    private fun release(): Boolean {
        if (closed) return false
        // Two tunnel starts can briefly overlap; only the last user closes.
        if (--users > 0) return false
        closed = true
        try {
            writer.close()
        } catch (_: Exception) {
            // Nothing left to do.
        }
        return true
    }

    @Synchronized
    private fun retain() {
        users++
    }

    /** Open the live file, first archiving it when the previous session left it full. */
    private fun openWriter(): BufferedWriter {
        rotateIfFull()
        written = file.length()
        return BufferedWriter(FileWriter(file, true))
    }

    private fun rotateIfFull() {
        if (file.length() < MAX_BYTES) return
        archiveCurrent()
    }

    /** Move the live file aside and start a new one; only the newest archive is kept. */
    private fun rotate() {
        try {
            writer.close()
        } catch (_: Exception) {
            // Keep going: reopening below still truncates nothing.
        }
        archiveCurrent()
        writer = BufferedWriter(FileWriter(file, true))
        written = 0
    }

    private fun archiveCurrent() {
        File(dir, ARCHIVE_NAME).delete()
        file.renameTo(File(dir, ARCHIVE_NAME))
    }

    companion object {
        const val DIR = "logs"
        const val FILE_NAME = "hysteria.log"
        const val ARCHIVE_NAME = "hysteria.1.log"
        private const val MAX_BYTES = 512 * 1024L
        private const val STAMP_RESERVE = 25
        private const val TAG = "CoreLog"

        private var shared: CoreLog? = null

        /**
         * The process-wide writer. Two overlapping tunnel starts share one instance
         * (and one rotation state) instead of fighting over the same file.
         */
        @Synchronized
        fun open(context: Context): CoreLog {
            shared?.let {
                it.retain()
                return it
            }
            return CoreLog(context).also { shared = it }
        }

        /** Called by [close] once the last user is gone. */
        @Synchronized
        private fun forget(instance: CoreLog) {
            if (shared === instance) shared = null
        }

        /** The live core log file (may not exist yet). */
        fun liveFile(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

        /** The rotated core log file (may not exist). */
        fun archiveFile(context: Context): File = File(File(context.filesDir, DIR), ARCHIVE_NAME)
    }
}
