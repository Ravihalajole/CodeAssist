package org.ravi.codeassist.database

import android.content.ContentValues
import android.content.Context

data class ExecutionRecord(
    val id: Long = 0,
    val timestamp: Long,
    val success: Boolean,
    val commandCount: Int,
    val logs: String,
    val workspaceRoot: String
)

object ExecutionHistory {

    fun record(context: Context, success: Boolean, commandCount: Int, logs: String, workspaceRoot: String) {
        try {
            val db = CodeAssistDatabase.getDatabase(context).writableDatabase
            val values = ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("success", if (success) 1 else 0)
                put("commandCount", commandCount)
                put("logs", logs.take(12000))
                put("workspaceRoot", workspaceRoot)
            }
            db.insert("execution_history", null, values)
        } catch (_: Exception) {}
    }

    fun recent(context: Context, limit: Int = 100): List<ExecutionRecord> {
        val list = mutableListOf<ExecutionRecord>()
        try {
            val db = CodeAssistDatabase.getDatabase(context).readableDatabase
            db.rawQuery(
                "SELECT * FROM execution_history ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        ExecutionRecord(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                            success = cursor.getInt(cursor.getColumnIndexOrThrow("success")) == 1,
                            commandCount = cursor.getInt(cursor.getColumnIndexOrThrow("commandCount")),
                            logs = cursor.getString(cursor.getColumnIndexOrThrow("logs")) ?: "",
                            workspaceRoot = cursor.getString(cursor.getColumnIndexOrThrow("workspaceRoot")) ?: ""
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun clear(context: Context) {
        try {
            val db = CodeAssistDatabase.getDatabase(context).writableDatabase
            db.delete("execution_history", null, null)
        } catch (_: Exception) {}
    }
}
