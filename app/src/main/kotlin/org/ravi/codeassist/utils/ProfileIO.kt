package org.ravi.codeassist.utils

import org.json.JSONArray
import org.json.JSONObject
import org.ravi.codeassist.database.AgentProfile
import org.ravi.codeassist.database.ElementRole
import org.ravi.codeassist.database.ElementSignature

object ProfileIO {
    fun toJson(profile: AgentProfile, signatures: List<ElementSignature>): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("profileName", profile.profileName)
        root.put("packageName", profile.packageName)

        val sigArray = JSONArray()
        signatures.forEach { sig ->
            val sObj = JSONObject()
            sObj.put("role", sig.role.name)
            sObj.put("resourceId", sig.resourceId ?: JSONObject.NULL)
            sObj.put("contentDescription", sig.contentDescription ?: JSONObject.NULL)
            sObj.put("hintText", sig.hintText ?: JSONObject.NULL)
            sObj.put("className", sig.className ?: JSONObject.NULL)
            sObj.put("boundsX", sig.boundsX)
            sObj.put("boundsY", sig.boundsY)
            sObj.put("hierarchyPath", sig.hierarchyPath ?: JSONObject.NULL)
            sigArray.put(sObj)
        }
        root.put("signatures", sigArray)
        return root.toString(4)
    }

    fun fromJson(jsonStr: String): Pair<AgentProfile, List<ElementSignature>>? {
        try {
            val root = JSONObject(jsonStr)
            val profileName = root.getString("profileName")
            val packageName = root.getString("packageName")

            val profile = AgentProfile(
                profileName = profileName,
                packageName = packageName,
                isCalibrated = true
            )

            val signatures = mutableListOf<ElementSignature>()
            if (root.has("signatures")) {
                val sigArray = root.getJSONArray("signatures")
                for (i in 0 until sigArray.length()) {
                    val sObj = sigArray.getJSONObject(i)
                    signatures.add(
                        ElementSignature(
                            profileId = 0, // Ignored; injected by Repository after profile insert
                            role = ElementRole.valueOf(sObj.getString("role")),
                            resourceId = if (sObj.isNull("resourceId")) null else sObj.getString("resourceId"),
                            contentDescription = if (sObj.isNull("contentDescription")) null else sObj.getString("contentDescription"),
                            hintText = if (sObj.isNull("hintText")) null else sObj.getString("hintText"),
                            className = if (sObj.isNull("className")) null else sObj.getString("className"),
                            boundsX = sObj.optInt("boundsX", 0),
                            boundsY = sObj.optInt("boundsY", 0),
                            hierarchyPath = if (sObj.isNull("hierarchyPath")) null else sObj.getString("hierarchyPath")
                        )
                    )
                }
            }
            return Pair(profile, signatures)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}