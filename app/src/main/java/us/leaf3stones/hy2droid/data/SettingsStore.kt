package us.leaf3stones.hy2droid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Global app settings that are not per-profile. Currently the split-tunneling
 * bypass list: packages routed OUTSIDE the VPN (their traffic never enters the TUN).
 */
class SettingsStore(private val context: Context) {
    private val keyExcluded = stringPreferencesKey("excluded_packages")

    /** Package names excluded from the VPN (bypass). */
    val excluded: Flow<Set<String>> = context.settingsDataStore.data
        .map { decode(it[keyExcluded]) }

    /** Replace the bypass list. */
    suspend fun setExcluded(packages: Set<String>) {
        context.settingsDataStore.edit { it[keyExcluded] = encode(packages) }
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
}
