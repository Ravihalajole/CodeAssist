package org.ravi.codeassist

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.ravi.codeassist.database.ExecutionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExecutionHistoryAdapter(
    private val records: List<ExecutionRecord>
) : RecyclerView.Adapter<ExecutionHistoryAdapter.RecordViewHolder>() {

    class RecordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val vStatusDot: View = view.findViewById(R.id.vExecStatusDot)
        val tvStatus: TextView = view.findViewById(R.id.tvExecStatus)
        val tvTime: TextView = view.findViewById(R.id.tvExecTime)
        val tvMeta: TextView = view.findViewById(R.id.tvExecMeta)
        val tvLogs: TextView = view.findViewById(R.id.tvExecLogs)
    }

    private val timeFormatter = SimpleDateFormat("MMM dd · hh:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_execution_record, parent, false)
        return RecordViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = records[position]
        val ctx = holder.itemView.context
        val statusColor = ContextCompat.getColor(
            ctx, if (record.success) R.color.brand_mint else R.color.state_red
        )
        holder.tvStatus.text = if (record.success) "SUCCESS" else "FAILED"
        holder.tvStatus.setTextColor(statusColor)
        holder.vStatusDot.backgroundTintList = ColorStateList.valueOf(statusColor)
        holder.tvTime.text = timeFormatter.format(Date(record.timestamp))
        val workspaceLabel = record.workspaceRoot.substringAfterLast('/').ifBlank { record.workspaceRoot }
        holder.tvMeta.text = "${record.commandCount} command(s) · $workspaceLabel"
        holder.tvLogs.text = record.logs.trim().takeIf { it.isNotEmpty() } ?: "(no logs)"
    }

    override fun getItemCount(): Int = records.size
}