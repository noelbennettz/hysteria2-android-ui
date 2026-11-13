package us.leaf3stones.hy2droid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the core version by running the bundled binary instead of trusting a
 * build-time constant. A Gradle constant can only describe what was downloaded
 * when the APK was built; `libhysteria.so` is a plain file here, so a swapped
 * core would make that constant lie. `hysteria version` costs one short
 * process, needs no network and answers for the binary that would actually run.
 */
object CoreVersion {

    private const val BINARY = "libhysteria.so"

    /** `Version:\tv2.5.1` in the CLI banner. */
    private val VERSION = Regex("""Version:\s*(\S+)""")

    /** Pulls the version out of the banner, or null when it is not there. */
    fun parse(output: String): String? = VERSION.find(output)?.groupValues?.get(1)

    /** The running core's version, or null when the probe fails for any reason. */
    suspend fun probe(context: Context): String? = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY)
        if (!binary.canExecute()) return@withContext null
        runCatching {
            val process = ProcessBuilder(binary.absolutePath, "version")
                .redirectErrorStream(true)
                .start()
            // `version` exits on its own; never let a stuck process pin an IO thread.
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                return@runCatching null
            }
            process.inputStream.bufferedReader().use { parse(it.readText()) }
        }.getOrNull()
    }
}
