package org.ravi.codeassist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsAdapter(
    private var commits: List<GitManager.CommitInfo>,
    private val onCommitClick: (GitManager.CommitInfo) -> Unit
) : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLogSectionHeader: TextView = view.findViewById(R.id.tvLogSectionHeader)
        val tvCommitHeader: TextView = view.findViewById(R.id.tvCommitHeader)
        val tvCommitAuthor: TextView = view.findViewById(R.id.tvCommitAuthor)
        val tvCommitHash: TextView = view.findViewById(R.id.tvCommitHash)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val commit = commits[position]
        val lines = commit.message.lines().filter { it.isNotBlank() }

        // Use the first line of the commit message (the summary text) as the primary header title
        val displayTitle = lines.firstOrNull()?.trim() ?: "Automated Execution"
        holder.tvCommitHeader.text = displayTitle

        // Format timeline anchors to group adjacent row structures as requested by image reference 1000177372.png
        val sectionFormatter = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
        val currentSectionDate = "Commits on ${sectionFormatter.format(Date(commit.time))}"
        
        if (position == 0) {
            holder.tvLogSectionHeader.visibility = View.VISIBLE
            holder.tvLogSectionHeader.text = currentSectionDate
        } else {
            val previousCommit = commits[position - 1]
            val previousSectionDate = "Commits on ${sectionFormatter.format(Date(previousCommit.time))}"
            if (currentSectionDate == previousSectionDate) {
                holder.tvLogSectionHeader.visibility = View.GONE
            } else {
                holder.tvLogSectionHeader.visibility = View.VISIBLE
                holder.tvLogSectionHeader.text = currentSectionDate
            }
        }

        // Compute descriptive subtext and short hash components matching the style of 1000177372.png
        val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        holder.tvCommitAuthor.text = "${commit.author} committed at ${timeFormatter.format(Date(commit.time))}"
        holder.tvCommitHash.text = commit.hash.take(7)

        holder.itemView.setOnClickListener {
            onCommitClick(commit)
        }
    }

    override fun getItemCount() = commits.size

    fun updateData(newCommits: List<GitManager.CommitInfo>) {
        this.commits = newCommits
        notifyDataSetChanged()
    }
}