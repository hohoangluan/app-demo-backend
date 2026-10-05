package com.youreyes.app.command

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite store for received commands and their reports.
 *
 * `commands` has a unique `request_id`, which is what makes a duplicate push
 * a no-op. `pending_reports` holds every result until the server accepts it.
 */
class CommandStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), CommandLog {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $COMMANDS (
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
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $PENDING_REPORTS (
                request_id TEXT PRIMARY KEY NOT NULL,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                action TEXT NOT NULL,
                execution_state TEXT NOT NULL,
                result_json TEXT,
                error_json TEXT,
                report_payload_hash TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_attempt_at INTEGER NOT NULL,
                executed_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 kept no execution time, so its last attempt is the best estimate.
            db.execSQL("ALTER TABLE $PENDING_REPORTS ADD COLUMN executed_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE $PENDING_REPORTS SET executed_at = last_attempt_at")
        }
    }

    /**
     * Records a newly received command. Returns false when the `request_id` was
     * already received, so the caller must not execute it again.
     */
    override fun insertCommandIfNew(record: CommandRecord): Boolean {
        val values = ContentValues().apply {
            put("request_id", record.requestId)
            put("user_id", record.userId)
            put("device_id", record.deviceId)
            put("action", record.action)
            put("params_json", record.paramsJson)
            put("received_at", record.receivedAt)
            put("status", record.status)
        }
        return writableDatabase.insertWithOnConflict(
            COMMANDS, null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    override fun finishCommand(requestId: String, status: String, resultJson: String?, errorJson: String?) {
        val values = ContentValues().apply {
            put("status", status)
            put("result_json", resultJson)
            put("error_json", errorJson)
        }
        writableDatabase.update(COMMANDS, values, "request_id = ?", arrayOf(requestId))
    }

    override fun savePendingReport(record: PendingReportRecord) {
        val values = ContentValues().apply {
            put("request_id", record.requestId)
            put("user_id", record.userId)
            put("device_id", record.deviceId)
            put("action", record.action)
            put("execution_state", record.executionState)
            put("result_json", record.resultJson)
            put("error_json", record.errorJson)
            put("status", record.status)
            put("attempts", record.attempts)
            put("last_attempt_at", record.executedAt)
            put("executed_at", record.executedAt)
        }
        writableDatabase.insertWithOnConflict(
            PENDING_REPORTS, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun updateReportStatus(requestId: String, status: String, attempts: Int) {
        val values = ContentValues().apply {
            put("status", status)
            put("attempts", attempts)
            put("last_attempt_at", System.currentTimeMillis())
        }
        writableDatabase.update(PENDING_REPORTS, values, "request_id = ?", arrayOf(requestId))
    }

    /** Reports not yet accepted by the server, oldest first. */
    override fun unsentReports(): List<PendingReportRecord> =
        readableDatabase.query(
            PENDING_REPORTS, null,
            "status IN (?, ?)", arrayOf(ReportStatus.PENDING, ReportStatus.FAILED),
            null, null, "executed_at ASC",
        ).use { cursor -> cursor.mapRows(::toPendingReport) }

    /** Every received command, newest first. */
    fun allCommands(): List<CommandRecord> =
        readableDatabase.query(COMMANDS, null, null, null, null, null, "id DESC")
            .use { cursor -> cursor.mapRows(::toCommand) }

    private fun toPendingReport(c: Cursor) = PendingReportRecord(
        requestId = c.string("request_id")!!,
        userId = c.string("user_id")!!,
        deviceId = c.string("device_id")!!,
        action = c.string("action")!!,
        executionState = c.string("execution_state")!!,
        resultJson = c.string("result_json"),
        errorJson = c.string("error_json"),
        status = c.string("status")!!,
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        executedAt = c.getLong(c.getColumnIndexOrThrow("executed_at")),
    )

    private fun toCommand(c: Cursor) = CommandRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        requestId = c.string("request_id")!!,
        userId = c.string("user_id")!!,
        deviceId = c.string("device_id")!!,
        action = c.string("action")!!,
        paramsJson = c.string("params_json")!!,
        receivedAt = c.getLong(c.getColumnIndexOrThrow("received_at")),
        status = c.string("status")!!,
        resultJson = c.string("result_json"),
        errorJson = c.string("error_json"),
    )

    private fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))

    private fun <T> Cursor.mapRows(map: (Cursor) -> T): List<T> {
        val rows = mutableListOf<T>()
        while (moveToNext()) rows += map(this)
        return rows
    }

    companion object {
        private const val DATABASE_NAME = "app_demo_android.db"
        private const val DATABASE_VERSION = 2
        private const val COMMANDS = "commands"
        private const val PENDING_REPORTS = "pending_reports"
    }
}
