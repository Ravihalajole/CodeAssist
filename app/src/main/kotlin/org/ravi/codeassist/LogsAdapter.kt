package org.ravi.codeassist

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogsAdapter(
    private var groupedLogs: List<List<ExecutionLog>>,
    private val onBatchClick: (List<ExecutionLog>) -> Unit
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
        val batch = groupedLogs[position]
        val firstLog = batch.first()
        val isUndo = firstLog.commandType.startsWith("UNDO")
        val isSuccess = batch.all { it.isSuccess }

        holder.tvLogCommand.text = if (isUndo) "Undo Operation (${batch.size} actions)" else "Batch Execution (${batch.size} commands)"
        
        val paths = batch.map { it.targetPath }.filter { it.isNotEmpty() }
        holder.tvLogPath.text = if (paths.size > 1) {
            "${paths.first()} (+${paths.size - 1} more)"
        } else {
            paths.firstOrNull() ?: firstLog.message
        }

        if (isSuccess) {
            holder.tvLogStatus.text = "SUCCESS"
            holder.tvLogStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            holder.tvLogStatus.text = "FAILED"
            holder.tvLogStatus.setTextColor(Color.parseColor("#F44336"))
        }

        holder.itemView.setOnClickListener {
            onBatchClick(batch)
        }
    }

    override fun getItemCount() = groupedLogs.size

    fun updateData(newLogs: List<ExecutionLog>) {
        val groupedMap = java.util.LinkedHashMap<String, MutableList<ExecutionLog>>()
        for (log in newLogs) {
            groupedMap.getOrPut(log.batchId) { mutableListOf() }.add(log)
        }
        this.groupedLogs = groupedMap.values.toList()
        notifyDataSetChanged()
    }
}
