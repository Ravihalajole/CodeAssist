package org.ravi.codeassist.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CodeAssistDatabase(context: Context) : SQLiteOpenHelper(context, "codeassist_agent_db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE agent_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profileName TEXT NOT NULL,
                packageName TEXT NOT NULL,
                isCalibrated INTEGER NOT NULL DEFAULT 0,
                scrollLeftPct REAL NOT NULL DEFAULT 0.35,
                scrollTopPct REAL NOT NULL DEFAULT 0.35,
                scrollRightPct REAL NOT NULL DEFAULT 0.65,
                scrollBottomPct REAL NOT NULL DEFAULT 0.65
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE element_signatures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profileId INTEGER NOT NULL,
                role TEXT NOT NULL,
                resourceId TEXT,
                contentDescription TEXT,
                hintText TEXT,
                boundsX INTEGER NOT NULL,
                boundsY INTEGER NOT NULL,
                hierarchyPath TEXT,
                className TEXT,
                FOREIGN KEY(profileId) REFERENCES agent_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX index_element_signatures_profileId ON element_signatures(profileId)")

        db.execSQL(CREATE_EXECUTION_HISTORY)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Incremental migrations. NEVER drop or recreate the tables here — agent
        // profiles and calibrated element signatures are the user's primary
        // investment in this app and a destructive onUpgrade would silently
        // erase them all. Every step below is additive, and the idempotent
        // schema alignment at the end guarantees the final schema no matter
        // which version chain was traversed (including skipped intermediate
        // versions that previously hit the destructive fallback).
        var v = oldVersion
        while (v < newVersion) {
            when (v) {
                // 1 -> 2 / 2 -> 3: pre-v4 versions lacked the scroll-bound
                // columns; they are added non-destructively in the alignment
                // block below.
                3 -> ensureExecutionHistoryTable(db)
                else -> {
                    // No explicit migration for v -> v+1. Previously this fell
                    // back to dropping and recreating every table, silently
                    // erasing calibration data. It now logs and relies on the
                    // idempotent alignment below instead.
                    android.util.Log.w(
                        "CodeAssistDatabase",
                        "No explicit migration from $v to ${v + 1}; aligning schema non-destructively."
                    )
                }
            }
            v++
        }
        alignSchema(db)
    }

    /**
     * Idempotent, additive schema alignment. Safe to run on any upgrade path:
     * adds missing columns/tables without ever dropping or rewriting existing
     * rows.
     */
    private fun alignSchema(db: SQLiteDatabase) {
        if (!hasColumn(db, "agent_profiles", "scrollLeftPct")) {
            db.execSQL("ALTER TABLE agent_profiles ADD COLUMN scrollLeftPct REAL NOT NULL DEFAULT 0.35")
            db.execSQL("ALTER TABLE agent_profiles ADD COLUMN scrollTopPct REAL NOT NULL DEFAULT 0.35")
            db.execSQL("ALTER TABLE agent_profiles ADD COLUMN scrollRightPct REAL NOT NULL DEFAULT 0.65")
            db.execSQL("ALTER TABLE agent_profiles ADD COLUMN scrollBottomPct REAL NOT NULL DEFAULT 0.65")
        }
        ensureExecutionHistoryTable(db)
    }

    private fun ensureExecutionHistoryTable(db: SQLiteDatabase) {
        db.execSQL(CREATE_EXECUTION_HISTORY)
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        return try {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return@use true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        private val CREATE_EXECUTION_HISTORY = """
            CREATE TABLE IF NOT EXISTS execution_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                success INTEGER NOT NULL,
                commandCount INTEGER NOT NULL,
                logs TEXT,
                workspaceRoot TEXT
            )
        """.trimIndent()

        @Volatile
        private var INSTANCE: CodeAssistDatabase? = null

        fun getDatabase(context: Context): CodeAssistDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = CodeAssistDatabase(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}