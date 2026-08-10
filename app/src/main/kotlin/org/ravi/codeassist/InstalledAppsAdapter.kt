package org.ravi.codeassist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.ravi.codeassist.utils.InstalledApp

class InstalledAppsAdapter(
    private var apps: List<InstalledApp>,
    private val onAppSelected: (InstalledApp) -> Unit
) : RecyclerView.Adapter<InstalledAppsAdapter.AppViewHolder>() {

    private var filteredApps = apps.toList()
    private var selectedPosition = -1

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvAppPackage: TextView = view.findViewById(R.id.tvAppPackage)
        val ivAppIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val rootView: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_installed_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.tvAppName.text = app.appName
        holder.tvAppPackage.text = app.packageName

        try {
            val pm = holder.itemView.context.packageManager
            val icon = pm.getApplicationIcon(app.packageName)
            holder.ivAppIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            holder.ivAppIcon.setImageResource(android.R.mipmap.sym_def_app_icon)
        }

        if (position == selectedPosition) {
            holder.rootView.setBackgroundColor(android.graphics.Color.parseColor("#334CAF50"))
        } else {
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val typedArray = holder.itemView.context.obtainStyledAttributes(attrs)
            val backgroundResource = typedArray.getResourceId(0, 0)
            holder.rootView.setBackgroundResource(backgroundResource)
            typedArray.recycle()
        }

        holder.rootView.setOnClickListener {
            val previousSelection = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            notifyItemChanged(previousSelection)
            notifyItemChanged(selectedPosition)
            onAppSelected(app)
        }
    }

    override fun getItemCount(): Int = filteredApps.size

    fun updateData(newApps: List<InstalledApp>) {
        this.apps = newApps
        filter("")
    }

    fun filter(query: String) {
        filteredApps = if (query.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }
        selectedPosition = -1
        notifyDataSetChanged()
    }
}