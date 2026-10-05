package com.youreyes.app.command

import com.youreyes.app.actions.ActionExecutionResult
import com.youreyes.app.actions.ActionHandler
import com.youreyes.app.actions.ActionRegistry
import com.youreyes.app.core.DeviceCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [CommandLog] with the same duplicate rule as the SQLite store. */
private class FakeLog : CommandLog {
    val commands = linkedMapOf<String, CommandRecord>()
    val reports = linkedMapOf<String, PendingReportRecord>()

    override fun insertCommandIfNew(record: CommandRecord): Boolean =
        commands.putIfAbsent(record.requestId, record) == null

    override fun finishCommand(requestId: String, status: String, resultJson: String?, errorJson: String?) {
        commands[requestId] = commands.getValue(requestId).copy(status = status, resultJson = resultJson, errorJson = errorJson)
    }

    override fun savePendingReport(record: PendingReportRecord) {
        reports[record.requestId] = record
    }

    override fun updateReportStatus(requestId: String, status: String, attempts: Int) {
        reports[requestId] = reports.getValue(requestId).copy(status = status, attempts = attempts)
    }

    override fun unsentReports(): List<PendingReportRecord> =
        reports.values.filter { it.status == ReportStatus.PENDING || it.status == ReportStatus.FAILED }
}

private class FakeSender(var accept: Boolean = true) : ReportSender {
    val sent = mutableListOf<DeviceReportPayload>()
    override fun send(credentials: DeviceCredentials, payload: DeviceReportPayload): Boolean {
        sent += payload
        return accept
    }
}

class CommandPipelineTest {

    private val credentials = DeviceCredentials("http://server", "device-token")
    private val log = FakeLog()
    private val sender = FakeSender()
    private var handlerRuns = 0
    private var outcome: ActionExecutionResult = ActionExecutionResult.Success(mapOf("call_state" to "calling"))
    private val registry = ActionRegistry(
        mapOf("contact_call" to ActionHandler { _, _ -> handlerRuns++; outcome })
    )
    private val dispatcher = CommandDispatcher(log, registry, sender, context = null, clock = { 1_600_000_000_000 })

    private fun command(requestId: String = "req-1", action: String = "contact_call") =
        PushCommand(requestId, "user-1", "phone-1", action, """{"name":"Mẹ"}""")

    @Test
    fun `push data needs every routing field`() {
        val data = mapOf(
            "request_id" to "req-1", "user_id" to "user-1", "device_id" to "phone-1",
            "action" to "contact_call", "params_json" to """{"name":"Mẹ"}""",
        )
        assertEquals(command(), PushCommand.fromData(data))
        assertEquals("{}", PushCommand.fromData(data - "params_json")?.paramsJson)
        for (key in listOf("request_id", "user_id", "device_id", "action")) {
            assertNull(key, PushCommand.fromData(data - key))
        }
    }

    @Test
    fun `a command runs once and its report is sent immediately`() {
        val result = dispatcher.dispatch(command(), credentials)

        assertEquals(DispatchResult.Executed("req-1", ExecutionState.SUCCEEDED, reportSent = true), result)
        assertEquals(1, handlerRuns)
        val report = sender.sent.single()
        assertEquals("phone-1", report.deviceId)
        assertEquals("contact_call", report.action)
        assertEquals("calling", report.result?.get("call_state"))
        assertEquals("2020-09-13T12:26:40Z", report.timestamp)
        assertEquals(ReportStatus.SENT, log.reports.getValue("req-1").status)
    }

    @Test
    fun `a duplicate push never runs the handler again`() {
        dispatcher.dispatch(command(), credentials)

        val second = dispatcher.dispatch(command(), credentials)

        assertEquals(DispatchResult.DuplicateIgnored("req-1"), second)
        assertEquals(1, handlerRuns)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `an unsent report is retried later without re-running the handler`() {
        sender.accept = false
        dispatcher.dispatch(command(), credentials)
        assertEquals(ReportStatus.FAILED, log.reports.getValue("req-1").status)

        sender.accept = true
        val resent = ReportFlusher(log, sender).flush(credentials)

        assertEquals(1, resent)
        assertEquals(1, handlerRuns)
        assertEquals(2, sender.sent.size)
        assertEquals(sender.sent[0], sender.sent[1])
        assertEquals(ReportStatus.SENT, log.reports.getValue("req-1").status)
        assertEquals(0, ReportFlusher(log, sender).flush(credentials))
    }

    @Test
    fun `without credentials the report waits in the queue`() {
        val result = dispatcher.dispatch(command(), credentials = null)

        assertEquals(false, (result as DispatchResult.Executed).reportSent)
        assertTrue(sender.sent.isEmpty())
        assertEquals(ReportStatus.PENDING, log.reports.getValue("req-1").status)
    }

    @Test
    fun `failure details survive the retry path`() {
        val candidates = listOf(mapOf("name" to "Mẹ", "phone_number" to "***768"))
        outcome = ActionExecutionResult.Error(
            ReportErrorPayload("MULTIPLE_CONTACTS_FOUND", "Multiple contacts matched", mapOf("candidates" to candidates))
        )
        sender.accept = false
        dispatcher.dispatch(command(), credentials)
        sender.accept = true

        ReportFlusher(log, sender).flush(credentials)

        val retried = sender.sent.last()
        assertEquals(ExecutionState.FAILED, retried.executionState)
        assertNull(retried.result)
        assertEquals("MULTIPLE_CONTACTS_FOUND", retried.error?.code)
        assertEquals(candidates, retried.error?.details?.get("candidates"))
    }

    @Test
    fun `unknown action and crashing handler are both reported as failures`() {
        dispatcher.dispatch(command("req-unknown", action = "camera_capture"), credentials)
        val crashing = CommandDispatcher(
            log, ActionRegistry(mapOf("contact_call" to ActionHandler { _, _ -> error("boom") })), sender, null,
        )
        crashing.dispatch(command("req-crash"), credentials)

        assertEquals("UNSUPPORTED_ACTION", sender.sent[0].error?.code)
        assertEquals("EXECUTION_FAILED", sender.sent[1].error?.code)
        assertEquals(ExecutionState.FAILED.value, log.commands.getValue("req-crash").status)
    }

    @Test
    fun `a report the server keeps refusing is abandoned after max attempts`() {
        sender.accept = false
        dispatcher.dispatch(command(), credentials)
        val flusher = ReportFlusher(log, sender)

        repeat(ReportFlusher.MAX_ATTEMPTS + 2) { flusher.flush(credentials) }

        assertEquals(ReportStatus.ABANDONED, log.reports.getValue("req-1").status)
        assertEquals(ReportFlusher.MAX_ATTEMPTS, sender.sent.size)
    }

    @Test
    fun `a corrupt stored report is abandoned instead of guessed`() {
        log.savePendingReport(
            PendingReportRecord("req-bad", "user-1", "phone-1", "contact_call", executionState = "unknown")
        )

        ReportFlusher(log, sender).flush(credentials)

        assertTrue(sender.sent.isEmpty())
        assertEquals(ReportStatus.ABANDONED, log.reports.getValue("req-bad").status)
    }
}
