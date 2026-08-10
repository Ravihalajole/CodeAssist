package org.ravi.codeassist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import org.ravi.codeassist.database.AgentProfile

class AgentProfilesAdapter(
    private var profiles: List<AgentProfile>,
    private val onCalibrate: (AgentProfile) -> Unit,
    private val onRun: (AgentProfile) -> Unit,
    private val onDelete: (AgentProfile) -> Unit,
    private val onExport: (AgentProfile) -> Unit
) : RecyclerView.Adapter<AgentProfilesAdapter.ProfileViewHolder>() {

    class ProfileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAppIcon: android.widget.ImageView = view.findViewById(R.id.ivAppIcon)
        val tvProfileName: TextView = view.findViewById(R.id.tvProfileName)
        val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
        val vStatusDot: View = view.findViewById(R.id.vStatusDot)
        val tvStatusText: TextView = view.findViewById(R.id.tvStatusText)
        val btnAction: MaterialButton = view.findViewById(R.id.btnAction)
        val btnDeleteProfile: MaterialButton = view.findViewById(R.id.btnDeleteProfile)
        val btnExportProfile: MaterialButton = view.findViewById(R.id.btnExportProfile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_agent_profile, parent, false)
        return ProfileViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = profiles[position]
        holder.tvProfileName.text = profile.profileName
        holder.tvPackageName.text = profile.packageName

        try {
            val pm = holder.itemView.context.packageManager
            val icon = pm.getApplicationIcon(profile.packageName)
            holder.ivAppIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            holder.ivAppIcon.setImageResource(android.R.mipmap.sym_def_app_icon)
        }

        holder.tvStatusText.visibility = View.GONE
        holder.vStatusDot.visibility = View.GONE

        if (profile.isCalibrated) {
            holder.btnAction.text = "Start Agent"
            holder.btnAction.setIconResource(R.drawable.ic_play)
            holder.btnAction.setOnClickListener { onRun(profile) }
        } else {
            holder.btnAction.text = "Calibrate"
            holder.btnAction.setIconResource(R.drawable.ic_edit)
            holder.btnAction.setOnClickListener { onCalibrate(profile) }
        }

        holder.btnDeleteProfile.setOnClickListener { onDelete(profile) }
        holder.btnExportProfile.setOnClickListener { onExport(profile) }
    }

    override fun getItemCount(): Int = profiles.size

    fun updateData(newProfiles: List<AgentProfile>) {
        this.profiles = newProfiles
        notifyDataSetChanged()
    }
}