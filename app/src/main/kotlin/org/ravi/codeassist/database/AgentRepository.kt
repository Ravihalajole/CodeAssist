package org.ravi.codeassist.database

import android.content.ContentValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentRepository(private val database: CodeAssistDatabase) {

    private val _allProfiles = MutableStateFlow<List<AgentProfile>>(emptyList())
    val allProfiles: StateFlow<List<AgentProfile>> = _allProfiles.asStateFlow()

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            refreshProfilesList()
        }
    }

    fun refreshProfilesList() {
        val list = mutableListOf<AgentProfile>()
        val db = database.readableDatabase
        db.rawQuery("SELECT * FROM agent_profiles ORDER BY profileName ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    AgentProfile(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        profileName = cursor.getString(cursor.getColumnIndexOrThrow("profileName")),
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow("packageName")),
                        isCalibrated = cursor.getInt(cursor.getColumnIndexOrThrow("isCalibrated")) == 1,
                        scrollLeftPct = cursor.getFloat(cursor.getColumnIndexOrThrow("scrollLeftPct")),
                        scrollTopPct = cursor.getFloat(cursor.getColumnIndexOrThrow("scrollTopPct")),
                        scrollRightPct = cursor.getFloat(cursor.getColumnIndexOrThrow("scrollRightPct")),
                        scrollBottomPct = cursor.getFloat(cursor.getColumnIndexOrThrow("scrollBottomPct"))
                    )
                )
            }
        }
        _allProfiles.value = list
    }

    suspend fun insertProfile(profile: AgentProfile): Long {
        val db = database.writableDatabase
        val values = ContentValues().apply {
            if (profile.id > 0) put("id", profile.id)
            put("profileName", profile.profileName)
            put("packageName", profile.packageName)
            put("isCalibrated", if (profile.isCalibrated) 1 else 0)
            put("scrollLeftPct", profile.scrollLeftPct)
            put("scrollTopPct", profile.scrollTopPct)
            put("scrollRightPct", profile.scrollRightPct)
            put("scrollBottomPct", profile.scrollBottomPct)
        }
        val result = db.insertWithOnConflict("agent_profiles", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        refreshProfilesList()
        return result
    }

    suspend fun getProfileById(id: Long): AgentProfile? {
        val db = database.readableDatabase
        db.rawQuery("SELECT * FROM agent_profiles WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) {
                return AgentProfile(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    profileName = cursor.getString(cursor.getColumnIndexOrThrow("profileName")),
                    packageName = cursor.getString(cursor.getColumnIndexOrThrow("packageName")),
                    isCalibrated = cursor.getInt(cursor.getColumnIndexOrThrow("isCalibrated")) == 1,
                    scrollLeftPct = cursor.getFloat(cursor.getColumnIndexOrThrow("scrollLeftPct")),
                    scrollTopPct = cursor.getFloat(cursor.getColumnIndexOrThrow("scrollTopPct")),
                    scrollRightPct = cursor.getFloat(cursor.getColumnIndexOrThrow("scrollRightPct")),
                    scrollBottomPct = cursor.getFloat(cursor.getColumnIndexOrThrow("scrollBottomPct"))
                )
            }
        }
        return null
    }
        
    suspend fun deleteProfile(profile: AgentProfile) {
        val db = database.writableDatabase
        db.delete("agent_profiles", "id = ?", arrayOf(profile.id.toString()))
        refreshProfilesList()
    }

    suspend fun saveSignaturesForProfile(profileId: Long, signatures: List<ElementSignature>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("element_signatures", "profileId = ?", arrayOf(profileId.toString()))
            for (sig in signatures) {
                val values = ContentValues().apply {
                    put("profileId", profileId)
                    put("role", sig.role.name)
                    put("resourceId", sig.resourceId)
                    put("contentDescription", sig.contentDescription)
                    put("hintText", sig.hintText)
                    put("boundsX", sig.boundsX)
                    put("boundsY", sig.boundsY)
                    put("hierarchyPath", sig.hierarchyPath)
                    put("className", sig.className)
                }
                db.insert("element_signatures", null, values)
            }

            val profileValues = ContentValues().apply {
                put("isCalibrated", 1)
            }
            db.update("agent_profiles", profileValues, "id = ?", arrayOf(profileId.toString()))

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshProfilesList()
    }

    suspend fun getSignaturesForProfile(profileId: Long): List<ElementSignature> {
        val list = mutableListOf<ElementSignature>()
        val db = database.readableDatabase
        db.rawQuery("SELECT * FROM element_signatures WHERE profileId = ?", arrayOf(profileId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    ElementSignature(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        profileId = cursor.getLong(cursor.getColumnIndexOrThrow("profileId")),
                        role = ElementRole.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("role"))),
                        resourceId = cursor.getString(cursor.getColumnIndexOrThrow("resourceId")),
                        contentDescription = cursor.getString(cursor.getColumnIndexOrThrow("contentDescription")),
                        hintText = cursor.getString(cursor.getColumnIndexOrThrow("hintText")),
                        boundsX = cursor.getInt(cursor.getColumnIndexOrThrow("boundsX")),
                        boundsY = cursor.getInt(cursor.getColumnIndexOrThrow("boundsY")),
                        hierarchyPath = cursor.getString(cursor.getColumnIndexOrThrow("hierarchyPath")),
                        className = cursor.getString(cursor.getColumnIndexOrThrow("className"))
                    )
                )
            }
        }
        return list
    }
}