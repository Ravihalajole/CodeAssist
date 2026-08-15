package org.ravi.codeassist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
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
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var btnGitOptions: MaterialButton
    private lateinit var btnExecutionHistory: MaterialButton
    private lateinit var tvGitIdentityStatus: TextView
    private lateinit var btnEditGitConfig: MaterialButton
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
        bottomNavigation = findViewById(R.id.bottomNavigation)
        btnGitOptions = findViewById(R.id.btnGitOptions)
        btnExecutionHistory = findViewById(R.id.btnExecutionHistory)
        rvLogs = findViewById(R.id.rvLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)
        logsProgress = findViewById(R.id.logsProgress)
        tvGitIdentityStatus = findViewById(R.id.tvGitIdentityStatus)
        btnEditGitConfig = findViewById(R.id.btnEditGitConfig)
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

    private fun showGitActionsDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_git_actions, null)

        val wsLabel = dialogView.findViewById<TextView>(R.id.tvGitDialogWorkspace)
        wsLabel.text = workspaceRoot?.let { getString(R.string.git_actions_workspace_desc, it) }
            ?: getString(R.string.workspace_not_set)

        lateinit var dialog: android.app.Dialog
        dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.rowGitCommit).setOnClickListener {
            dialog.dismiss()
            showManualCommitDialog()
        }
        dialogView.findViewById<View>(R.id.rowGitRevert).setOnClickListener {
            dialog.dismiss()
            handleGitUndoAction()
        }
        dialogView.findViewById<View>(R.id.rowGitReset).setOnClickListener {
            dialog.dismiss()
            showResetDialog()
        }
        dialogView.findViewById<MaterialButton>(R.id.btnGitDialogClose).setOnClickListener { dialog.dismiss() }

        dialog.show()
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

        dialog.show()
    }

    private fun handleGitUndoAction() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.hint = "Leave blank for latest (HEAD)"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val dp48 = (resources.displayMetrics.density * 48).toInt()
        params.setMargins(dp48, 0, dp48, 0)
        input.layoutParams = params
        container.addView(input)

        val explicitContext = "This will append a safe corrective commit that explicitly neutralizes and flips the modifications introduced by the target commit, while fully preserving history."

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Revert Commit")
            .setMessage(explicitContext)
            .setView(container)
            .setPositiveButton("Revert") { _, _ ->
                val hash = input.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() } ?: "HEAD"
                executeGitUndoAction(rootFile, hash)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.hint = "Leave blank for HEAD~1"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val dp48 = (resources.displayMetrics.density * 48).toInt()
        params.setMargins(dp48, 0, dp48, 0)
        input.layoutParams = params
        container.addView(input)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Hard Reset")
            .setMessage("Enter the exact commit hash to reset to. If left blank, it defaults to HEAD~1 (which will discard the latest commit and all uncommitted changes).")
            .setView(container)
            .setPositiveButton("Reset") { _, _ ->
                val hash = input.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() } ?: "HEAD~1"
                executeGitResetAction(rootFile, hash)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
            
    private fun showManualCommitDialog() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.hint = "Commit message"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.minLines = 3

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val dp48 = (resources.displayMetrics.density * 48).toInt()
        params.setMargins(dp48, 0, dp48, 0)
        input.layoutParams = params
        container.addView(input)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Manual Commit")
            .setMessage("Stage and commit all tracked/untracked file changes in the workspace.")
            .setView(container)
            .setPositiveButton("Commit") { _, _ ->
                val message = input.text?.toString()?.trim()
                if (!message.isNullOrEmpty()) {
                    executeManualCommit(rootFile, message)
                } else {
                    Toast.makeText(this, "Commit message cannot be empty.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
