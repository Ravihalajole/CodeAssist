package org.ravi.codeassist

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class GitCredentialProfile(
    val name: String,
    val username: String,
    val token: String
)

object GitCredentialProfiles {
    private const val KEY_PROFILES = "GIT_CRED_PROFILES"
    private const val KEY_ACTIVE = "GIT_CRED_ACTIVE"

    fun profiles(prefs: SharedPreferences): List<GitCredentialProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isEmpty()) null else GitCredentialProfile(
                    name = name,
                    username = o.optString("username"),
                    token = o.optString("token")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(prefs: SharedPreferences, list: List<GitCredentialProfile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("username", it.username)
                    .put("token", it.token)
            )
        }
        val editor = prefs.edit().putString(KEY_PROFILES, arr.toString())
        val active = activeName(prefs)
        if (active != null && list.none { it.name == active }) {
            editor.remove(KEY_ACTIVE)
        }
        editor.apply()
    }

    fun activeName(prefs: SharedPreferences): String? = prefs.getString(KEY_ACTIVE, null)

    fun activeProfile(prefs: SharedPreferences): GitCredentialProfile? =
        activeName(prefs)?.let { name -> profiles(prefs).firstOrNull { it.name == name } }

    fun setActive(prefs: SharedPreferences, name: String) {
        prefs.edit().putString(KEY_ACTIVE, name).apply()
    }

    fun upsert(prefs: SharedPreferences, profile: GitCredentialProfile) {
        val list = profiles(prefs).toMutableList()
        val idx = list.indexOfFirst { it.name == profile.name }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        save(prefs, list)
        setActive(prefs, profile.name)
    }

    fun remove(prefs: SharedPreferences, name: String) {
        val list = profiles(prefs).filterNot { it.name == name }
        save(prefs, list)
    }

    fun labels(prefs: SharedPreferences): List<String> = profiles(prefs).map { it.name }
}
