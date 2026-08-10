package org.ravi.codeassist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ravi.codeassist.database.AgentProfile
import org.ravi.codeassist.database.AgentRepository
import org.ravi.codeassist.database.CodeAssistDatabase
import org.ravi.codeassist.utils.InstalledApp
import org.ravi.codeassist.utils.PackageManagerUtils

class AgenticViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: AgentRepository
    
    init {
        val db = CodeAssistDatabase.getDatabase(application)
        repository = AgentRepository(db)
    }

    val allProfiles = repository.allProfiles

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                PackageManagerUtils.getInstalledApplications(getApplication())
            }
            _installedApps.value = apps
        }
    }

    fun createProfile(name: String, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertProfile(AgentProfile(profileName = name, packageName = packageName))
        }
    }

    fun deleteProfile(profile: AgentProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteProfile(profile)
        }
    }

    fun updateScrollBounds(profileId: Long, left: Float, top: Float, right: Float, bottom: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.getProfileById(profileId)
            if (existing != null) {
                val updated = existing.copy(
                    scrollLeftPct = left,
                    scrollTopPct = top,
                    scrollRightPct = right,
                    scrollBottomPct = bottom
                )
                repository.insertProfile(updated)
            }
        }
    }

    fun refreshProfiles() {
        repository.refreshProfilesList()
    }

    suspend fun getSignatures(profileId: Long): List<org.ravi.codeassist.database.ElementSignature> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.getSignaturesForProfile(profileId)
        }
    }

    suspend fun exportProfileToJson(profileId: Long): String? {
        return withContext(Dispatchers.IO) {
            val profile = repository.getProfileById(profileId) ?: return@withContext null
            val signatures = repository.getSignaturesForProfile(profileId)
            org.ravi.codeassist.utils.ProfileIO.toJson(profile, signatures)
        }
    }

    suspend fun importProfileFromJson(jsonStr: String): Boolean {
        return withContext(Dispatchers.IO) {
            val parsed = org.ravi.codeassist.utils.ProfileIO.fromJson(jsonStr) ?: return@withContext false
            val profile = parsed.first
            val signatures = parsed.second

            val newProfileId = repository.insertProfile(profile)
            if (newProfileId > 0 && signatures.isNotEmpty()) {
                repository.saveSignaturesForProfile(newProfileId, signatures)
            }
            true
        }
    }
}