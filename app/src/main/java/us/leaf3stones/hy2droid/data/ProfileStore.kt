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
 * Persists [Profile]s (and the selected profile id) as a single JSON array in a
 * Preferences DataStore. Replaces the previous fragile `ObjectOutputStream`
 * cache: JSON is schema-tolerant across app versions and never throws on read
 * (a corrupt payload degrades to an empty list).
 *
 * The two secrets (`auth`, obfs password) are sealed with [CredentialCipher]
 * before they are written, so the file on disk — and any backup of it — holds
 * ciphertext. Values written by older versions are still readable and get
 * rewritten in place by [encryptLegacyCredentials].
 */
class ProfileStore(
    private val context: Context,
    private val cipher: CredentialCipher = CredentialCipher.withAndroidKeystore(),
) {
    private val keyProfiles = stringPreferencesKey("profiles_json")
    private val keySelected = stringPreferencesKey("selected_id")

    /** All stored profiles, in insertion order. */
    val profiles: Flow<List<Profile>> = context.profileDataStore.data
        .map { decodeList(it[keyProfiles]) }

    /** The id of the currently selected profile, or `null`. */
    val selectedId: Flow<String?> = context.profileDataStore.data
        .map { it[keySelected] }

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

    /** Remove the profile with [id]; reselect the first remaining profile if it was selected. */
    suspend fun delete(id: String) {
        context.profileDataStore.edit { prefs ->
            val list = decodeList(prefs[keyProfiles]).filterNot { it.id == id }
            prefs[keyProfiles] = encodeList(list)
            if (prefs[keySelected] == id) {
                val next = list.firstOrNull()?.id
                if (next == null) prefs.remove(keySelected) else prefs[keySelected] = next
            }
        }
    }

    /** Mark [id] as the selected profile. */
    suspend fun select(id: String) {
        context.profileDataStore.edit { prefs -> prefs[keySelected] = id }
    }

    /**
     * Rewrite credentials stored in plaintext by an older version as ciphertext.
     * Runs on every start and is a no-op once everything is sealed.
     */
    suspend fun encryptLegacyCredentials() {
        context.profileDataStore.edit { prefs ->
            val json = prefs[keyProfiles] ?: return@edit
            if (!hasPlaintextCredential(json)) return@edit
            prefs[keyProfiles] = encodeList(decodeList(json))
        }
    }

    /** True when the stored JSON still carries an unsealed credential. */
    private fun hasPlaintextCredential(json: String): Boolean = try {
        val array = JSONArray(json)
        (0 until array.length()).any { index ->
            val item = array.getJSONObject(index)
            CREDENTIAL_FIELDS.any { field ->
                val raw = item.optString(field)
                raw.isNotEmpty() && !cipher.isEncrypted(raw)
            }
        }
    } catch (_: Exception) {
        false
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

    private fun encodeList(profiles: List<Profile>): String {
        val array = JSONArray()
        profiles.forEach { array.put(encode(it)) }
        return array.toString()
    }

    private fun encode(p: Profile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("server", p.server)
        put("auth", cipher.encrypt(p.auth))
        put("sni", p.sni)
        put("obfsPassword", cipher.encrypt(p.obfsPassword))
        put("insecure", p.insecure)
        put("txMbps", p.txMbps)
        put("rxMbps", p.rxMbps)
        put("fastOpen", p.fastOpen)
        put("port", p.port)
    }

    private fun decode(o: JSONObject): Profile = Profile(
        id = o.getString("id"),
        name = o.optString("name"),
        server = o.optString("server"),
        // `null` = sealed with a key this install no longer has (restored backup,
        // cleared app data): keep the profile, make the user retype the secret.
        auth = cipher.decrypt(o.optString("auth")).orEmpty(),
        sni = o.optString("sni"),
        obfsPassword = cipher.decrypt(o.optString("obfsPassword")).orEmpty(),
        insecure = o.optBoolean("insecure", false),
        txMbps = o.optInt("txMbps", 0),
        rxMbps = o.optInt("rxMbps", 0),
        fastOpen = o.optBoolean("fastOpen", false),
        port = o.optInt("port", 443),
    )

    private companion object {
        /** Fields that must never reach the DataStore in plaintext. */
        val CREDENTIAL_FIELDS = listOf("auth", "obfsPassword")
    }
}
