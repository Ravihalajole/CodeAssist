package org.ravi.codeassist

import android.graphics.Color
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
        val tvLogCommand: TextView = view.findViewById(R.id.tvLogCommand)
        val tvLogStatus: TextView = view.findViewById(R.id.tvLogStatus)
        val tvLogPath: TextView = view.findViewById(R.id.tvLogPath)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val commit = commits[position]

        val df = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
        holder.tvLogCommand.text = commit.hash.take(7) + " - " + df.format(Date(commit.time))
        
        // Show up to 4 lines of the commit message to include the operations summary
        val displayMsg = commit.message.lines()
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString("\n")
            
        holder.tvLogPath.text = displayMsg.ifEmpty { "No message" }

        holder.tvLogStatus.text = "COMMIT"
        holder.tvLogStatus.setTextColor(Color.parseColor("#4CAF50"))

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
