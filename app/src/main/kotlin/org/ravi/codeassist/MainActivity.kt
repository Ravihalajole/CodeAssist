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
    private lateinit var viewSourceControl: View
    private lateinit var viewSettings: View
    
    // UI Elements
    private lateinit var tvWorkspacePath: TextView
    private lateinit var tvGitStatus: TextView
    private lateinit var switchBubble: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var switchAutoRead: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var btnSelectWorkspace: MaterialButton
    private lateinit var btnCopyPrompt: MaterialButton
    private lateinit var btnCopySkeleton: MaterialButton
    private lateinit var btnRunManual: MaterialButton
    private lateinit var btnInitGit: MaterialButton
    private lateinit var btnStash: MaterialButton
    private lateinit var btnPopStash: MaterialButton
    private lateinit var btnRefreshStatus: MaterialButton
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var btnRevert: MaterialButton
    private lateinit var btnReset: MaterialButton
    
    // RecyclerView Components
    private lateinit var rvLogs: RecyclerView
    private lateinit var tvEmptyLogs: TextView
    private lateinit var logsAdapter: LogsAdapter

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
        // Auto-refresh active tabs when returning to the app from ClipboardActivity
        if (this::viewLogs.isInitialized && viewLogs.visibility == View.VISIBLE) {
            showLogsScreen()
        } else if (this::viewSourceControl.isInitialized && viewSourceControl.visibility == View.VISIBLE) {
            refreshGitStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        viewWorkspace = findViewById(R.id.viewWorkspace)
        viewPromptVault = findViewById(R.id.viewPromptVault)
        viewLogs = findViewById(R.id.viewLogs)
        viewSourceControl = findViewById(R.id.viewSourceControl)
        viewSettings = findViewById(R.id.viewSettings)
        
        tvWorkspacePath = findViewById(R.id.tvWorkspacePath)
        tvGitStatus = findViewById(R.id.tvGitStatus)
        switchBubble = findViewById(R.id.switchBubble)
        switchAutoRead = findViewById(R.id.switchAutoRead)
        btnSelectWorkspace = findViewById(R.id.btnSelectWorkspace)
        btnCopyPrompt = findViewById(R.id.btnCopyPrompt)
        btnCopySkeleton = findViewById(R.id.btnCopySkeleton)
        btnRunManual = findViewById(R.id.btnRunManual)
        btnInitGit = findViewById(R.id.btnInitGit)
        btnStash = findViewById(R.id.btnStash)
        btnPopStash = findViewById(R.id.btnPopStash)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        btnRevert = findViewById(R.id.btnRevert)
        btnReset = findViewById(R.id.btnReset)
        rvLogs = findViewById(R.id.rvLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)

        // Setup RecyclerView
        rvLogs.layoutManager = LinearLayoutManager(this)
        logsAdapter = LogsAdapter(emptyList()) { commit ->
            showCommitDetailsDialog(commit)
        }
        rvLogs.adapter = logsAdapter

        checkAndRequestStoragePermission()
        loadCurrentWorkspace()

        // Setup Bubble Settings
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        switchBubble.isChecked = sharedPref.getBoolean("BUBBLE_ENABLED", false)
        switchAutoRead.isChecked = sharedPref.getBoolean("AUTO_READ_ENABLED", false)

        switchAutoRead.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putBoolean("AUTO_READ_ENABLED", isChecked).apply()
        }

        val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                switchBubble.isChecked = true
                sharedPref.edit().putBoolean("BUBBLE_ENABLED", true).apply()
                startService(Intent(this, FloatingBubbleService::class.java))
            } else {
                switchBubble.isChecked = false
                sharedPref.edit().putBoolean("BUBBLE_ENABLED", false).apply()
                Toast.makeText(this, "Permission denied for Floating Bubble.", Toast.LENGTH_SHORT).show()
            }
        }

        switchBubble.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    switchBubble.isChecked = false
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlayPermissionLauncher.launch(intent)
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

        btnInitGit.setOnClickListener {
            val path = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE).getString("WORKSPACE_ROOT", null)
            if (path != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    GitManager.initGit(File(path))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Git Initialized.", Toast.LENGTH_SHORT).show()
                        refreshGitStatus()
                    }
                }
            }
        }

        btnStash.setOnClickListener {
            val path = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE).getString("WORKSPACE_ROOT", null)
            if (path != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = GitManager.stashChanges(File(path))
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(this@MainActivity, "Changes stashed.", Toast.LENGTH_SHORT).show()
                            refreshGitStatus()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to stash.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        btnPopStash.setOnClickListener {
            val path = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE).getString("WORKSPACE_ROOT", null)
            if (path != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = GitManager.popStash(File(path))
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(this@MainActivity, "Stash popped.", Toast.LENGTH_SHORT).show()
                            refreshGitStatus()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to pop stash.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        btnRefreshStatus.setOnClickListener {
            refreshGitStatus()
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
                R.id.nav_source_control -> {
                    showSourceControlScreen()
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
        viewWorkspace.visibility = View.VISIBLE
        viewPromptVault.visibility = View.GONE
        viewLogs.visibility = View.GONE
        viewSourceControl.visibility = View.GONE
        viewSettings.visibility = View.GONE
    }

    private fun showPromptVaultScreen() {
        viewPromptVault.visibility = View.VISIBLE
        viewWorkspace.visibility = View.GONE
        viewLogs.visibility = View.GONE
        viewSourceControl.visibility = View.GONE
        viewSettings.visibility = View.GONE
    }

    private fun showLogsScreen() {
        viewLogs.visibility = View.VISIBLE
        viewWorkspace.visibility = View.GONE
        viewPromptVault.visibility = View.GONE
        viewSourceControl.visibility = View.GONE
        viewSettings.visibility = View.GONE
        
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        if (!workspaceRoot.isNullOrEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val commits = GitManager.getCommitHistory(File(workspaceRoot))
                withContext(Dispatchers.Main) {
                    logsAdapter.updateData(commits)
                    tvEmptyLogs.visibility = if (commits.isEmpty()) View.VISIBLE else View.GONE
                    if (commits.isNotEmpty()) {
                        rvLogs.scrollToPosition(0)
                    }
                }
            }
        } else {
            logsAdapter.updateData(emptyList())
            tvEmptyLogs.visibility = View.VISIBLE
            tvEmptyLogs.text = "Workspace not set."
        }
    }

    private fun showSourceControlScreen() {
        viewSourceControl.visibility = View.VISIBLE
        viewWorkspace.visibility = View.GONE
        viewPromptVault.visibility = View.GONE
        viewLogs.visibility = View.GONE
        viewSettings.visibility = View.GONE
        
        refreshGitStatus()
    }

    private fun showSettingsScreen() {
        viewSettings.visibility = View.VISIBLE
        viewWorkspace.visibility = View.GONE
        viewPromptVault.visibility = View.GONE
        viewLogs.visibility = View.GONE
        viewSourceControl.visibility = View.GONE
    }

    private fun refreshGitStatus() {
        val path = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE).getString("WORKSPACE_ROOT", null)
        if (path.isNullOrEmpty()) {
            tvGitStatus.text = "Workspace not set."
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val statusStr = GitManager.getStatusString(File(path))
            withContext(Dispatchers.Main) {
                tvGitStatus.text = statusStr
            }
        }
    }

    // --- ACTIONS ---

    private fun showCommitDetailsDialog(commit: GitManager.CommitInfo) {
        val df = java.text.SimpleDateFormat("MMM dd, yyyy  hh:mm a", java.util.Locale.getDefault())
        val messageBody = """
            Commit: ${commit.hash}
            Author: ${commit.author}
            Date: ${df.format(java.util.Date(commit.time))}
            
            Message:
            ${commit.message}
        """.trimIndent()

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
                if (ignoreList.contains(file.name)) continue
                
                val indent = "  ".repeat(depth)
                val prefix = if (file.isDirectory) "📁 " else "📄 "
                sb.append("$indent$prefix${file.name}\n")
                
                if (file.isDirectory) {
                    walkDir(file, depth + 1)
                }
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

        lifecycleScope.launch(Dispatchers.IO) {
            if (!GitManager.isGitInitialized(rootFile)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Git not initialized.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val commits = GitManager.getCommitHistory(rootFile)
            if (commits.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No reversible actions found.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val commitHash = commits.first().hash
            val actionSuccess = if (isReset) {
                GitManager.resetHardToPrevious(rootFile)
            } else {
                GitManager.revertCommit(rootFile, commitHash)
            }
            
            val actionName = if (isReset) "Git Reset" else "Git Revert"

            withContext(Dispatchers.Main) {
                if (actionSuccess) {
                    Toast.makeText(this@MainActivity, "Undo Complete: $actionName successful.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Undo Failed: Could not complete $actionName.", Toast.LENGTH_SHORT).show()
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val commits = GitManager.getCommitHistory(rootFile)
                    withContext(Dispatchers.Main) {
                        logsAdapter.updateData(commits)
                        tvEmptyLogs.visibility = if (commits.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
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
            } else {
                // Fallback for secondary storage volumes (SD cards, USB drives)
                val storageManager = getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
                val storageVolumes = storageManager.storageVolumes
                for (volume in storageVolumes) {
                    val volumeUuid = volume.uuid
                    if (volumeUuid != null && volumeUuid.equals(type, ignoreCase = true)) {
                        val volumeDirectory = volume.directory
                        if (volumeDirectory != null) {
                            return volumeDirectory.absolutePath + "/" + path
                        }
                    }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }
}
