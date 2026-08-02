package com.innostar.appdemo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.innostar.appdemo.model.CommandRecord
import com.innostar.appdemo.model.PendingReportRecord

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "app_demo_android.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_COMMANDS = "commands"
        private const val TABLE_PENDING_REPORTS = "pending_reports"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createCommandsTable = """
            CREATE TABLE $TABLE_COMMANDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id TEXT UNIQUE NOT NULL,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                action TEXT NOT NULL,
                params_json TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                result_json TEXT,
                error_json TEXT
            );
        """.trimIndent()

        val createPendingReportsTable = """
            CREATE TABLE $TABLE_PENDING_REPORTS (
                request_id TEXT PRIMARY KEY NOT NULL,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                action TEXT NOT NULL,
                execution_state TEXT NOT NULL,
                result_json TEXT,
                error_json TEXT,
                report_payload_hash TEXT NOT NULL,
                status TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_attempt_at INTEGER NOT NULL
            );
        """.trimIndent()

        db.execSQL(createCommandsTable)
        db.execSQL(createPendingReportsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_COMMANDS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PENDING_REPORTS")
        onCreate(db)
    }

    fun isDuplicateRequestId(requestId: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_COMMANDS,
            arrayOf("request_id"),
            "request_id = ?",
            arrayOf(requestId),
            null, null, null
        )
        val exists = cursor.use { it.count > 0 }
        return exists
    }

    fun saveCommand(record: CommandRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("request_id", record.requestId)
            put("user_id", record.userId)
            put("device_id", record.deviceId)
            put("action", record.action)
            put("params_json", record.paramsJson)
            put("received_at", record.receivedAt)
            put("status", record.status)
            put("result_json", record.resultJson)
            put("error_json", record.errorJson)
        }
        return db.insertWithOnConflict(TABLE_COMMANDS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateCommandStatus(requestId: String, status: String, resultJson: String?, errorJson: String?) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            put("result_json", resultJson)
            put("error_json", errorJson)
        }
        db.update(TABLE_COMMANDS, values, "request_id = ?", arrayOf(requestId))
    }

    fun savePendingReport(record: PendingReportRecord) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("request_id", record.requestId)
            put("user_id", record.userId)
            put("device_id", record.deviceId)
            put("action", record.action)
            put("execution_state", record.executionState)
            put("result_json", record.resultJson)
            put("error_json", record.errorJson)
            put("report_payload_hash", record.reportPayloadHash)
            put("status", record.status)
            put("attempts", record.attempts)
            put("last_attempt_at", record.lastAttemptAt)
        }
        db.insertWithOnConflict(TABLE_PENDING_REPORTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateReportStatus(requestId: String, status: String, attempts: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            put("attempts", attempts)
            put("last_attempt_at", System.currentTimeMillis())
        }
        db.update(TABLE_PENDING_REPORTS, values, "request_id = ?", arrayOf(requestId))
    }

    fun getPendingReports(): List<PendingReportRecord> {
        val db = readableDatabase
        val list = mutableListOf<PendingReportRecord>()
        val cursor = db.query(
            TABLE_PENDING_REPORTS,
            null,
            "status IN ('PENDING', 'FAILED')",
            null, null, null, "last_attempt_at ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    PendingReportRecord(
                        requestId = it.getString(it.getColumnIndexOrThrow("request_id")),
                        userId = it.getString(it.getColumnIndexOrThrow("user_id")),
                        deviceId = it.getString(it.getColumnIndexOrThrow("device_id")),
                        action = it.getString(it.getColumnIndexOrThrow("action")),
                        executionState = it.getString(it.getColumnIndexOrThrow("execution_state")),
                        resultJson = it.getString(it.getColumnIndexOrThrow("result_json")),
                        errorJson = it.getString(it.getColumnIndexOrThrow("error_json")),
                        reportPayloadHash = it.getString(it.getColumnIndexOrThrow("report_payload_hash")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        attempts = it.getInt(it.getColumnIndexOrThrow("attempts")),
                        lastAttemptAt = it.getLong(it.getColumnIndexOrThrow("last_attempt_at"))
                    )
                )
            }
        }
        return list
    }

    fun getAllCommands(): List<CommandRecord> {
        val db = readableDatabase
        val list = mutableListOf<CommandRecord>()
        val cursor = db.query(TABLE_COMMANDS, null, null, null, null, null, "id DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    CommandRecord(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        requestId = it.getString(it.getColumnIndexOrThrow("request_id")),
                        userId = it.getString(it.getColumnIndexOrThrow("user_id")),
                        deviceId = it.getString(it.getColumnIndexOrThrow("device_id")),
                        action = it.getString(it.getColumnIndexOrThrow("action")),
                        paramsJson = it.getString(it.getColumnIndexOrThrow("params_json")),
                        receivedAt = it.getLong(it.getColumnIndexOrThrow("received_at")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        resultJson = it.getString(it.getColumnIndexOrThrow("result_json")),
                        errorJson = it.getString(it.getColumnIndexOrThrow("error_json"))
                    )
                )
            }
        }
        return list
    }
}
