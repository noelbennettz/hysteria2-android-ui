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
import org.json.JSONObject

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "hysteria_profiles")

/**
 * Id to keep selected after the entry at [removedIndex] was deleted: the entry
 * above it, or the new first entry when the removed one was on top (or when the
 * id was no longer in the list). `null` means nothing is left.
 */
internal fun successorAfterRemoval(remaining: List<Profile>, removedIndex: Int): String? =
    remaining.getOrNull((removedIndex - 1).coerceAtLeast(0))?.id

/**
 * Persists [Profile]s (and the selected profile id) as a single JSON array in a
 * Preferences DataStore. Replaces the previous fragile `ObjectOutputStream`
 * cache: JSON is schema-tolerant across app versions and never throws on read
 * (a corrupt payload degrades to an empty list).
 *
 * Secrets are stored as-is: the file lives in the app's private directory, which
 * no other app can read (see the Security notes in the README for the full
 * threat model). Keeping them usable is what makes a backup restore on a new
 * phone just work.
 */
class ProfileStore(private val context: Context) {
    private val keyProfiles = stringPreferencesKey("profiles_json")
    private val keySelected = stringPreferencesKey("selected_id")
    private val keyLatency = stringPreferencesKey("latencies_json")

    /** All stored profiles, in insertion order. */
    val profiles: Flow<List<Profile>> = context.profileDataStore.data
        .map { decodeList(it[keyProfiles]) }

    /** The id of the currently selected profile, or `null`. */
    val selectedId: Flow<String?> = context.profileDataStore.data
        .map { it[keySelected] }

    /**
     * The last measured latency per profile id, in milliseconds, dropping entries
     * older than [LATENCY_TTL_MS]: a number from last week says nothing about the
     * node's speed right now, and stale values would keep the list sorted by
     * something that is no longer true. Failed probes are not stored at all.
     */
    val latencies: Flow<Map<String, Int>> = context.profileDataStore.data
        .map { prefs ->
            val now = System.currentTimeMillis()
            decodeLatencyEntries(prefs[keyLatency])
                .filterValues { now - it.measuredAt <= LATENCY_TTL_MS }
                .mapValues { it.value.millis }
        }

    /** Insert or replace [profile] by id. Selects it if nothing is selected yet. */
    suspend fun upsert(profile: Profile) {
        context.profileDataStore.edit { prefs ->
            val list = decodeList(prefs[keyProfiles]).toMutableList()
            val index = list.indexOfFirst { it.id == profile.id }
            if (index >= 0) list[index] = profile else list.add(profile)
            prefs[keyProfiles] = encodeList(list)
            if (prefs[keySelected] == null) prefs[keySelected] = profile.id
        }
    }

    /**
     * Remove the profile with [id]. When it was the selected one the selection
     * moves to the entry *above* it, so a Quick Settings tap right after a
     * delete connects the node next to the one that just went away instead of
     * jumping to the top of the list.
     */
    suspend fun delete(id: String) {
        context.profileDataStore.edit { prefs ->
            val current = decodeList(prefs[keyProfiles])
            val removedIndex = current.indexOfFirst { it.id == id }
            val list = current.filterNot { it.id == id }
            prefs[keyProfiles] = encodeList(list)
            // The cached latency of a deleted node is dead weight.
            val entries = decodeLatencyEntries(prefs[keyLatency]).toMutableMap()
            if (entries.remove(id) != null) {
                prefs[keyLatency] = encodeLatencyEntries(entries)
            }
            if (prefs[keySelected] == id) {
                val next = successorAfterRemoval(list, removedIndex)
                if (next == null) prefs.remove(keySelected) else prefs[keySelected] = next
            }
        }
    }

    /** Store (or, with `null`, forget) the measured latency of [id]. */
    suspend fun setLatency(id: String, millis: Int?) {
        context.profileDataStore.edit { prefs ->
            val entries = decodeLatencyEntries(prefs[keyLatency]).toMutableMap()
            if (millis == null) {
                entries.remove(id)
            } else {
                entries[id] = LatencyEntry(millis, System.currentTimeMillis())
            }
            prefs[keyLatency] = encodeLatencyEntries(entries)
        }
    }

    /** Mark [id] as the selected profile. */
    suspend fun select(id: String) {
        context.profileDataStore.edit { prefs -> prefs[keySelected] = id }
    }

    private fun decodeList(json: String?): List<Profile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { i -> decode(array.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Decode the latency map. Both the `{id: {ms, at}}` shape and a bare
     * `{id: millis}` are understood, so an older cache degrades instead of
     * throwing; a bare entry gets timestamp 0 and therefore counts as expired.
     */
    private fun decodeLatencyEntries(json: String?): Map<String, LatencyEntry> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                for (key in obj.keys()) {
                    val entry = obj.optJSONObject(key)
                    val millis = entry?.optInt("ms", -1) ?: obj.optInt(key, -1)
                    if (millis <= 0) continue
                    put(key, LatencyEntry(millis, entry?.optLong("at", 0L) ?: 0L))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Encode as `{id: {ms, at}}`, keeping each entry's own timestamp. */
    private fun encodeLatencyEntries(entries: Map<String, LatencyEntry>): String {
        val obj = JSONObject()
        for ((id, entry) in entries) {
            obj.put(id, JSONObject().put("ms", entry.millis).put("at", entry.measuredAt))
        }
        return obj.toString()
    }

    private fun encodeList(profiles: List<Profile>): String {
        val array = JSONArray()
        profiles.forEach { array.put(encode(it)) }
        return array.toString()
    }

    private fun encode(p: Profile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("server", p.server)
        put("auth", p.auth)
        put("sni", p.sni)
        put("obfsPassword", p.obfsPassword)
        put("insecure", p.insecure)
        put("txMbps", p.txMbps)
        put("rxMbps", p.rxMbps)
        put("fastOpen", p.fastOpen)
        put("port", p.port)
    }

    /** One cached measurement: the value and when it was taken. */
    private data class LatencyEntry(val millis: Int, val measuredAt: Long)

    private fun decode(o: JSONObject): Profile = Profile(
        id = o.getString("id"),
        name = o.optString("name"),
        server = o.optString("server"),
        auth = usable(o.optString("auth")),
        sni = o.optString("sni"),
        obfsPassword = usable(o.optString("obfsPassword")),
        insecure = o.optBoolean("insecure", false),
        txMbps = o.optInt("txMbps", 0),
        rxMbps = o.optInt("rxMbps", 0),
        fastOpen = o.optBoolean("fastOpen", false),
        port = o.optInt("port", 443),
    )

    /**
     * A short-lived build sealed secrets with a Keystore key that no longer
     * exists; such a value would be copied into the core config as junk, so it
     * degrades to "no secret" and the user retypes it once.
     */
    private fun usable(stored: String): String =
        if (stored.startsWith(SEALED_PREFIX)) "" else stored

    private companion object {
        /** Envelope prefix of that withdrawn experiment; never written again. */
        const val SEALED_PREFIX = "enc:v1:"

        /** How long a measured latency stays meaningful (24 h). */
        const val LATENCY_TTL_MS = 24L * 60 * 60 * 1000
    }
}
