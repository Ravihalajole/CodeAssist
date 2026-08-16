package org.ravi.codeassist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var viewWorkspace: View
    private lateinit var viewAgentic: View
    private lateinit var viewSettings: View
    
    // UI Elements
    private lateinit var tvWorkspacePath: TextView
    private lateinit var tvWorkspaceStatus: TextView
    private lateinit var switchBubble: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var tvBubbleIconStatus: TextView
    private lateinit var btnEditBubbleIcon: MaterialButton
    private lateinit var toggleAutoAllowMode: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var switchInputMode: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var btnSelectWorkspace: MaterialButton
    private lateinit var btnCloneWorkspace: MaterialButton
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var btnGitOptions: MaterialButton
    private lateinit var btnExecutionHistory: MaterialButton
    private lateinit var tvGitIdentityStatus: TextView
    private lateinit var btnEditGitConfig: MaterialButton
    private lateinit var tvGitCredStatus: TextView
    private lateinit var btnManageGitCreds: MaterialButton
    private lateinit var rowPermAllFiles: View
    private lateinit var permStatusAllFiles: TextView
    private lateinit var rowPermAccessibility: View
    private lateinit var permStatusAccessibility: TextView
    private lateinit var rowPermNotifications: View
    private lateinit var permStatusNotifications: TextView
    private lateinit var rowPermOverlay: View
    private lateinit var permStatusOverlay: TextView
    private lateinit var rowPermQsTile: View
    private lateinit var permStatusQsTile: TextView
    
    // RecyclerView Components
    private lateinit var rvLogs: RecyclerView
    private lateinit var tvEmptyLogs: TextView
    private lateinit var logsAdapter: LogsAdapter
    private lateinit var logsProgress: com.google.android.material.progressindicator.LinearProgressIndicator

    // Agentic Mode UI Elements
    private lateinit var cardHeaderActions: View
    private lateinit var btnHeaderAddProfile: MaterialButton
    private lateinit var btnHeaderImportProfile: MaterialButton
    private lateinit var layoutEmptyProfiles: View
    private lateinit var btnEmptyAddProfile: MaterialButton
    private lateinit var btnEmptyImportProfile: MaterialButton
    private lateinit var rvAgentProfiles: RecyclerView
    private lateinit var agenticViewModel: AgenticViewModel
    private lateinit var agentProfilesAdapter: AgentProfilesAdapter

    private val workspacePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val split = docId.split(":")
                val type = split[0]
                if ("primary".equals(type, ignoreCase = true)) {
                    val path = if (split.size > 1 && split[1].isNotEmpty()) {
                        android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                    } else {
                        android.os.Environment.getExternalStorageDirectory().absolutePath
                    }
                    verifyAndSaveWorkspace(uri, path)
                } else {
                    Toast.makeText(this, "Please select a directory on primary internal storage.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to resolve folder path.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Persists the SAF tree Uri alongside the resolved absolute path, then
     * verifies the resolved path is actually writable BEFORE accepting it.
     * On scoped-storage-only builds the SAF grant may not match the resolved
     * path; failing here (with a clear message) beats discovering it later
     * when every git operation silently fails. The SAF Uri is kept so a future
     * DocumentFile-based backend can recover the real grant.
     */
    private fun verifyAndSaveWorkspace(uri: Uri, path: String) {
        lifecycleScope.launch {
            val writable = withContext(Dispatchers.IO) {
                try {
                    val dir = File(path)
                    if (!dir.isDirectory) return@withContext false
                    val probe = File(dir, ".codeassist_probe_${System.currentTimeMillis()}")
                    val created = probe.createNewFile()
                    if (created) probe.delete()
                    created && dir.canWrite()
                } catch (e: Exception) {
                    false
                }
            }
            if (writable) {
                saveWorkspace(uri, path)
                refreshLogsData()
            } else {
                Toast.makeText(this@MainActivity, "Workspace path is not writable. Choose a folder under primary internal storage.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var cloneDialogParentLabel: TextView? = null

    private val cloneParentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val split = docId.split(":")
                if ("primary".equals(split.firstOrNull(), ignoreCase = true)) {
                    val path = if (split.size > 1 && split[1].isNotEmpty()) {
                        Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                    } else {
                        Environment.getExternalStorageDirectory().absolutePath
                    }
                    getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
                        .edit().putString("CLONE_PARENT_DIR", path).apply()
                    cloneDialogParentLabel?.text = path
                } else {
                    Toast.makeText(this, "Please select a directory on primary internal storage.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to resolve folder path.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importProfileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonStr = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (jsonStr != null) {
                        val success = agenticViewModel.importProfileFromJson(jsonStr)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(this@MainActivity, "Profile imported successfully!", Toast.LENGTH_SHORT).show()
                                agenticViewModel.refreshProfiles()
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to parse profile JSON. Ensure the file is valid.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for Quick-Action heads-up alerts.", Toast.LENGTH_LONG).show()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAndRequestNotificationPermission()
    }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val enabled = Settings.canDrawOverlays(this)
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        switchBubble.isChecked = enabled
        sharedPref.edit().putBoolean("BUBBLE_ENABLED", enabled).apply()
        if (enabled) {
            startService(Intent(this, FloatingBubbleService::class.java))
        } else {
            Toast.makeText(this, "Permission denied for Floating Bubble.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (this::viewWorkspace.isInitialized && viewWorkspace.visibility == View.VISIBLE) {
            refreshLogsData()
            val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
            refreshWorkspaceStatus(sharedPref.getString("WORKSPACE_ROOT", null))
        }
        if (this::viewAgentic.isInitialized && viewAgentic.visibility == View.VISIBLE) {
            agenticViewModel.refreshProfiles()
        }
        if (this::permStatusAllFiles.isInitialized) {
            refreshPermissionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Exit CodeAssist")
                    .setMessage("Are you sure you want to close the app?")
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        
        GitManager.registerContext(this)

        // Bind Views
        viewWorkspace = findViewById(R.id.viewWorkspace)
        viewAgentic = findViewById(R.id.viewAgentic)
        viewSettings = findViewById(R.id.viewSettings)
        
        cardHeaderActions = findViewById(R.id.cardHeaderActions)
        btnHeaderAddProfile = findViewById(R.id.btnHeaderAddProfile)
        btnHeaderImportProfile = findViewById(R.id.btnHeaderImportProfile)
        layoutEmptyProfiles = findViewById(R.id.layoutEmptyProfiles)
        btnEmptyAddProfile = findViewById(R.id.btnEmptyAddProfile)
        btnEmptyImportProfile = findViewById(R.id.btnEmptyImportProfile)
        rvAgentProfiles = findViewById(R.id.rvAgentProfiles)
        
        agenticViewModel = ViewModelProvider(this)[AgenticViewModel::class.java]
        
        tvWorkspacePath = findViewById(R.id.tvWorkspacePath)
        tvWorkspaceStatus = findViewById(R.id.tvWorkspaceStatus)
        switchBubble = findViewById(R.id.switchBubble)
        tvBubbleIconStatus = findViewById(R.id.tvBubbleIconStatus)
        btnEditBubbleIcon = findViewById(R.id.btnEditBubbleIcon)
        toggleAutoAllowMode = findViewById(R.id.toggleAutoAllowMode)
        switchInputMode = findViewById(R.id.switchInputMode)
        btnSelectWorkspace = findViewById(R.id.btnSelectWorkspace)
        btnCloneWorkspace = findViewById(R.id.btnCloneWorkspace)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        btnGitOptions = findViewById(R.id.btnGitOptions)
        btnExecutionHistory = findViewById(R.id.btnExecutionHistory)
        rvLogs = findViewById(R.id.rvLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)
        logsProgress = findViewById(R.id.logsProgress)
        tvGitIdentityStatus = findViewById(R.id.tvGitIdentityStatus)
        btnEditGitConfig = findViewById(R.id.btnEditGitConfig)
        tvGitCredStatus = findViewById(R.id.tvGitCredStatus)
        btnManageGitCreds = findViewById(R.id.btnManageGitCreds)
        rowPermAllFiles = findViewById(R.id.rowPermAllFiles)
        permStatusAllFiles = findViewById(R.id.permStatusAllFiles)
        rowPermAccessibility = findViewById(R.id.rowPermAccessibility)
        permStatusAccessibility = findViewById(R.id.permStatusAccessibility)
        rowPermNotifications = findViewById(R.id.rowPermNotifications)
        permStatusNotifications = findViewById(R.id.permStatusNotifications)
        rowPermOverlay = findViewById(R.id.rowPermOverlay)
        permStatusOverlay = findViewById(R.id.permStatusOverlay)
        rowPermQsTile = findViewById(R.id.rowPermQsTile)
        permStatusQsTile = findViewById(R.id.permStatusQsTile)

        // Setup RecyclerView
        rvLogs.layoutManager = LinearLayoutManager(this)
        logsAdapter = LogsAdapter(emptyList()) { commit ->
            showCommitDetailsDialog(commit)
        }
        rvLogs.adapter = logsAdapter
        
        // Setup Agentic RecyclerView
        rvAgentProfiles.layoutManager = LinearLayoutManager(this)
        agentProfilesAdapter = AgentProfilesAdapter(
            profiles = emptyList(),
            onCalibrate = { profile ->
                if (AgentAccessibilityService.instance == null) {
                    Toast.makeText(this, "Please enable the CodeAssist Agent in Accessibility Settings first.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    val launchIntent = packageManager.getLaunchIntentForPackage(profile.packageName)
                    if (launchIntent != null) {
                        Toast.makeText(this, "Launching app for calibration...", Toast.LENGTH_SHORT).show()
                        startActivity(launchIntent)
                        AgentAccessibilityService.instance?.startCalibration(profile.id, profile.packageName)
                    } else {
                        Toast.makeText(this, "Error: Target app could not be launched.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRun = { profile -> 
                if (AgentAccessibilityService.instance == null) {
                    Toast.makeText(this, "Please enable the CodeAssist Agent in Accessibility Settings first.", Toast.LENGTH_LONG).show()
                } else {
                    lifecycleScope.launch {
                        val signatures = agenticViewModel.getSignatures(profile.id)
                        if (signatures.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Profile is incomplete. Please calibrate first.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        
                        val launchIntent = packageManager.getLaunchIntentForPackage(profile.packageName)
                        if (launchIntent != null) {
                            org.ravi.codeassist.agent.AgentOrchestrator.initializeSession(profile, signatures)
                            Toast.makeText(this@MainActivity, "Agent loop launched for ${profile.profileName}!", Toast.LENGTH_SHORT).show()
                            startActivity(launchIntent)
                            AgentAccessibilityService.instance?.startAgentSession()
                        } else {
                            Toast.makeText(this@MainActivity, "Error: Target app could not be launched.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDelete = { profile -> 
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Delete Profile")
                    .setMessage("Are you sure you want to delete '${profile.profileName}'? This action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> agenticViewModel.deleteProfile(profile) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onExport = { profile ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val jsonStr = agenticViewModel.exportProfileToJson(profile.id)
                    if (jsonStr != null) {
                        try {
                            val filename = "CodeAssistProfile_${profile.profileName.replace(" ", "_")}.json"
                            val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                            val path = File(documents, "CodeAssist")
                            if (!path.exists()) path.mkdirs()
                            val file = File(path, filename)
                            file.writeText(jsonStr)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Profile exported to Documents/CodeAssist/$filename", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to export profile data.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
        rvAgentProfiles.adapter = agentProfilesAdapter

        lifecycleScope.launch {
            agenticViewModel.allProfiles.collectLatest { profiles ->
                agentProfilesAdapter.updateData(profiles)
                val isEmpty = profiles.isEmpty()
                layoutEmptyProfiles.visibility = if (isEmpty) View.VISIBLE else View.GONE
                rvAgentProfiles.visibility = if (isEmpty) View.GONE else View.VISIBLE
                cardHeaderActions.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
        }

        val openCreateDialog = {
            CreateProfileDialog().show(supportFragmentManager, "CreateProfileDialog")
        }

        val openImport = {
            importProfileLauncher.launch("*/*")
        }

        btnHeaderAddProfile.setOnClickListener { openCreateDialog() }
        btnEmptyAddProfile.setOnClickListener { openCreateDialog() }
        btnHeaderImportProfile.setOnClickListener { openImport() }
        btnEmptyImportProfile.setOnClickListener { openImport() }

        checkAndRequestStoragePermission()
        loadCurrentWorkspace()

        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)

        // Setup Bubble Settings
        switchBubble.isChecked = sharedPref.getBoolean("BUBBLE_ENABLED", false)

        val initialName = sharedPref.getString("GIT_AUTHOR_NAME", "CodeAssist AI")
        val initialEmail = sharedPref.getString("GIT_AUTHOR_EMAIL", "ai@codeassist.local")
        tvGitIdentityStatus.text = "$initialName ($initialEmail)"

        val bubbleOptions = arrayOf(
            "Current Icon",
            "Current Icon (Dark Background)",
            "Current Icon (Dark Icon)",
            "App Icon",
            "App Icon Monochrome Dark",
            "App Icon Monochrome Light"
        )
        val currentIconStyle = sharedPref.getInt("BUBBLE_ICON_STYLE", 0)
        tvBubbleIconStatus.text = bubbleOptions.getOrElse(currentIconStyle) { "Current Icon" }

        btnEditBubbleIcon.setOnClickListener {
            val checkedItem = sharedPref.getInt("BUBBLE_ICON_STYLE", 0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Select Bubble Icon Style")
                .setSingleChoiceItems(bubbleOptions, checkedItem) { dialog, which ->
                    sharedPref.edit().putInt("BUBBLE_ICON_STYLE", which).apply()
                    tvBubbleIconStatus.text = bubbleOptions[which]
                    
                    if (sharedPref.getBoolean("BUBBLE_ENABLED", false) && Settings.canDrawOverlays(this)) {
                        stopService(Intent(this, FloatingBubbleService::class.java))
                        startService(Intent(this, FloatingBubbleService::class.java))
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val currentAutoModeStr = sharedPref.getString("AUTO_ALLOW_MODE", org.ravi.codeassist.AutoAllowMode.READ_ONLY.name)
        when (currentAutoModeStr) {
            org.ravi.codeassist.AutoAllowMode.NONE.name -> toggleAutoAllowMode.check(R.id.btnAllowNone)
            org.ravi.codeassist.AutoAllowMode.READ_WRITE.name -> toggleAutoAllowMode.check(R.id.btnAllowWrite)
            else -> toggleAutoAllowMode.check(R.id.btnAllowRead)
        }
        
        toggleAutoAllowMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnAllowNone -> org.ravi.codeassist.AutoAllowMode.NONE.name
                    R.id.btnAllowWrite -> org.ravi.codeassist.AutoAllowMode.READ_WRITE.name
                    else -> org.ravi.codeassist.AutoAllowMode.READ_ONLY.name
                }
                sharedPref.edit().putString("AUTO_ALLOW_MODE", mode).apply()
            }
        }

        switchInputMode.isChecked = sharedPref.getString("PREF_INPUT_MODE", "DIRECT") == "DIRECT"
        switchInputMode.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putString("PREF_INPUT_MODE", if (isChecked) "DIRECT" else "CLIPBOARD").apply()
        }

        findViewById<View>(R.id.rowPolicyRules)?.setOnClickListener { showPolicyEditor(sharedPref) }

        btnEditGitConfig.setOnClickListener {
            val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_config, null)
            val etDialogGitName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogGitName)
            val etDialogGitEmail = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogGitEmail)

            etDialogGitName.setText(sharedPref.getString("GIT_AUTHOR_NAME", "CodeAssist AI"))
            etDialogGitEmail.setText(sharedPref.getString("GIT_AUTHOR_EMAIL", "ai@codeassist.local"))

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Edit Git Identity")
                .setView(dialogView)
                .setPositiveButton("Save") { _, _ ->
                    val name = etDialogGitName.text?.toString()?.trim() ?: ""
                    val email = etDialogGitEmail.text?.toString()?.trim() ?: ""
                    if (name.isNotEmpty() && email.isNotEmpty()) {
                        sharedPref.edit()
                            .putString("GIT_AUTHOR_NAME", name)
                            .putString("GIT_AUTHOR_EMAIL", email)
                            .apply()
                        tvGitIdentityStatus.text = "$name ($email)"
                        Toast.makeText(this, "Git identity saved successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Configuration fields cannot be blank.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnManageGitCreds.setOnClickListener { showCredentialProfilesDialog() }
        refreshGitCredStatus()
        rowPermAllFiles.setOnClickListener { launchStorageSettingsIntent() }
        rowPermAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        rowPermNotifications.setOnClickListener { checkAndRequestNotificationPermission() }
        rowPermOverlay.setOnClickListener {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        rowPermQsTile.setOnClickListener { requestAddQsTile() }
        refreshPermissionStatus()

        var isProgrammaticChange = false
        switchBubble.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    isProgrammaticChange = true
                    switchBubble.isChecked = false
                    isProgrammaticChange = false
                    overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    sharedPref.edit().putBoolean("BUBBLE_ENABLED", true).apply()
                    startService(Intent(this, FloatingBubbleService::class.java))
                }
            } else {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Disable Agent Mode")
                    .setMessage("Are you sure you want to stop the background agent bubble?")
                    .setPositiveButton("Stop") { _, _ ->
                        sharedPref.edit().putBoolean("BUBBLE_ENABLED", false).apply()
                        stopService(Intent(this, FloatingBubbleService::class.java))
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        isProgrammaticChange = true
                        switchBubble.isChecked = true
                        isProgrammaticChange = false
                    }
                    .setOnCancelListener {
                        isProgrammaticChange = true
                        switchBubble.isChecked = true
                        isProgrammaticChange = false
                    }
                    .show()
            }
        }

        // Start bubble on launch if enabled
        if (switchBubble.isChecked && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingBubbleService::class.java))
        }

        // Setup Button Listeners
        btnSelectWorkspace.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Please grant All Files Access permission first.", Toast.LENGTH_LONG).show()
                checkAndRequestStoragePermission()
            } else {
                workspacePickerLauncher.launch(null)
            }
        }

        btnCloneWorkspace.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Please grant All Files Access permission first.", Toast.LENGTH_LONG).show()
                checkAndRequestStoragePermission()
            } else {
                showCloneDialog()
            }
        }

        btnExecutionHistory.setOnClickListener {
            showExecutionHistoryDialog()
        }

        btnGitOptions.setOnClickListener { showGitActionsDialog() }

        // Setup Bottom Navigation Logic
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_workspace -> {
                    showWorkspaceScreen()
                    true
                }
                R.id.nav_agentic -> {
                    showAgenticScreen()
                    true
                }
                R.id.nav_settings -> {
                    showSettingsScreen()
                    true
                }
                else -> false
            }
        }

        // Select Workspace by default
        if (savedInstanceState == null) {
            bottomNavigation.selectedItemId = R.id.nav_workspace
        }
    }
            
    // --- SCREEN NAVIGATION ---

    private fun showWorkspaceScreen() {
        updateScreenVisibilities(workspace = View.VISIBLE)
        refreshLogsData()
    }

    private fun showAgenticScreen() {
        updateScreenVisibilities(agentic = View.VISIBLE)
    }

    private fun showSettingsScreen() {
        updateScreenVisibilities(settings = View.VISIBLE)
    }
        
    private fun refreshLogsData() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        if (!workspaceRoot.isNullOrEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val commits = GitManager.getCommitHistory(File(workspaceRoot))
                withContext(Dispatchers.Main) {
                    logsAdapter.updateData(commits)
                    tvEmptyLogs.visibility = if (commits.isEmpty()) View.VISIBLE else View.GONE
                    if (commits.isNotEmpty()) rvLogs.scrollToPosition(0)
                }
            }
        } else {
            logsAdapter.updateData(emptyList())
            tvEmptyLogs.visibility = View.VISIBLE
            tvEmptyLogs.text = "Workspace not set."
        }
    }

    private fun updateScreenVisibilities(
        workspace: Int = View.GONE,
        agentic: Int = View.GONE,
        settings: Int = View.GONE
    ) {
        viewWorkspace.visibility = workspace
        viewAgentic.visibility = agentic
        viewSettings.visibility = settings
    }

    // --- ACTIONS ---

    private fun showPolicyEditor(sharedPref: android.content.SharedPreferences) {
        val input = android.widget.EditText(this).apply {
            setText(org.ravi.codeassist.agent.AgentPolicy.rulesFor(sharedPref).joinToString("\n") { it.render() })
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHint("deny PATCH:**/secrets/**\ndeny DELETE:*.env")
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_hi))
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(input, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        scroll.setPadding(padding, padding, padding, padding)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Policy Rules")
            .setMessage("One rule per line: deny COMMAND:PATH-GLOB (uppercase command, * wildcards, e.g. deny PATCH:**/secrets/**). Only DENY is enforced; DELETE/MOVE always keep human confirmation.")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val (rules, errors) = org.ravi.codeassist.agent.AgentPolicy.parseLines(
                    input.text?.toString()?.lines() ?: emptyList()
                )
                if (errors.isNotEmpty()) {
                    Toast.makeText(this, "Ignored ${errors.size} malformed rule(s).", Toast.LENGTH_SHORT).show()
                }
                org.ravi.codeassist.agent.AgentPolicy.saveRules(sharedPref, rules)
                if (rules.isNotEmpty()) {
                    Toast.makeText(this, "Saved ${rules.size} policy rule(s).", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class GitActionsContext(
        val workspaceRoot: String?,
        val overview: GitManager.ChangesOverview?,
        val status: GitManager.WorkspaceStatus?,
        val commits: List<GitManager.CommitInfo>,
        val remotes: List<Pair<String, String>>,
        val otherBranches: List<String>,
        val stashCount: Int,
        val checkpointCount: Int,
        val commitsAhead: Int?
    )

    private fun showGitActionsDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        lifecycleScope.launch(Dispatchers.IO) {
            val rootFile = workspaceRoot?.let { File(it) }
            val status = rootFile?.let { GitManager.getWorkspaceStatus(it) }
            val branches = rootFile?.let { GitManager.listBranches(it) } ?: emptyList()
            val currentBranch = status?.branch
            val context = GitActionsContext(
                workspaceRoot = workspaceRoot,
                overview = rootFile?.let { GitManager.changesOverview(it) },
                status = status,
                commits = rootFile?.let { GitManager.getCommitHistory(it).take(20) } ?: emptyList(),
                remotes = rootFile?.let { GitManager.listRemoteDetails(it) } ?: emptyList(),
                otherBranches = branches.filterNot { it == currentBranch },
                stashCount = rootFile?.let { GitManager.stashCount(it) } ?: 0,
                checkpointCount = rootFile?.let { GitManager.listCheckpoints(it).size } ?: 0,
                commitsAhead = rootFile?.let { GitManager.commitsAhead(it, currentBranch) }
            )
            withContext(Dispatchers.Main) {
                showGitActionsDialogContent(context)
            }
        }
    }

    private fun showGitActionsDialogContent(ctx: GitActionsContext) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_actions, null)
        val hasWorkspace = !ctx.workspaceRoot.isNullOrEmpty()
        dialogView.findViewById<TextView>(R.id.tvGitDialogWorkspace).text =
            ctx.workspaceRoot?.let { getString(R.string.git_actions_workspace_desc, it) }
                ?: getString(R.string.workspace_not_set)

        val overview = ctx.overview
        val status = ctx.status
        val commits = ctx.commits
        val allChanges = overview?.let { it.staged + it.unstaged + it.untracked } ?: emptyList()
        val totalAdded = allChanges.sumOf { it.added }
        val totalRemoved = allChanges.sumOf { it.removed }
        val headSummary = commits.firstOrNull()?.message?.lines()?.firstOrNull()?.trim().orEmpty()
        val conflictCount = status?.conflictCount ?: 0
        val hasChanges = allChanges.isNotEmpty()
        val hasRemotes = ctx.remotes.isNotEmpty()

        val dot = dialogView.findViewById<View>(R.id.vGitSummaryDot)
        val summaryTitle = dialogView.findViewById<TextView>(R.id.tvGitSummaryTitle)
        val summarySub = dialogView.findViewById<TextView>(R.id.tvGitSummarySub)
        val branchLabel = status?.branch ?: getString(R.string.git_branch_detached)
        when {
            !hasWorkspace -> {
                dot.background?.setTint(androidx.core.content.ContextCompat.getColor(this, R.color.text_mid))
                summaryTitle.text = getString(R.string.git_summary_no_workspace)
                summarySub.text = getString(R.string.workspace_not_set)
            }
            conflictCount > 0 -> {
                dot.background?.setTint(androidx.core.content.ContextCompat.getColor(this, R.color.state_red))
                summaryTitle.text = branchLabel
                summarySub.text = getString(R.string.git_summary_conflicts, conflictCount)
            }
            hasChanges -> {
                dot.background?.setTint(androidx.core.content.ContextCompat.getColor(this, R.color.state_amber))
                summaryTitle.text = branchLabel
                summarySub.text = getString(R.string.git_summary_changes, allChanges.size, totalAdded, totalRemoved)
            }
            else -> {
                dot.background?.setTint(androidx.core.content.ContextCompat.getColor(this, R.color.state_green))
                summaryTitle.text = branchLabel
                summarySub.text = getString(R.string.git_summary_clean)
            }
        }

        dialogView.findViewById<TextView>(R.id.tvGitCommitStatus).text = when {
            conflictCount > 0 -> getString(R.string.git_commit_conflicts, conflictCount)
            !hasChanges -> getString(R.string.git_summary_clean)
            else -> {
                val staged = overview?.staged?.size ?: 0
                if (staged > 0) {
                    getString(R.string.git_status_changes_staged, allChanges.size, totalAdded, totalRemoved, staged)
                } else {
                    getString(R.string.git_status_changes, allChanges.size, totalAdded, totalRemoved)
                }
            }
        }
        val ahead = ctx.commitsAhead
        dialogView.findViewById<TextView>(R.id.tvGitPushStatus).text = when {
            !hasRemotes -> getString(R.string.no_remotes)
            ahead == null -> getString(R.string.git_push_no_upstream)
            ahead > 0 -> getString(R.string.git_push_ahead, ahead)
            else -> getString(R.string.git_push_up_to_date)
        }
        dialogView.findViewById<TextView>(R.id.tvGitPullStatus).text =
            if (hasRemotes) getString(R.string.git_pull_from, ctx.remotes.first().first)
            else getString(R.string.no_remotes)
        dialogView.findViewById<TextView>(R.id.tvGitFetchStatus).text =
            if (hasRemotes) getString(R.string.git_fetch_remotes, ctx.remotes.size, ctx.remotes.joinToString(", ") { it.first })
            else getString(R.string.no_remotes)
        dialogView.findViewById<TextView>(R.id.tvGitRevertStatus).text = getString(R.string.git_status_head, headSummary)
        dialogView.findViewById<TextView>(R.id.tvGitResetStatus).text = headSummary.ifEmpty { getString(R.string.git_summary_clean) }
        dialogView.findViewById<TextView>(R.id.tvGitMergeStatus).text =
            if (ctx.otherBranches.isNotEmpty()) getString(R.string.git_merge_branches, ctx.otherBranches.size)
            else getString(R.string.git_merge_none)
        dialogView.findViewById<TextView>(R.id.tvGitDiscardStatus).text =
            if (hasChanges) getString(R.string.git_action_discard_desc) else getString(R.string.git_summary_clean)
        dialogView.findViewById<TextView>(R.id.tvGitBranchStatus).text =
            getString(R.string.git_status_branch, branchLabel)
        dialogView.findViewById<TextView>(R.id.tvGitStashStatus).text =
            if (ctx.stashCount > 0) getString(R.string.git_stash_saved, ctx.stashCount)
            else getString(R.string.git_stash_none)
        dialogView.findViewById<TextView>(R.id.tvGitStashPopStatus).text =
            if (ctx.stashCount > 0) getString(R.string.git_stash_pop_ready)
            else getString(R.string.git_stash_none)
        dialogView.findViewById<TextView>(R.id.tvGitCheckpointsStatus).text =
            if (ctx.checkpointCount > 0) getString(R.string.git_checkpoints_count, ctx.checkpointCount)
            else getString(R.string.git_checkpoints_none)
        dialogView.findViewById<TextView>(R.id.tvGitRemotesStatus).text = if (hasRemotes) {
            val first = ctx.remotes.first()
            val primary = "${first.first} · ${first.second}"
            if (ctx.remotes.size > 1) "$primary\n" + getString(R.string.git_remotes_more, ctx.remotes.size - 1) else primary
        } else {
            getString(R.string.no_remotes)
        }

        lateinit var dialog: android.app.Dialog
        dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.rowGitPush).setOnClickListener {
            dialog.dismiss()
            openPushDialog()
        }
        dialogView.findViewById<View>(R.id.rowGitPull).setOnClickListener {
            dialog.dismiss()
            openPullDialog()
        }
        dialogView.findViewById<View>(R.id.rowGitFetch).setOnClickListener {
            dialog.dismiss()
            openFetchDialog()
        }
        dialogView.findViewById<View>(R.id.rowGitCommit).setOnClickListener {
            dialog.dismiss()
            showManualCommitDialog()
        }
        dialogView.findViewById<View>(R.id.rowGitRevert).setOnClickListener {
            dialog.dismiss()
            handleGitUndoAction(commits)
        }
        dialogView.findViewById<View>(R.id.rowGitReset).setOnClickListener {
            dialog.dismiss()
            showResetDialog(commits)
        }
        dialogView.findViewById<View>(R.id.rowGitMerge).setOnClickListener {
            dialog.dismiss()
            showMergeDialog()
        }
        dialogView.findViewById<View>(R.id.rowGitDiscard).setOnClickListener {
            dialog.dismiss()
            confirmDiscardChanges()
        }
        dialogView.findViewById<View>(R.id.rowGitBranch).setOnClickListener {
            dialog.dismiss()
            showBranchDialog()
        }
        dialogView.findViewById<View>(R.id.rowGitStash).setOnClickListener {
            dialog.dismiss()
            confirmStashSave()
        }
        dialogView.findViewById<View>(R.id.rowGitStashPop).setOnClickListener {
            dialog.dismiss()
            confirmStashPop()
        }
        dialogView.findViewById<View>(R.id.rowGitCheckpoints).setOnClickListener {
            dialog.dismiss()
            showCheckpointsDialog()
        }
        dialogView.findViewById<View>(R.id.rowGitRemotes).setOnClickListener {
            dialog.dismiss()
            showRemotesDialog()
        }
        dialogView.findViewById<MaterialButton>(R.id.btnGitDialogClose).setOnClickListener { dialog.dismiss() }

        applyGitRowAvailability(
            dialogView,
            hasWorkspace = hasWorkspace,
            hasRemotes = hasRemotes,
            hasChanges = hasChanges,
            hasOtherBranches = ctx.otherBranches.isNotEmpty(),
            hasStashes = ctx.stashCount > 0
        )

        dialog.show()
    }

    private fun applyGitRowAvailability(
        dialogView: View,
        hasWorkspace: Boolean,
        hasRemotes: Boolean,
        hasChanges: Boolean,
        hasOtherBranches: Boolean,
        hasStashes: Boolean
    ) {
        setGitRowEnabled(dialogView, R.id.rowGitPush, hasWorkspace && hasRemotes)
        setGitRowEnabled(dialogView, R.id.rowGitPull, hasWorkspace && hasRemotes)
        setGitRowEnabled(dialogView, R.id.rowGitFetch, hasWorkspace && hasRemotes)
        setGitRowEnabled(dialogView, R.id.rowGitCommit, hasWorkspace)
        setGitRowEnabled(dialogView, R.id.rowGitRevert, hasWorkspace)
        setGitRowEnabled(dialogView, R.id.rowGitReset, hasWorkspace)
        setGitRowEnabled(dialogView, R.id.rowGitMerge, hasWorkspace && hasOtherBranches)
        setGitRowEnabled(dialogView, R.id.rowGitDiscard, hasWorkspace && hasChanges)
        setGitRowEnabled(dialogView, R.id.rowGitBranch, hasWorkspace)
        setGitRowEnabled(dialogView, R.id.rowGitStash, hasWorkspace && hasChanges)
        setGitRowEnabled(dialogView, R.id.rowGitStashPop, hasWorkspace && hasStashes)
        setGitRowEnabled(dialogView, R.id.rowGitCheckpoints, hasWorkspace)
        setGitRowEnabled(dialogView, R.id.rowGitRemotes, hasWorkspace)
    }

    private fun setGitRowEnabled(dialogView: View, rowId: Int, enabled: Boolean) {
        val row = dialogView.findViewById<View>(rowId)
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.45f
    }

    private fun commitPickerItems(commits: List<GitManager.CommitInfo>, defaultRef: String, defaultLabel: String): Pair<List<String>, List<String>> {
        val labels = mutableListOf<String>()
        val refs = mutableListOf<String>()
        labels += defaultLabel
        refs += defaultRef
        commits.forEach { commit ->
            labels += "${commit.hash.take(7)} — ${commit.message.lines().firstOrNull()?.trim()?.take(56) ?: ""}"
            refs += commit.hash
        }
        return labels to refs
    }

    private fun showExecutionHistoryDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val records = org.ravi.codeassist.database.ExecutionHistory.recent(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (records.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No execution history yet.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this@MainActivity)
                val sheetView = android.view.LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.bottom_sheet_execution_history, null)
                val rv = sheetView.findViewById<RecyclerView>(R.id.rvExecutionHistory)
                rv.layoutManager = LinearLayoutManager(this@MainActivity)
                rv.adapter = ExecutionHistoryAdapter(records)
                sheetView.findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener {
                    org.ravi.codeassist.database.ExecutionHistory.clear(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Execution history cleared.", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                }
                sheet.setContentView(sheetView)
                sheet.show()
            }
        }
    }

    private fun showCommitDetailsDialog(commit: GitManager.CommitInfo) {
        val df = java.text.SimpleDateFormat("MMM dd, yyyy  hh:mm a", java.util.Locale.getDefault())
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_commit_details, null)

        val tvMessage = dialogView.findViewById<TextView>(R.id.tvDialogCommitMessage)
        val tvAuthor = dialogView.findViewById<TextView>(R.id.tvDialogCommitAuthor)
        val tvDate = dialogView.findViewById<TextView>(R.id.tvDialogCommitDate)
        val tvHash = dialogView.findViewById<TextView>(R.id.tvDialogCommitHash)
        val btnCopy = dialogView.findViewById<MaterialButton>(R.id.btnDialogCopyHash)

        tvMessage.text = commit.message
        tvAuthor.text = commit.author
        tvDate.text = df.format(java.util.Date(commit.time))
        tvHash.text = commit.hash.take(12)

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Commit Hash", commit.hash)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Hash copied!", Toast.LENGTH_SHORT).show()
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Commit Details")
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btnDialogClose)?.setOnClickListener {
            dialog.dismiss()
        }

        val rowFiles = dialogView.findViewById<View>(R.id.rowCommitFiles)
        val tvFiles = dialogView.findViewById<TextView>(R.id.tvDialogCommitFiles)
        val btnDiff = dialogView.findViewById<MaterialButton>(R.id.btnDialogDiff)

        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        if (workspaceRoot != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val changes = GitManager.commitFileChanges(File(workspaceRoot), commit.hash)
                withContext(Dispatchers.Main) {
                    if (!changes.isNullOrEmpty() && dialog.isShowing) {
                        val added = changes.sumOf { it.added }
                        val removed = changes.sumOf { it.removed }
                        val f = if (changes.size == 1) "file" else "files"
                        tvFiles.text = "${changes.size} $f changed · +$added −$removed"
                        rowFiles.visibility = View.VISIBLE
                        btnDiff.visibility = View.VISIBLE
                        val openDiff = {
                            showCommitDiffSheet(commit.hash, commit.message, changes)
                        }
                        rowFiles.setOnClickListener { openDiff() }
                        btnDiff.setOnClickListener { openDiff() }
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showCommitDiffSheet(hash: String, message: String, changes: List<GitManager.FileChange>) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_commit_diff, null)
        val content = sheetView.findViewById<LinearLayout>(R.id.llCommitDiffContent)
        val title = sheetView.findViewById<TextView>(R.id.tvCommitDiffTitle)
        val sub = sheetView.findViewById<TextView>(R.id.tvCommitDiffSub)
        val back = sheetView.findViewById<MaterialButton>(R.id.btnCommitDiffBack)

        title.text = getString(R.string.git_action_diff)
        sub.text = "${hash.take(12)} · ${message.lines().firstOrNull()?.take(48) ?: ""}"
        sheetView.findViewById<MaterialButton>(R.id.btnCommitDiffClose).setOnClickListener { sheet.dismiss() }

        fun showFileList() {
            back.visibility = View.GONE
            title.text = getString(R.string.git_action_diff)
            content.removeAllViews()
            changes.forEach { change ->
                val row = android.view.LayoutInflater.from(this).inflate(R.layout.item_git_change, content, false)
                row.findViewById<View>(R.id.cbChangeSelect).visibility = View.GONE
                val tvStatus = row.findViewById<TextView>(R.id.tvChangeStatus)
                val tvPath = row.findViewById<TextView>(R.id.tvChangePath)
                val tvStats = row.findViewById<TextView>(R.id.tvChangeStats)
                tvStatus.text = when (change.status) {
                    GitManager.ChangeStatus.ADDED -> "A"
                    GitManager.ChangeStatus.MODIFIED -> "M"
                    GitManager.ChangeStatus.DELETED -> "D"
                    GitManager.ChangeStatus.RENAMED -> "R"
                    else -> "M"
                }
                tvStatus.setTextColor(getColor(when (change.status) {
                    GitManager.ChangeStatus.ADDED -> R.color.state_green
                    GitManager.ChangeStatus.DELETED -> R.color.state_red
                    else -> R.color.state_amber
                }))
                tvPath.text = change.path
                tvStats.text = if (change.status == GitManager.ChangeStatus.DELETED) {
                    "−${change.removed}"
                } else {
                    "+${change.added} −${change.removed}"
                }
                tvStats.setTextColor(getColor(if (change.added >= change.removed) R.color.state_green else R.color.state_red))
                row.setOnClickListener {
                    val workspaceRoot = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
                        .getString("WORKSPACE_ROOT", null)
                    if (workspaceRoot == null) return@setOnClickListener
                    content.removeAllViews()
                    val loading = TextView(this)
                    loading.text = getString(R.string.diff_loading)
                    loading.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    loading.setPadding(0, 8, 0, 8)
                    content.addView(loading)
                    back.visibility = View.VISIBLE
                    title.text = change.path
                    lifecycleScope.launch(Dispatchers.IO) {
                        val diff = GitManager.fileDiff(File(workspaceRoot), hash, change.path)
                        withContext(Dispatchers.Main) {
                            content.removeAllViews()
                            if (diff.isNullOrEmpty()) {
                                val none = TextView(this@MainActivity)
                                none.text = getString(R.string.diff_unavailable)
                                none.setTextAppearance(this@MainActivity, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                                none.setPadding(0, 8, 0, 8)
                                content.addView(none)
                            } else {
                                val tv = TextView(this@MainActivity)
                                tv.typeface = android.graphics.Typeface.MONOSPACE
                                tv.setTextIsSelectable(true)
                                tv.setText(applyDiffColors(diff))
                                content.addView(tv)
                            }
                        }
                    }
                }
                content.addView(row)
            }
        }

        back.setOnClickListener { showFileList() }
        showFileList()
        sheet.setContentView(sheetView)
        sheet.show()
    }

    private fun applyDiffColors(diff: String): android.text.SpannableString {
        val maxLen = 20000
        val capped = if (diff.length > maxLen) diff.substring(0, maxLen) + "\n… diff truncated" else diff
        val spannable = android.text.SpannableString(capped)
        val green = getColor(R.color.state_green)
        val red = getColor(R.color.state_red)
        val amber = getColor(R.color.state_amber)
        val ta = theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
        val neutral = ta.getColor(0, android.graphics.Color.GRAY)
        ta.recycle()
        var idx = 0
        for (line in capped.lines()) {
            val start = idx
            val end = start + line.length
            val color = when {
                line.startsWith("diff --git") || line.startsWith("+++") || line.startsWith("---") -> amber
                line.startsWith("@@") -> amber
                line.startsWith("+") && !line.startsWith("+++") -> green
                line.startsWith("-") && !line.startsWith("---") -> red
                line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file") -> neutral
                else -> null
            }
            if (color != null) {
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(color), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            idx = end + 1
        }
        return spannable
    }

    private fun handleGitUndoAction(commits: List<GitManager.CommitInfo>) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_revert, null)
        val pick = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etRevertPick)
        val headSummary = commits.firstOrNull()?.message?.lines()?.firstOrNull()?.trim().orEmpty()
        val (labels, refs) = commitPickerItems(
            commits,
            defaultRef = "HEAD",
            defaultLabel = if (headSummary.isEmpty()) "HEAD" else "HEAD — ${headSummary.take(56)}"
        )
        pick.setSimpleItems(labels.toTypedArray())
        pick.setText(labels.first(), false)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.git_action_revert)
            .setView(dialogView)
            .setPositiveButton(R.string.git_action_revert) { _, _ ->
                val idx = labels.indexOf(pick.text?.toString())
                val hash = refs.getOrElse(idx) { "HEAD" }
                executeGitUndoAction(rootFile, hash)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showResetDialog(commits: List<GitManager.CommitInfo>) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_reset, null)
        val pick = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etResetPick)
        val (labels, refs) = commitPickerItems(
            commits,
            defaultRef = "HEAD~1",
            defaultLabel = "HEAD~1 — previous commit"
        )
        pick.setSimpleItems(labels.toTypedArray())
        pick.setText(labels.first(), false)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.git_action_reset)
            .setView(dialogView)
            .setPositiveButton(R.string.git_action_reset) { _, _ ->
                val idx = labels.indexOf(pick.text?.toString())
                val hash = refs.getOrElse(idx) { "HEAD~1" }
                executeGitResetAction(rootFile, hash)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManualCommitDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val overview = GitManager.changesOverview(rootFile)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                showCommitDialog(rootFile, overview)
            }
        }
    }

    private fun showCommitDialog(rootFile: File, overview: GitManager.ChangesOverview) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_commit, null)
        val groups = dialogView.findViewById<LinearLayout>(R.id.llCommitChangeGroups)
        val summary = dialogView.findViewById<TextView>(R.id.tvCommitChangeSummary)
        val input = dialogView.findViewById<TextInputEditText>(R.id.etCommitMessage)
        val selected = mutableMapOf<String, Boolean>()

        val all = overview.staged + overview.unstaged + overview.untracked
        val totalAdded = all.sumOf { it.added }
        val totalRemoved = all.sumOf { it.removed }
        val fileName = if (all.size == 1) "file" else "files"
        summary.text = "${all.size} $fileName · +$totalAdded −$totalRemoved"

        fun addGroup(title: String, changes: List<GitManager.FileChange>, selectable: Boolean) {
            if (changes.isEmpty()) return
            val header = android.view.LayoutInflater.from(this).inflate(R.layout.item_git_change_group, groups, false)
            header.findViewById<TextView>(R.id.tvGroupName).text = title
            header.findViewById<TextView>(R.id.tvGroupCount).text = changes.size.toString()
            groups.addView(header)
            changes.forEach { change -> addChangeRow(groups, selected, change, selectable) }
        }

        addGroup(getString(R.string.commit_group_staged), overview.staged, true)
        addGroup(getString(R.string.commit_group_unstaged), overview.unstaged, true)
        addGroup(getString(R.string.commit_group_untracked), overview.untracked, true)
        addGroup(getString(R.string.commit_group_conflicts), overview.conflicts.map { path ->
            GitManager.FileChange(path, GitManager.ChangeStatus.CONFLICTED, 0, 0)
        }, false)

        if (all.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.commit_no_changes)
            empty.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            val ta = theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
            empty.setTextColor(ta.getColor(0, android.graphics.Color.GRAY))
            ta.recycle()
            empty.setPadding(0, 8, 0, 8)
            groups.addView(empty)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.git_action_commit)
            .setView(dialogView)
            .setPositiveButton(R.string.git_action_commit) { _, _ ->
                val message = input.text?.toString()?.trim()
                if (message.isNullOrEmpty()) {
                    Toast.makeText(this, "Commit message cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val paths = selected.filterValues { it }.keys.toList()
                if (paths.isEmpty()) {
                    Toast.makeText(this, "Select at least one file to commit.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runCommitSelected(rootFile, paths, message)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addChangeRow(
        groups: LinearLayout,
        selected: MutableMap<String, Boolean>,
        change: GitManager.FileChange,
        selectable: Boolean
    ) {
        val row = android.view.LayoutInflater.from(this).inflate(R.layout.item_git_change, groups, false)
        val cb = row.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbChangeSelect)
        val tvStatus = row.findViewById<TextView>(R.id.tvChangeStatus)
        val tvPath = row.findViewById<TextView>(R.id.tvChangePath)
        val tvStats = row.findViewById<TextView>(R.id.tvChangeStats)

        val code = when (change.status) {
            GitManager.ChangeStatus.ADDED -> "A"
            GitManager.ChangeStatus.MODIFIED -> "M"
            GitManager.ChangeStatus.DELETED -> "D"
            GitManager.ChangeStatus.RENAMED -> "R"
            GitManager.ChangeStatus.UNTRACKED -> "U"
            GitManager.ChangeStatus.CONFLICTED -> "C"
        }
        val statusColor = when (change.status) {
            GitManager.ChangeStatus.ADDED, GitManager.ChangeStatus.UNTRACKED -> R.color.state_green
            GitManager.ChangeStatus.DELETED, GitManager.ChangeStatus.CONFLICTED -> R.color.state_red
            else -> R.color.state_amber
        }
        tvStatus.text = code
        tvStatus.setTextColor(getColor(statusColor))
        tvPath.text = change.path
        tvStats.text = if (change.status == GitManager.ChangeStatus.DELETED) {
            "−${change.removed}"
        } else {
            "+${change.added} −${change.removed}"
        }
        tvStats.setTextColor(getColor(if (change.added >= change.removed) R.color.state_green else R.color.state_red))

        cb.isEnabled = selectable
        cb.isChecked = selectable
        selected[change.path] = selectable
        cb.setOnCheckedChangeListener { _, isChecked -> selected[change.path] = isChecked }
        groups.addView(row)
    }

    private fun runCommitSelected(rootFile: File, paths: List<String>, message: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.commitSelected(rootFile, paths, message)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.commit_success, paths.size), Toast.LENGTH_SHORT).show()
                    refreshLogsData()
                    refreshWorkspaceStatus(rootFile.absolutePath)
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun executeManualCommit(rootFile: File, message: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            if (!GitManager.isGitInitialized(rootFile)) {
                withContext(Dispatchers.Main) {
                    logsProgress.visibility = View.GONE
                    btnGitOptions.isEnabled = true
                    Toast.makeText(this@MainActivity, "Git repository not initialized.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
            val authorName = sharedPref.getString("GIT_AUTHOR_NAME", "CodeAssist AI") ?: "CodeAssist AI"
            val authorEmail = sharedPref.getString("GIT_AUTHOR_EMAIL", "ai@codeassist.local") ?: "ai@codeassist.local"

            val commitHash = GitManager.commitAllChanges(rootFile, message, authorName, authorEmail)
            val postActionCommits = GitManager.getCommitHistory(rootFile)

            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true

                if (commitHash != null) {
                    Toast.makeText(this@MainActivity, "Committed successfully: ${commitHash.take(7)}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Nothing to commit or commit failed.", Toast.LENGTH_LONG).show()
                }

                logsAdapter.updateData(postActionCommits)
                tvEmptyLogs.visibility = if (postActionCommits.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun executeGitUndoAction(rootFile: File, targetRef: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            if (!GitManager.isGitInitialized(rootFile)) {
                withContext(Dispatchers.Main) {
                    logsProgress.visibility = View.GONE
                    btnGitOptions.isEnabled = true
                    Toast.makeText(this@MainActivity, "Git repository not initialized.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
                
            val actionSuccess = GitManager.revertCommit(rootFile, targetRef)
            val actionName = "Revert $targetRef"
            val postActionCommits = GitManager.getCommitHistory(rootFile)

            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true

                if (actionSuccess) {
                    Toast.makeText(this@MainActivity, "$actionName processed successfully.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "$actionName transaction rejected or failed.", Toast.LENGTH_LONG).show()
                }

                logsAdapter.updateData(postActionCommits)
                tvEmptyLogs.visibility = if (postActionCommits.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun executeGitResetAction(rootFile: File, targetRef: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            if (!GitManager.isGitInitialized(rootFile)) {
                withContext(Dispatchers.Main) {
                    logsProgress.visibility = View.GONE
                    btnGitOptions.isEnabled = true
                    Toast.makeText(this@MainActivity, "Git repository not initialized.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val actionSuccess = GitManager.resetHardToCommit(rootFile, targetRef)
            val postActionCommits = GitManager.getCommitHistory(rootFile)

            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true

                if (actionSuccess) {
                    Toast.makeText(this@MainActivity, "Hard Reset to $targetRef successful.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Hard Reset failed. Hash might be invalid.", Toast.LENGTH_LONG).show()
                }

                logsAdapter.updateData(postActionCommits)
                tvEmptyLogs.visibility = if (postActionCommits.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // --- GIT CLONE ---

    private fun showCloneDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val defaultParent = File(Environment.getExternalStorageDirectory(), "Documents/CodeAssist/repos").absolutePath
        val parentDir = sharedPref.getString("CLONE_PARENT_DIR", null) ?: defaultParent

        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_clone, null)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etCloneUrl)
        val etFolder = dialogView.findViewById<TextInputEditText>(R.id.etCloneFolder)
        val tvParent = dialogView.findViewById<TextView>(R.id.tvCloneParent)
        val etBranch = dialogView.findViewById<TextInputEditText>(R.id.etCloneBranch)
        val etDepth = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etCloneDepth)
        val etCred = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etCloneCred)
        cloneDialogParentLabel = tvParent

        tvParent.text = parentDir
        etDepth.setSimpleItems(arrayOf(
            getString(R.string.clone_depth_full), "1", "5", "20", "50", "100"
        ))
        etDepth.setText("1", false)
        configureCredDropdown(etCred, defaultActive = false)

        var lastSlug: String? = null
        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val slug = repoNameFromUrl(s?.toString() ?: return)
                if (slug == null) return
                val currentFolder = etFolder.text?.toString()?.trim()
                if (currentFolder.isNullOrEmpty() || currentFolder == lastSlug) {
                    lastSlug = slug
                    etFolder.setText(slug)
                }
            }
        })

        dialogView.findViewById<MaterialButton>(R.id.btnCloneChangeParent).setOnClickListener {
            cloneParentLauncher.launch(null)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clone_title)
            .setView(dialogView)
            .setPositiveButton(R.string.clone_action) { _, _ ->
                val url = etUrl.text?.toString()?.trim().orEmpty()
                val folder = etFolder.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) {
                    Toast.makeText(this, "Enter a repository URL.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (folder.isEmpty()) {
                    Toast.makeText(this, "Enter a folder name.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val branch = etBranch.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val depth = when (etDepth.text?.toString()?.trim()) {
                    getString(R.string.clone_depth_full) -> -1
                    else -> etDepth.text?.toString()?.trim()?.toIntOrNull() ?: -1
                }
                val (username, token) = resolveCredProfile(etCred)
                runClone(url, File(parentDir, folder), branch, depth, username, token)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun repoNameFromUrl(url: String): String? {
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty()) return null
        val lastSegment = when {
            clean.contains("://") -> clean.substringAfterLast('/')
            clean.contains("@") && clean.contains(':') -> clean.substringAfterLast(':').substringAfterLast('/')
            else -> clean.substringAfterLast('/')
        }
        val name = lastSegment.removeSuffix(".git")
        return name.ifEmpty { null }
    }

    private fun runClone(url: String, destDir: File, branch: String?, depth: Int, username: String?, token: String?) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false
        btnCloneWorkspace.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val result = GitManager.cloneRepository(url, destDir, branch, depth, username, token)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                btnCloneWorkspace.isEnabled = true
                if (result.ok) {
                    val clonedPath = result.path?.absolutePath ?: return@withContext
                    getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("WORKSPACE_ROOT", clonedPath)
                        .remove("WORKSPACE_SAF_URI")
                        .apply()
                    tvWorkspacePath.text = File(clonedPath).name
                    refreshWorkspaceStatus(clonedPath)
                    refreshLogsData()
                    Toast.makeText(this@MainActivity, getString(R.string.clone_success, clonedPath), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, result.error ?: "Clone failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- GIT PUSH ---

    private fun openPushDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        if (workspaceRoot.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.push_no_workspace), Toast.LENGTH_SHORT).show()
            return
        }
        val rootFile = File(workspaceRoot)
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val isRepo = GitManager.isGitInitialized(rootFile)
            val remotes = GitManager.listRemotes(rootFile)
            val branches = GitManager.listBranches(rootFile)
            val currentBranch = GitManager.getWorkspaceStatus(rootFile).branch
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (!isRepo) {
                    Toast.makeText(this@MainActivity, getString(R.string.push_not_repo), Toast.LENGTH_SHORT).show()
                } else if (remotes.isEmpty() && branches.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No remotes or branches found. Configure a remote first.", Toast.LENGTH_LONG).show()
                } else {
                    showPushDialog(workspaceRoot, remotes, branches, currentBranch)
                }
            }
        }
    }

    private fun showPushDialog(workspaceRoot: String, remotes: List<String>, branches: List<String>, currentBranch: String?) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_push, null)
        val etRemote = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etPushRemote)
        val etBranch = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etPushBranch)
        val etCred = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etPushCred)

        if (remotes.isNotEmpty()) {            etRemote.setSimpleItems(remotes.toTypedArray())
            etRemote.setText(if ("origin" in remotes) "origin" else remotes.first(), false)
        }
        if (branches.isNotEmpty()) {
            etBranch.setSimpleItems(branches.toTypedArray())
            etBranch.setText(currentBranch?.takeIf { it in branches } ?: branches.first(), false)
        }
        configureCredDropdown(etCred, defaultActive = true)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.push_title)
            .setView(dialogView)
            .setPositiveButton(R.string.push_action) { _, _ ->
                val remote = etRemote.text?.toString()?.trim().orEmpty()
                val branch = etBranch.text?.toString()?.trim().orEmpty()
                val (username, token) = resolveCredProfile(etCred)
                if (remote.isEmpty()) {
                    Toast.makeText(this, "Select or type a remote.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (branch.isEmpty()) {
                    Toast.makeText(this, "Select a branch to push.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runPush(File(workspaceRoot), remote, branch, username, token)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runPush(rootFile: File, remote: String, branch: String, username: String?, token: String?) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.pushToRemote(rootFile, remote, branch, username, token)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.push_success, branch, remote), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
                refreshLogsData()
                refreshWorkspaceStatus(rootFile.absolutePath)
            }
        }
    }

    // --- GIT PULL ---

    private fun openPullDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        if (workspaceRoot.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.pull_no_workspace), Toast.LENGTH_SHORT).show()
            return
        }
        val rootFile = File(workspaceRoot)
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val isRepo = GitManager.isGitInitialized(rootFile)
            val remotes = GitManager.listRemotes(rootFile)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (!isRepo) {
                    Toast.makeText(this@MainActivity, getString(R.string.pull_not_repo), Toast.LENGTH_SHORT).show()
                } else if (remotes.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No remotes configured. Configure a remote first.", Toast.LENGTH_LONG).show()
                } else {
                    showPullDialog(rootFile, remotes)
                }
            }
        }
    }

    private fun showPullDialog(rootFile: File, remotes: List<String>) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_pull, null)
        val etRemote = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etPullRemote)
        val etCred = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etPullCred)

        if (remotes.isNotEmpty()) {
            etRemote.setSimpleItems(remotes.toTypedArray())
            etRemote.setText(if ("origin" in remotes) "origin" else remotes.first(), false)
        }
        configureCredDropdown(etCred, defaultActive = true)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pull_title)
            .setView(dialogView)
            .setPositiveButton(R.string.pull_action) { _, _ ->
                val remote = etRemote.text?.toString()?.trim().orEmpty()
                if (remote.isEmpty()) {
                    Toast.makeText(this, "Select or type a remote.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val (username, token) = resolveCredProfile(etCred)
                runPull(rootFile, remote, username, token)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runPull(rootFile: File, remote: String, username: String?, token: String?) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.pullFromRemote(rootFile, remote, username, token)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.pull_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
                refreshLogsData()
                refreshWorkspaceStatus(rootFile.absolutePath)
            }
        }
    }

    // --- GIT BRANCH SWITCHER ---

    private fun showBranchDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val branches = GitManager.listBranches(rootFile)
            val current = GitManager.getWorkspaceStatus(rootFile).branch
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (branches.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No local branches found.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val dialogView = android.view.LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.dialog_git_branch, null)
                val container = dialogView.findViewById<LinearLayout>(R.id.llBranchList)
                lateinit var dialog: android.app.Dialog

                dialogView.findViewById<MaterialButton>(R.id.btnNewBranch).setOnClickListener {
                    dialog.dismiss()
                    showCreateBranchDialog(rootFile)
                }

                branches.forEach { branch ->
                    val row = android.view.LayoutInflater.from(this@MainActivity)
                        .inflate(R.layout.item_branch, container, false)
                    val rb = row.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rbBranchActive)
                    val tv = row.findViewById<TextView>(R.id.tvBranchName)
                    val btnDelete = row.findViewById<ImageButton>(R.id.btnBranchDelete)
                    val isCurrent = branch == current
                    tv.text = if (isCurrent) "$branch  (current)" else branch
                    rb.isChecked = isCurrent
                    rb.isEnabled = !isCurrent
                    if (isCurrent) {
                        btnDelete.visibility = View.GONE
                    } else {
                        btnDelete.setOnClickListener {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle(getString(R.string.branch_delete_title, branch))
                                .setMessage(R.string.branch_delete_msg)
                                .setPositiveButton(R.string.branch_delete_action) { _, _ ->
                                    runDeleteBranch(rootFile, branch)
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                    row.setOnClickListener {
                        if (isCurrent) return@setOnClickListener
                        dialog.dismiss()
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.checkout_title, branch))
                            .setMessage(R.string.checkout_msg)
                            .setPositiveButton(R.string.checkout_action) { _, _ ->
                                runCheckout(rootFile, branch)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    container.addView(row)
                }

                dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setView(dialogView)
                    .create()
                dialog.show()
            }
        }
    }

    private fun runCheckout(rootFile: File, branch: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.checkoutBranch(rootFile, branch)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.checkout_success, branch), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
                refreshLogsData()
                refreshWorkspaceStatus(rootFile.absolutePath)
            }
        }
    }

    // --- STASH ---

    private fun confirmStashSave() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.git_action_stash)
            .setMessage(R.string.stash_save_msg)
            .setPositiveButton(R.string.git_action_stash) { _, _ ->
                val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
                val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return@setPositiveButton
                val rootFile = File(workspaceRoot)
                val stamp = java.text.SimpleDateFormat("MMM dd hh:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date())
                runStashSave(rootFile, "WIP $stamp")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runStashSave(rootFile: File, message: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.stashChanges(rootFile, message)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, R.string.stash_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
                refreshLogsData()
                refreshWorkspaceStatus(rootFile.absolutePath)
            }
        }
    }

    private fun confirmStashPop() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.git_action_stash_pop)
            .setMessage(R.string.stash_pop_msg)
            .setPositiveButton(R.string.git_action_stash_pop) { _, _ ->
                val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
                val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return@setPositiveButton
                val rootFile = File(workspaceRoot)
                logsProgress.visibility = View.VISIBLE
                btnGitOptions.isEnabled = false
                lifecycleScope.launch(Dispatchers.IO) {
                    val error = GitManager.popStash(rootFile)
                    withContext(Dispatchers.Main) {
                        logsProgress.visibility = View.GONE
                        btnGitOptions.isEnabled = true
                        if (error == null) {
                            Toast.makeText(this@MainActivity, R.string.stash_restored, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                        }
                        refreshLogsData()
                        refreshWorkspaceStatus(rootFile.absolutePath)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- CHECKPOINTS ---

    private fun showCheckpointsDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val checkpoints = GitManager.listCheckpoints(rootFile)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                val dialogView = android.view.LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.dialog_git_checkpoints, null)
                val container = dialogView.findViewById<LinearLayout>(R.id.llCheckpoints)

                if (checkpoints.isEmpty()) {
                    val empty = TextView(this@MainActivity)
                    empty.text = getString(R.string.checkpoints_empty)
                    empty.setTextAppearance(this@MainActivity, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    val ta = theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    empty.setTextColor(ta.getColor(0, android.graphics.Color.GRAY))
                    ta.recycle()
                    empty.setPadding(0, 8, 0, 8)
                    container.addView(empty)
                }

                checkpoints.forEach { cp ->
                    val item = android.view.LayoutInflater.from(this@MainActivity)
                        .inflate(R.layout.item_checkpoint, container, false)
                    item.findViewById<TextView>(R.id.tvCheckpointLabel).text =
                        if (cp.round == 0) getString(R.string.checkpoint_session_start) else getString(R.string.checkpoint_round, cp.round)
                    item.findViewById<TextView>(R.id.tvCheckpointMessage).text = cp.message
                    item.findViewById<TextView>(R.id.tvCheckpointTime).text =
                        java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(cp.time))
                    item.findViewById<MaterialButton>(R.id.btnCheckpointReset).setOnClickListener {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.checkpoint_reset_title)
                            .setMessage(getString(R.string.checkpoint_reset_msg, cp.tag))
                            .setPositiveButton(R.string.checkpoint_reset_action) { _, _ ->
                                logsProgress.visibility = View.VISIBLE
                                btnGitOptions.isEnabled = false
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val ok = GitManager.resetHardToCommit(rootFile, cp.tag)
                                    withContext(Dispatchers.Main) {
                                        logsProgress.visibility = View.GONE
                                        btnGitOptions.isEnabled = true
                                        if (ok) {
                                            Toast.makeText(this@MainActivity, R.string.checkpoint_reset_done, Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, R.string.checkpoint_reset_failed, Toast.LENGTH_LONG).show()
                                        }
                                        refreshLogsData()
                                        refreshWorkspaceStatus(rootFile.absolutePath)
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    container.addView(item)
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setView(dialogView)
                    .show()
            }
        }
    }

    // --- GIT FETCH ---

    private fun openFetchDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        if (workspaceRoot.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.pull_no_workspace), Toast.LENGTH_SHORT).show()
            return
        }
        val rootFile = File(workspaceRoot)
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val remotes = GitManager.listRemotes(rootFile)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (remotes.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.no_remotes, Toast.LENGTH_LONG).show()
                } else {
                    showFetchDialog(rootFile, remotes)
                }
            }
        }
    }

    private fun showFetchDialog(rootFile: File, remotes: List<String>) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_fetch, null)
        val etRemote = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etFetchRemote)
        val etCred = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etFetchCred)

        etRemote.setSimpleItems(remotes.toTypedArray())
        etRemote.setText(if ("origin" in remotes) "origin" else remotes.first(), false)
        configureCredDropdown(etCred, defaultActive = true)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fetch_title)
            .setView(dialogView)
            .setPositiveButton(R.string.fetch_action) { _, _ ->
                val remote = etRemote.text?.toString()?.trim().orEmpty()
                if (remote.isEmpty()) {
                    Toast.makeText(this, "Select or type a remote.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val (username, token) = resolveCredProfile(etCred)
                runFetch(rootFile, remote, username, token)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runFetch(rootFile: File, remote: String, username: String?, token: String?) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.fetchRemote(rootFile, remote, username, token)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, R.string.fetch_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
                refreshLogsData()
            }
        }
    }

    // --- GIT MERGE ---

    private fun showMergeDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val branches = GitManager.listBranches(rootFile)
            val current = GitManager.getWorkspaceStatus(rootFile).branch
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                val candidates = branches.filter { it != current }
                if (candidates.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.merge_no_branches, Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val dialogView = android.view.LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.dialog_git_merge, null)
                val pick = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etMergeBranch)
                pick.setSimpleItems(candidates.toTypedArray())
                pick.setText(candidates.first(), false)

                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.git_action_merge)
                    .setView(dialogView)
                    .setPositiveButton(R.string.merge_action) { _, _ ->
                        val idx = candidates.indexOf(pick.text?.toString())
                        val branch = candidates.getOrElse(idx) { return@setPositiveButton }
                        runMerge(rootFile, branch)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun runMerge(rootFile: File, branch: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.mergeBranch(rootFile, branch)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.merge_success, branch), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
                refreshLogsData()
                refreshWorkspaceStatus(rootFile.absolutePath)
            }
        }
    }

    // --- DISCARD WORKING CHANGES ---

    private fun confirmDiscardChanges() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.git_action_discard)
            .setMessage(R.string.discard_msg)
            .setPositiveButton(R.string.discard_action) { _, _ ->
                val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
                val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return@setPositiveButton
                runDiscardChanges(File(workspaceRoot))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runDiscardChanges(rootFile: File) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.discardWorkingChanges(rootFile)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, R.string.discard_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
                refreshLogsData()
                refreshWorkspaceStatus(rootFile.absolutePath)
            }
        }
    }

    // --- GIT REMOTES ---

    private fun showRemotesDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val details = GitManager.listRemoteDetails(rootFile)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                val dialogView = android.view.LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.dialog_git_remotes, null)
                val container = dialogView.findViewById<LinearLayout>(R.id.llRemotes)
                lateinit var dialog: android.app.Dialog

                if (details.isEmpty()) {
                    val empty = TextView(this@MainActivity)
                    empty.text = getString(R.string.remotes_empty)
                    empty.setTextAppearance(this@MainActivity, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    val ta = theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    empty.setTextColor(ta.getColor(0, android.graphics.Color.GRAY))
                    ta.recycle()
                    empty.setPadding(0, 8, 0, 8)
                    container.addView(empty)
                }

                details.forEach { (name, url) ->
                    val item = android.view.LayoutInflater.from(this@MainActivity)
                        .inflate(R.layout.item_remote, container, false)
                    item.findViewById<TextView>(R.id.tvRemoteName).text = name
                    item.findViewById<TextView>(R.id.tvRemoteUrl).text = url.ifEmpty { getString(R.string.no_url) }
                    item.findViewById<ImageButton>(R.id.btnRemoteEdit).setOnClickListener {
                        dialog.dismiss()
                        showRemoteEditDialog(rootFile, name to url)
                    }
                    item.findViewById<ImageButton>(R.id.btnRemoteRemove).setOnClickListener {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.remove_remote_title)
                            .setMessage(getString(R.string.remove_remote_msg, name))
                            .setPositiveButton(R.string.remove_remote_action) { _, _ ->
                                runRemoveRemote(rootFile, name)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    container.addView(item)
                }

                dialogView.findViewById<MaterialButton>(R.id.btnAddRemote).setOnClickListener {
                    dialog.dismiss()
                    showRemoteEditDialog(rootFile, null)
                }

                dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setView(dialogView)
                    .create()
                dialog.show()
            }
        }
    }

    private fun showRemoteEditDialog(rootFile: File, existing: Pair<String, String>?) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_remote_edit, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etRemoteName)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etRemoteUrl)

        if (existing != null) {
            etName.setText(existing.first)
            etName.isEnabled = false
            etUrl.setText(existing.second)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_remote_title else R.string.edit_remote_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save_remote) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                val url = etUrl.text?.toString()?.trim().orEmpty()
                if (existing == null) {
                    runAddRemote(rootFile, name, url)
                } else {
                    runSetRemoteUrl(rootFile, name, url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runAddRemote(rootFile: File, name: String, url: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.addRemote(rootFile, name, url)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, R.string.remote_added, Toast.LENGTH_SHORT).show()
                    showRemotesDialog()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runSetRemoteUrl(rootFile: File, name: String, url: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.setRemoteUrl(rootFile, name, url)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, R.string.remote_updated, Toast.LENGTH_SHORT).show()
                    showRemotesDialog()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runRemoveRemote(rootFile: File, name: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.removeRemote(rootFile, name)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, R.string.remote_removed, Toast.LENGTH_SHORT).show()
                    showRemotesDialog()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- BRANCH CREATE / DELETE ---

    private fun showCreateBranchDialog(rootFile: File) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_create_branch, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etBranchName)
        val cbCheckout = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbBranchCheckout)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.branch_new)
            .setView(dialogView)
            .setPositiveButton(R.string.branch_new_action) { _, _ ->
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.branch_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runCreateBranch(rootFile, name, cbCheckout.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runCreateBranch(rootFile: File, name: String, switchTo: Boolean) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.createBranch(rootFile, name)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.branch_created, name), Toast.LENGTH_SHORT).show()
                    if (switchTo) {
                        runCheckout(rootFile, name)
                    } else {
                        showBranchDialog()
                    }
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runDeleteBranch(rootFile: File, branch: String) {
        logsProgress.visibility = View.VISIBLE
        btnGitOptions.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val error = GitManager.deleteBranch(rootFile, branch)
            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnGitOptions.isEnabled = true
                if (error == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.branch_deleted, branch), Toast.LENGTH_SHORT).show()
                    showBranchDialog()
                } else {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- GIT CREDENTIAL PROFILES ---

    private fun refreshGitCredStatus() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val active = GitCredentialProfiles.activeProfile(sharedPref)
        tvGitCredStatus.text = if (active == null) {
            getString(R.string.no_credentials_status)
        } else {
            "${active.name} (${active.username.ifEmpty { "no username" }})"
        }
    }

    private fun configureCredDropdown(et: MaterialAutoCompleteTextView, defaultActive: Boolean) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val anonymous = getString(R.string.no_credentials)
        val labels = GitCredentialProfiles.labels(sharedPref)
        et.setSimpleItems(arrayOf(anonymous) + labels)
        val active = GitCredentialProfiles.activeName(sharedPref)
        et.setText(if (defaultActive) active?.takeIf { it in labels } ?: anonymous else anonymous, false)
    }

    private fun resolveCredProfile(et: MaterialAutoCompleteTextView): Pair<String?, String?> {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val selected = et.text?.toString()?.trim().orEmpty()
        if (selected.isEmpty() || selected == getString(R.string.no_credentials)) return null to null
        val profile = GitCredentialProfiles.profiles(sharedPref).firstOrNull { it.name == selected } ?: return null to null
        return profile.username.takeIf { it.isNotEmpty() } to profile.token.takeIf { it.isNotEmpty() }
    }

    private fun showCredentialProfilesDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_credentials, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.llCredProfiles)

        fun render() {
            container.removeAllViews()
            val activeName = GitCredentialProfiles.activeName(sharedPref)
            GitCredentialProfiles.profiles(sharedPref).forEach { profile ->
                val item = android.view.LayoutInflater.from(this).inflate(R.layout.item_cred_profile, container, false)
                item.findViewById<TextView>(R.id.tvCredName).text = profile.name
                item.findViewById<TextView>(R.id.tvCredUsername).text = profile.username.ifEmpty { getString(R.string.no_credentials_status) }
                item.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchCredActive).isChecked = profile.name == activeName
                item.setOnClickListener {
                    GitCredentialProfiles.setActive(sharedPref, profile.name)
                    render()
                    refreshGitCredStatus()
                }
                item.findViewById<MaterialButton>(R.id.btnCredEdit).setOnClickListener {
                    showCredentialEditor(profile) { render(); refreshGitCredStatus() }
                }
                item.findViewById<MaterialButton>(R.id.btnCredDelete).setOnClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.delete_profile_title)
                        .setMessage(R.string.delete_profile_msg)
                        .setPositiveButton(R.string.delete_button) { _, _ ->
                            GitCredentialProfiles.remove(sharedPref, profile.name)
                            render()
                            refreshGitCredStatus()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                container.addView(item)
            }
            if (GitCredentialProfiles.profiles(sharedPref).isEmpty()) {
                val empty = TextView(this)
                empty.text = getString(R.string.no_credentials_status)
                empty.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                val ta = theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                empty.setTextColor(ta.getColor(0, android.graphics.Color.GRAY))
                ta.recycle()
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 16, 0, 16)
                empty.layoutParams = lp
                container.addView(empty)
            }
        }

        render()

        dialogView.findViewById<MaterialButton>(R.id.btnAddCredProfile).setOnClickListener {
            showCredentialEditor(null) { render(); refreshGitCredStatus() }
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .show()
    }

    private fun showCredentialEditor(existing: GitCredentialProfile?, onSaved: () -> Unit) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_credential, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etCredName)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.etCredUsername)
        val etToken = dialogView.findViewById<TextInputEditText>(R.id.etCredToken)

        etName.setText(existing?.name)
        etUsername.setText(existing?.username)
        etToken.setText(existing?.token)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_profile else R.string.edit_profile)
            .setView(dialogView)
            .setPositiveButton(R.string.save_profile) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.profile_name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val username = etUsername.text?.toString()?.trim().orEmpty()
                val token = etToken.text?.toString()?.trim().orEmpty()
                GitCredentialProfiles.upsert(sharedPref, GitCredentialProfile(name, username, token))
                onSaved()
                refreshGitCredStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- NATIVE FILE PICKER LOGIC ---

    private fun saveWorkspace(uri: Uri, path: String) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("WORKSPACE_ROOT", path)
            putString("WORKSPACE_SAF_URI", uri.toString())
            apply()
        }
        tvWorkspacePath.text = File(path).name
        refreshWorkspaceStatus(path)
    }

    private fun loadCurrentWorkspace() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val path = sharedPref.getString("WORKSPACE_ROOT", null)
        tvWorkspacePath.text = if (path != null) File(path).name else getString(R.string.workspace_not_set)
        refreshWorkspaceStatus(path)
    }

    private fun refreshWorkspaceStatus(workspaceRoot: String?) {
        if (workspaceRoot.isNullOrEmpty()) {
            tvWorkspaceStatus.text = ""
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val status = GitManager.getWorkspaceStatus(File(workspaceRoot))
            withContext(Dispatchers.Main) {
                tvWorkspaceStatus.text = when {
                    !status.isRepo -> "Not a Git repository"
                    status.branch.isNullOrBlank() ->
                        if (status.isClean) "Git repository · Clean (detached)"
                        else "Git repository · ${status.changeCount} change(s) (detached)"
                    status.isClean -> "${status.branch} · Clean"
                    else -> "${status.branch} · ${status.changeCount} change(s)"
                }
            }
        }
    }

    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            checkAndRequestNotificationPermission()
            return
        }
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        if (!sharedPref.getBoolean("STORAGE_RATIONALE_SHOWN", false)) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Storage Access Required")
                .setMessage("CodeAssist applies file mutations directly inside your workspace. To read and write files in any folder you choose, Android requires the \"All files access\" permission. Grant it from the next screen.")
                .setPositiveButton("Continue") { _, _ ->
                    sharedPref.edit().putBoolean("STORAGE_RATIONALE_SHOWN", true).apply()
                    launchStorageSettingsIntent()
                }
                .setNegativeButton("Not Now", null)
                .show()
        } else {
            launchStorageSettingsIntent()
        }
    }

    private fun launchStorageSettingsIntent() {
        val packageIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        val intent = if (packageIntent.resolveActivity(packageManager) != null) {
            packageIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        try {
            storagePermissionLauncher.launch(intent)
        } catch (_: Exception) {
            checkAndRequestNotificationPermission()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun refreshPermissionStatus() {
        val colorGranted = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0)
        val colorOff = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

        val storageEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        permStatusAllFiles.text = getString(if (storageEnabled) R.string.perm_granted else R.string.perm_off)
        permStatusAllFiles.setTextColor(if (storageEnabled) colorGranted else colorOff)

        val a11yEnabled = AgentAccessibilityService.instance != null
        permStatusAccessibility.text = getString(if (a11yEnabled) R.string.perm_granted else R.string.perm_off)
        permStatusAccessibility.setTextColor(if (a11yEnabled) colorGranted else colorOff)

        val notifEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        permStatusNotifications.text = getString(if (notifEnabled) R.string.perm_granted else R.string.perm_off)
        permStatusNotifications.setTextColor(if (notifEnabled) colorGranted else colorOff)

        val overlayEnabled = Settings.canDrawOverlays(this)
        permStatusOverlay.text = getString(if (overlayEnabled) R.string.perm_granted else R.string.perm_off)
        permStatusOverlay.setTextColor(if (overlayEnabled) colorGranted else colorOff)
    }

    private fun requestAddQsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(android.app.StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                android.content.ComponentName(this, CodeAssistTileService::class.java),
                getString(R.string.app_name),
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile),
                java.util.concurrent.Executors.newSingleThreadExecutor()
            ) { /* resultCode: 0 = not added, 1 = already added, 2 = added */ }
        } else {
            Toast.makeText(this, "Open the Quick Settings tile editor and search for the CodeAssist tile.", Toast.LENGTH_LONG).show()
        }
    }
}
