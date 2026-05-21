package org.ravi.codeassist

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// The Data Model
data class ExecutionLog(
    val id: Long = 0,
    val batchId: String,
    val timestamp: Long,
    val commandType: String,
    val targetPath: String,
    val isSuccess: Boolean,
    val message: String,
    val backupPath: String? = null
)

class LogDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "CodeAssistLogs.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_LOGS = "execution_logs"

        private const val COL_ID = "id"
        private const val COL_BATCH_ID = "batch_id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_COMMAND = "command_type"
        private const val COL_PATH = "target_path"
        private const val COL_SUCCESS = "is_success"
        private const val COL_MESSAGE = "message"
        private const val COL_BACKUP_PATH = "backup_path"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_LOGS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_BATCH_ID TEXT,
                $COL_TIMESTAMP INTEGER,
                $COL_COMMAND TEXT,
                $COL_PATH TEXT,
                $COL_SUCCESS INTEGER,
                $COL_MESSAGE TEXT,
                $COL_BACKUP_PATH TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    fun insertLog(log: ExecutionLog) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COL_BATCH_ID, log.batchId)
            put(COL_TIMESTAMP, log.timestamp)
            put(COL_COMMAND, log.commandType)
            put(COL_PATH, log.targetPath)
            put(COL_SUCCESS, if (log.isSuccess) 1 else 0)
            put(COL_MESSAGE, log.message)
            put(COL_BACKUP_PATH, log.backupPath)
        }
        db.insert(TABLE_LOGS, null, values)
        db.close()
    }

    fun getAllLogs(): List<ExecutionLog> {
        val logList = mutableListOf<ExecutionLog>()
        val db = this.readableDatabase
        // Order by newest first
        val cursor = db.rawQuery("SELECT * FROM $TABLE_LOGS ORDER BY $COL_TIMESTAMP DESC", null)

        if (cursor.moveToFirst()) {
            do {
                val log = ExecutionLog(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    batchId = cursor.getString(cursor.getColumnIndexOrThrow(COL_BATCH_ID)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    commandType = cursor.getString(cursor.getColumnIndexOrThrow(COL_COMMAND)),
                    targetPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_PATH)),
                    isSuccess = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SUCCESS)) == 1,
                    message = cursor.getString(cursor.getColumnIndexOrThrow(COL_MESSAGE)),
                    backupPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_BACKUP_PATH))
                )
                logList.add(log)
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return logList
    }

    fun getLastModifyingBatch(): List<ExecutionLog> {
        val batchLogs = mutableListOf<ExecutionLog>()
        val db = this.readableDatabase
        
        val batchQuery = """
            SELECT $COL_BATCH_ID FROM $TABLE_LOGS 
            WHERE $COL_SUCCESS = 1 
            AND $COL_COMMAND IN ('PatchFile', 'DeleteFile', 'CreateFile') 
            ORDER BY $COL_TIMESTAMP DESC LIMIT 1
        """.trimIndent()
        
        var latestBatchId: String? = null
        val batchCursor = db.rawQuery(batchQuery, null)
        if (batchCursor.moveToFirst()) {
            latestBatchId = batchCursor.getString(0)
        }
        batchCursor.close()

        if (latestBatchId == null) return batchLogs

        val query = "SELECT * FROM $TABLE_LOGS WHERE $COL_BATCH_ID = ?"
        val cursor = db.rawQuery(query, arrayOf(latestBatchId))
        
        if (cursor.moveToFirst()) {
            do {
                val log = ExecutionLog(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    batchId = cursor.getString(cursor.getColumnIndexOrThrow(COL_BATCH_ID)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    commandType = cursor.getString(cursor.getColumnIndexOrThrow(COL_COMMAND)),
                    targetPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_PATH)),
                    isSuccess = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SUCCESS)) == 1,
                    message = cursor.getString(cursor.getColumnIndexOrThrow(COL_MESSAGE)),
                    backupPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_BACKUP_PATH))
                )
                batchLogs.add(log)
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        
        return batchLogs.reversed()
    }
}
