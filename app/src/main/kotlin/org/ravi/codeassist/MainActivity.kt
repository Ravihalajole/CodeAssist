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
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var viewWorkspace: View
    private lateinit var viewPromptVault: View
    private lateinit var viewLogs: View
    private lateinit var viewSettings: View
    
    // UI Elements
    private lateinit var tvWorkspacePath: TextView
    private lateinit var switchBubble: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var tvBubbleIconStatus: TextView
    private lateinit var btnEditBubbleIcon: MaterialButton
    private lateinit var switchAutoRead: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var switchQuickActions: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var switchForgivingWhitespace: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var btnSelectWorkspace: MaterialButton
    private lateinit var btnCopyPrompt: MaterialButton
    private lateinit var btnCopySkeleton: MaterialButton
    private lateinit var btnRunManual: MaterialButton
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var btnRevert: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var tvGitIdentityStatus: TextView
    private lateinit var btnEditGitConfig: MaterialButton
    
    // RecyclerView Components
    private lateinit var rvLogs: RecyclerView
    private lateinit var tvEmptyLogs: TextView
    private lateinit var logsAdapter: LogsAdapter
    private lateinit var logsProgress: com.google.android.material.progressindicator.LinearProgressIndicator

    private val requestNotificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for Quick-Action heads-up alerts.", Toast.LENGTH_LONG).show()
        }
    }

    private val directoryPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val absolutePath = getAbsolutePathFromSafUri(uri)
            if (absolutePath != null) {
                saveWorkspacePath(absolutePath)
                Toast.makeText(this, "Workspace locked!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not resolve physical path. Please select a folder on internal storage.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::viewLogs.isInitialized && viewLogs.visibility == View.VISIBLE) {
            showLogsScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        GitManager.registerContext(this)

        // Bind Views
        viewWorkspace = findViewById(R.id.viewWorkspace)
        viewPromptVault = findViewById(R.id.viewPromptVault)
        viewLogs = findViewById(R.id.viewLogs)
        viewSettings = findViewById(R.id.viewSettings)
        
        tvWorkspacePath = findViewById(R.id.tvWorkspacePath)
        switchBubble = findViewById(R.id.switchBubble)
        tvBubbleIconStatus = findViewById(R.id.tvBubbleIconStatus)
        btnEditBubbleIcon = findViewById(R.id.btnEditBubbleIcon)
        switchAutoRead = findViewById(R.id.switchAutoRead)
        switchQuickActions = findViewById(R.id.switchQuickActions)
        switchForgivingWhitespace = findViewById(R.id.switchForgivingWhitespace)
        btnSelectWorkspace = findViewById(R.id.btnSelectWorkspace)
        btnCopyPrompt = findViewById(R.id.btnCopyPrompt)
        btnCopySkeleton = findViewById(R.id.btnCopySkeleton)
        btnRunManual = findViewById(R.id.btnRunManual)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        btnRevert = findViewById(R.id.btnRevert)
        btnReset = findViewById(R.id.btnReset)
        rvLogs = findViewById(R.id.rvLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)
        logsProgress = findViewById(R.id.logsProgress)
        tvGitIdentityStatus = findViewById(R.id.tvGitIdentityStatus)
        btnEditGitConfig = findViewById(R.id.btnEditGitConfig)

        // Setup RecyclerView
        rvLogs.layoutManager = LinearLayoutManager(this)
        logsAdapter = LogsAdapter(emptyList()) { commit ->
            showCommitDetailsDialog(commit)
        }
        rvLogs.adapter = logsAdapter

        checkAndRequestStoragePermission()
        checkAndRequestNotificationPermission()
        loadCurrentWorkspace()

        // Setup Bubble Settings
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        switchBubble.isChecked = sharedPref.getBoolean("BUBBLE_ENABLED", false)
        switchAutoRead.isChecked = sharedPref.getBoolean("AUTO_READ_ENABLED", false)
        switchQuickActions.isChecked = sharedPref.getBoolean("QUICK_ACTIONS_ENABLED", false)
        switchForgivingWhitespace.isChecked = sharedPref.getBoolean("FORGIVING_WHITESPACE_ENABLED", true)

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

        switchAutoRead.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putBoolean("AUTO_READ_ENABLED", isChecked).apply()
        }

        switchQuickActions.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putBoolean("QUICK_ACTIONS_ENABLED", isChecked).apply()
        }

        switchForgivingWhitespace.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putBoolean("FORGIVING_WHITESPACE_ENABLED", isChecked).apply()
        }

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

        val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val enabled = Settings.canDrawOverlays(this)
            switchBubble.isChecked = enabled
            sharedPref.edit().putBoolean("BUBBLE_ENABLED", enabled).apply()
            if (enabled) {
                startService(Intent(this, FloatingBubbleService::class.java))
            } else {
                Toast.makeText(this, "Permission denied for Floating Bubble.", Toast.LENGTH_SHORT).show()
            }
        }

        switchBubble.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    switchBubble.isChecked = false
                    overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    sharedPref.edit().putBoolean("BUBBLE_ENABLED", true).apply()
                    startService(Intent(this, FloatingBubbleService::class.java))
                }
            } else {
                sharedPref.edit().putBoolean("BUBBLE_ENABLED", false).apply()
                stopService(Intent(this, FloatingBubbleService::class.java))
            }
        }

        // Start bubble on launch if enabled
        if (switchBubble.isChecked && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingBubbleService::class.java))
        }

        // Setup Button Listeners
        btnSelectWorkspace.setOnClickListener {
            directoryPickerLauncher.launch(null)
        }

        btnCopyPrompt.setOnClickListener {
            copyProtocolToClipboard()
        }
        
        btnCopySkeleton.setOnClickListener {
            exportProjectSkeleton()
        }
        
        btnRunManual.setOnClickListener {
            val intent = Intent(this, ClipboardActivity::class.java)
            startActivity(intent)
        }
        
        btnRevert.setOnClickListener {
            handleGitUndoAction(isReset = false)
        }

        btnReset.setOnClickListener {
            handleGitUndoAction(isReset = true)
        }

        // Setup Bottom Navigation Logic
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_workspace -> {
                    showWorkspaceScreen()
                    true
                }
                R.id.nav_prompt_vault -> {
                    showPromptVaultScreen()
                    true
                }
                R.id.nav_logs -> {
                    showLogsScreen()
                    true
                }
                R.id.nav_settings -> {
                    showSettingsScreen()
                    true
                }
                else -> false
            }
        }
    }

    // --- SCREEN NAVIGATION ---

    private fun showWorkspaceScreen() {
        updateScreenVisibilities(workspace = View.VISIBLE)
    }

    private fun showPromptVaultScreen() {
        updateScreenVisibilities(promptVault = View.VISIBLE)
    }

    private fun showLogsScreen() {
        updateScreenVisibilities(logs = View.VISIBLE)
        
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

    private fun showSettingsScreen() {
        updateScreenVisibilities(settings = View.VISIBLE)
    }

    private fun updateScreenVisibilities(
        workspace: Int = View.GONE,
        promptVault: Int = View.GONE,
        logs: Int = View.GONE,
        settings: Int = View.GONE
    ) {
        viewWorkspace.visibility = workspace
        viewPromptVault.visibility = promptVault
        viewLogs.visibility = logs
        viewSettings.visibility = settings
    }

    // --- ACTIONS ---

    private fun showCommitDetailsDialog(commit: GitManager.CommitInfo) {
        val df = java.text.SimpleDateFormat("MMM dd, yyyy  hh:mm a", java.util.Locale.getDefault())
        val messageBody = "Commit: ${commit.hash}\nAuthor: ${commit.author}\nDate: ${df.format(java.util.Date(commit.time))}\n\nMessage:\n${commit.message}"

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Commit Details")
            .setMessage(messageBody)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun generateProjectSkeletonString(rootDir: File): String {
        val ignoreList = listOf("build", ".gradle", ".git", ".idea", ".codeassist", "outputs", "tmp")
        val sb = java.lang.StringBuilder("Project Skeleton:\n")

        fun walkDir(currentDir: File, depth: Int) {
            val files = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (file in files) {
                if (file.name in ignoreList) continue
                val indent = "  ".repeat(depth)
                val prefix = if (file.isDirectory) "📁 " else "📄 "
                sb.append("$indent$prefix${file.name}\n")
                if (file.isDirectory) walkDir(file, depth + 1)
            }
        }
        walkDir(rootDir, 0)
        return sb.toString()
    }

    private fun exportProjectSkeleton() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)

        if (workspaceRoot.isNullOrEmpty()) {
            Toast.makeText(this, "Select a workspace first", Toast.LENGTH_SHORT).show()
            return
        }

        val rootDir = File(workspaceRoot)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            Toast.makeText(this, "Workspace not found!", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val skeleton = generateProjectSkeletonString(rootDir)
            withContext(Dispatchers.Main) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Project Skeleton", skeleton)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "Skeleton copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyProtocolToClipboard() {
        val protocolText = getString(R.string.ai_protocol_text)
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)

        lifecycleScope.launch(Dispatchers.IO) {
            var finalText = protocolText
            
            if (!workspaceRoot.isNullOrEmpty()) {
                val rootDir = File(workspaceRoot)
                if (rootDir.exists() && rootDir.isDirectory) {
                    val skeleton = generateProjectSkeletonString(rootDir)
                    finalText = "$protocolText\n\n$skeleton"
                }
            }

            withContext(Dispatchers.Main) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CodeAssist Protocol", finalText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "Protocol + Skeleton copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleGitUndoAction(isReset: Boolean) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null) ?: return
        val rootFile = File(workspaceRoot)

        val title = if (isReset) "Confirm Hard Reset" else "Confirm Commit Revert"
        val explicitContext = if (isReset) {
            "This will execute a hard rollback to the preceding clean snapshot (HEAD~1). Your latest local tracking commit will be deleted and any uncommitted modifications will be discarded."
        } else {
            "This will append a safe corrective commit (Revert HEAD) that explicitly neutralizes and flips all file modifications introduced during your latest execution cycle while fully preserving history."
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(explicitContext)
            .setPositiveButton(if (isReset) "Reset to HEAD~1" else "Revert Latest") { _, _ ->
                executeGitUndoAction(rootFile, isReset)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeGitUndoAction(rootFile: File, isReset: Boolean) {
        logsProgress.visibility = View.VISIBLE
        btnRevert.isEnabled = false
        btnReset.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            if (!GitManager.isGitInitialized(rootFile)) {
                withContext(Dispatchers.Main) {
                    logsProgress.visibility = View.GONE
                    btnRevert.isEnabled = true
                    btnReset.isEnabled = true
                    Toast.makeText(this@MainActivity, "Git repository not initialized.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // O(1) Optimization: Instead of performing a heavy full repository tree history pull up front,
            // check if there is an active commit stack currently available on screen to target instantly.
            val commits = GitManager.getCommitHistory(rootFile)
            if (commits.isEmpty()) {
                withContext(Dispatchers.Main) {
                    logsProgress.visibility = View.GONE
                    btnRevert.isEnabled = true
                    btnReset.isEnabled = true
                    Toast.makeText(this@MainActivity, "No transactional history found to undo.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val targetHash = commits.first().hash
            val actionSuccess = if (isReset) {
                GitManager.resetHardToPrevious(rootFile)
            } else {
                GitManager.revertCommit(rootFile, targetHash)
            }
            
            val actionName = if (isReset) "Hard Reset" else "Commit Revert"
            val postActionCommits = GitManager.getCommitHistory(rootFile)

            withContext(Dispatchers.Main) {
                logsProgress.visibility = View.GONE
                btnRevert.isEnabled = true
                btnReset.isEnabled = true
                
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

    // --- SAF AND STORAGE LOGIC ---

    private fun getAbsolutePathFromSafUri(uri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val path = if (split.size > 1) split[1] else ""

            if ("primary".equals(type, ignoreCase = true)) {
                return Environment.getExternalStorageDirectory().toString() + "/" + path
            }
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            for (volume in storageManager.storageVolumes) {
                if (volume.uuid != null && volume.uuid.equals(type, ignoreCase = true)) {
                    volume.directory?.let { return it.absolutePath + "/" + path }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun saveWorkspacePath(path: String) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("WORKSPACE_ROOT", path)
            apply()
        }
        tvWorkspacePath.text = path
    }

    private fun loadCurrentWorkspace() {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val path = sharedPref.getString("WORKSPACE_ROOT", "Not Set")
        tvWorkspacePath.text = path
    }

    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
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
}
