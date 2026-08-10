package org.ravi.codeassist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.ravi.codeassist.utils.InstalledApp

class CreateProfileDialog : DialogFragment() {

    private lateinit var viewModel: AgenticViewModel
    private lateinit var appsAdapter: InstalledAppsAdapter
    private var selectedApp: InstalledApp? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return inflater.inflate(R.layout.dialog_create_profile, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[AgenticViewModel::class.java]

        val etProfileName = view.findViewById<TextInputEditText>(R.id.etProfileName)
        val searchApps = view.findViewById<SearchView>(R.id.searchApps)
        val rvApps = view.findViewById<RecyclerView>(R.id.rvApps)
        val btnSaveProfile = view.findViewById<MaterialButton>(R.id.btnSaveProfile)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val progressApps = view.findViewById<View>(R.id.progressApps)

        rvApps.layoutManager = LinearLayoutManager(requireContext())
        appsAdapter = InstalledAppsAdapter(emptyList()) { app ->
            selectedApp = app
            btnSaveProfile.isEnabled = etProfileName.text?.isNotBlank() == true
            if (etProfileName.text.isNullOrBlank()) {
                etProfileName.setText("${app.appName} Agent")
                btnSaveProfile.isEnabled = true
            }
        }
        rvApps.adapter = appsAdapter

        lifecycleScope.launch {
            viewModel.installedApps.collectLatest { apps ->
                if (apps.isNotEmpty()) {
                    progressApps.visibility = View.GONE
                    appsAdapter.updateData(apps)
                } else {
                    progressApps.visibility = View.VISIBLE
                }
            }
        }

        viewModel.loadInstalledApps()

        searchApps.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                appsAdapter.filter(newText ?: "")
                return true
            }
        })

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnSaveProfile.setOnClickListener {
            val profileName = etProfileName.text?.toString()?.trim()
            val appToTarget = selectedApp
            
            if (!profileName.isNullOrEmpty() && appToTarget != null) {
                viewModel.createProfile(profileName, appToTarget.packageName)
                Toast.makeText(requireContext(), "Profile created!", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }
}