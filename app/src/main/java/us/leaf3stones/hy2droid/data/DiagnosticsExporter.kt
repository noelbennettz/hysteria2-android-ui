package us.leaf3stones.hy2droid.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.BuildConfig
import us.leaf3stones.hy2droid.proxy.CoreLog
import us.leaf3stones.hy2droid.proxy.Hysteria2VpnService
import us.leaf3stones.hy2droid.proxy.TunnelStatus
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles everything needed to debug "it does not connect" into one zip:
 * a build/device summary, this process' logcat buffer, the persisted Hysteria
 * core log and the running config with its secrets masked.
 *
 * Written straight into a Storage Access Framework [Uri] picked by the user, so
 * no storage permission and no FileProvider is involved.
 */
class DiagnosticsExporter(private val context: Context) {

    /** Write the archive to [target]; `false` when anything went wrong. */
    suspend fun export(target: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openOutputStream(target) ?: return@withContext false
            stream.use { output ->
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    zip.put("app-info.txt", buildInfo())
                    zip.put("logcat.txt", readLogcat())
                    zip.put(CoreLog.FILE_NAME, readText(CoreLog.liveFile(context), MAX_LOG_BYTES))
                    zip.put(CoreLog.ARCHIVE_NAME, readText(CoreLog.archiveFile(context), MAX_LOG_BYTES))
                    zip.put(CONFIG_ENTRY, redactedConfig())
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Diagnostics export failed", e)
            false
        }
    }

    private suspend fun buildInfo(): String {
        val settings = SettingsStore(context)
        val store = ProfileStore(context)
        val sb = StringBuilder()
        sb.append("Hy2droid ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("Hysteria core: ").append(BuildConfig.CORE_VERSION).append('\n')
        sb.append("Tunnel state: ").append(TunnelStatus.state.value).append('\n')
        sb.append("Exported: ").append(timestamp()).append("\n\n")

        sb.append("Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.firstOrNull().orEmpty()).append('\n')
        sb.append("Locale: ").append(Locale.getDefault()).append("\n\n")

        sb.append("Settings\n")
        sb.append("  blocked traffic: ").append(
            runCatching { settings.blockRules.first().joinToString { it.encode() } }.getOrDefault("?"),
        ).append('\n')
        sb.append("  split tunnel: ").append(
            runCatching { settings.excluded.first().size }.getOrDefault(-1),
        ).append(" app(s) bypassing\n")
        sb.append("  custom DNS: ").append(
            runCatching { settings.customDnsEnabled.first() }.getOrDefault(false),
        ).append('\n')
        sb.append("  bypass LAN: ").append(
            runCatching { settings.bypassLan.first() }.getOrDefault(false),
        ).append('\n')
        sb.append("  auto latency test: ").append(
            runCatching { settings.autoTestLatency.first() }.getOrDefault(false),
        ).append("\n\n")

        // No auth, obfs password or SNI value: only whether they are set.
        val profiles = runCatching { store.profiles.first() }.getOrDefault(emptyList())
        val selectedId = runCatching { store.selectedId.first() }.getOrNull()
        sb.append("Profiles (").append(profiles.size).append(")\n")
        for (profile in profiles) {
            sb.append("  - ").append(profile.name)
                .append(if (profile.id == selectedId) " [selected]" else "")
                .append(' ').append(profile.authority)
                .append(" sni=").append(if (profile.sni.isBlank()) "no" else "yes")
                .append(" obfs=").append(if (profile.hasObfs) "yes" else "no")
                .append(" insecure=").append(profile.insecure)
                .append(" fastOpen=").append(profile.fastOpen)
                .append(" bandwidth=").append(profile.hasBandwidth)
                .append('\n')
        }
        return sb.toString()
    }

    /** This process' logcat ring buffer, newest [LOGCAT_LINES] lines (bounded). */
    private fun readLogcat(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.ifBlank { "logcat returned nothing" }.takeLast(MAX_LOG_CHARS)
    } catch (e: Exception) {
        "logcat unavailable: ${e.message}"
    }

    private fun redactedConfig(): String {
        val config = File(context.filesDir, Hysteria2VpnService.HYSTERIA_CONFIG_FILE)
        if (!config.exists()) return "no config file on disk"
        return Redact.config(readText(config, MAX_LOG_BYTES))
    }

    /** Read at most [limit] bytes as UTF-8; never throws. */
    private fun readText(file: File, limit: Long): String {
        if (!file.exists()) return "not present"
        return try {
            file.inputStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0L
                while (total < limit) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "unreadable: ${e.message}"
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        private const val TAG = "DiagnosticsExporter"
        private const val CONFIG_ENTRY = "hysteria.config.redacted.yaml"
        private const val LOGCAT_LINES = 4000
        private const val MAX_LOG_BYTES = 512L * 1024
        private const val MAX_LOG_CHARS = 800_000

        /** Suggested file name for the SAF picker. */
        fun suggestedFileName(): String =
            "hy2droid-logs-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
    }
}
