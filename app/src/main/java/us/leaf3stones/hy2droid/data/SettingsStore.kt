package us.leaf3stones.hy2droid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Global (non per-profile) app settings, persisted in a Preferences DataStore.
 * All keys default so a fresh install / restored backup behaves sensibly.
 */
class SettingsStore(private val context: Context) {
    private val keyExcluded = stringPreferencesKey("excluded_packages")
    private val keyDynamic = booleanPreferencesKey("dynamic_color")
    private val keyDnsEnabled = booleanPreferencesKey("custom_dns_enabled")
    private val keyDnsServer = stringPreferencesKey("custom_dns_server")
    private val keyDesired = booleanPreferencesKey("desired_connected")

    /** Package names excluded from the VPN (split-tunneling bypass). */
    val excluded: Flow<Set<String>> = context.settingsDataStore.data
        .map { decode(it[keyExcluded]) }

    /** Whether Material You dynamic color is applied. Default on. */
    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyDynamic] ?: true }

    /** Whether a custom upstream DNS server (pushed to the TUN) is used. Default off. */
    val customDnsEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyDnsEnabled] ?: false }

    /** The custom DNS server address. */
    val customDnsServer: Flow<String> = context.settingsDataStore.data
        .map { it[keyDnsServer] ?: DEFAULT_DNS }

    /** Whether the tunnel should be up (drives always-on / process-death resume). */
    val desiredConnected: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyDesired] ?: false }

    suspend fun setExcluded(packages: Set<String>) {
        context.settingsDataStore.edit { it[keyExcluded] = encode(packages) }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyDynamic] = enabled }
    }

    suspend fun setCustomDnsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyDnsEnabled] = enabled }
    }

    suspend fun setCustomDnsServer(server: String) {
        context.settingsDataStore.edit { it[keyDnsServer] = server }
    }

    suspend fun setDesiredConnected(desired: Boolean) {
        context.settingsDataStore.edit { it[keyDesired] = desired }
    }

    private fun decode(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(json)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun encode(packages: Set<String>): String {
        val array = JSONArray()
        packages.forEach { array.put(it) }
        return array.toString()
    }

    companion object {
        /** Used when custom DNS is disabled. */
        const val DEFAULT_DNS = "1.0.0.1"
    }
}
