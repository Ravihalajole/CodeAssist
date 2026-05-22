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
        val tvCommitHeader: TextView = view.findViewById(R.id.tvCommitHeader)
        val tvCommitAuthor: TextView = view.findViewById(R.id.tvCommitAuthor)
        val tvCommitMessage: TextView = view.findViewById(R.id.tvCommitMessage)
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

        // Group metadata attributes (short commit hash + localized execution timestamp) vertically
        val df = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())
        holder.tvCommitAuthor.text = "${commit.hash.take(7)}  •  ${df.format(Date(commit.time))}"
        
        // Display any remaining line items (such as targeted execution file lists) inside a structural body
        val underlyingDetails = lines.drop(1).joinToString("\n").trim()
        if (underlyingDetails.isNotEmpty()) {
            holder.tvCommitMessage.visibility = View.VISIBLE
            holder.tvCommitMessage.text = underlyingDetails
        } else {
            holder.tvCommitMessage.visibility = View.GONE
        }

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