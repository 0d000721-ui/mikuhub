package me.rerere.rikkahub.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.ExecutionApprovalMode
import me.rerere.rikkahub.data.ai.ExecutionApprovalSnapshot

@kotlinx.serialization.Serializable
enum class DeviceTransport { ADB, SHIZUKU, ROOT }
enum class DeviceAuthorization { ASK, SESSION, REVOKED }

data class DeviceSessionState(
    val authorization: DeviceAuthorization = DeviceAuthorization.ASK,
    val stopped: Boolean = false,
    val preferredTransport: DeviceTransport = DeviceTransport.SHIZUKU,
)
data class DeviceAuditEntry(
    val command: String,
    val transport: DeviceTransport,
    val risk: DeviceCommandRisk,
    val allowed: Boolean,
    val output: String,
    val error: String? = null,
)

interface DeviceCommandRunner {
    suspend fun run(command: String): String
}

class DeviceCommandRejectedException(message: String) : IllegalStateException(message)

class DeviceAccessSession(
    private val approvalMode: () -> ExecutionApprovalMode = { ExecutionApprovalMode.IMPORTANT_ONLY },
    private val approvalSnapshot: (() -> ExecutionApprovalSnapshot)? = null,
    private val record: (DeviceAuditEntry) -> Unit = {},
) {
    private val lock = Any()
    private val mutex = Mutex()
    private val auditEntries = ArrayDeque<DeviceAuditEntry>()
    private var activeJob: Job? = null
    private var revision = 0L
    private val _state = MutableStateFlow(DeviceSessionState())
    val state = _state.asStateFlow()
    val authorization get() = state.value.authorization
    val stopped get() = state.value.stopped
    internal val accessVersion get() = synchronized(lock) { revision }

    fun authorizeSession() = changeState { it.copy(authorization = DeviceAuthorization.SESSION, stopped = false) }
    fun revoke() = changeState { it.copy(authorization = DeviceAuthorization.REVOKED) }
    fun stop() = changeState { it.copy(authorization = DeviceAuthorization.REVOKED, stopped = true) }

    /** A changed approval choice invalidates pending/queued device work, without reviving a stopped session. */
    fun approvalModeChanged() = changeState { it }

    private fun currentApprovalSnapshot() = approvalSnapshot?.invoke() ?: ExecutionApprovalSnapshot(approvalMode(), 0L)
    val executionApprovalMode get() = currentApprovalSnapshot().mode

    /** Called from device settings; selection expires with the process. */
    fun selectTransport(transport: DeviceTransport) {
        require(transport == DeviceTransport.SHIZUKU || transport == DeviceTransport.ROOT)
        changeState { it.copy(preferredTransport = transport) }
    }

    private fun changeState(transform: (DeviceSessionState) -> DeviceSessionState) {
        val job = synchronized(lock) {
            revision++
            _state.value = transform(_state.value)
            activeJob
        }
        job?.cancel(CancellationException("设备会话授权已改变"))
    }

    fun audit(): List<DeviceAuditEntry> = synchronized(lock) { auditEntries.toList() }

    private fun append(entry: DeviceAuditEntry) {
        val bounded = entry.copy(output = entry.output.take(16_384), error = entry.error?.take(4_096))
        synchronized(lock) {
            auditEntries.addLast(bounded)
            while (auditEntries.size > 500) auditEntries.removeFirst()
        }
        // A storage failure must not turn an already executed uninstall into a retryable command failure.
        runCatching { record(bounded) }.onFailure { System.err.println("Device audit persistence failed: ${it.message}") }
    }

    suspend fun execute(
        preview: DeviceCommandPreview,
        transport: DeviceTransport,
        runner: DeviceCommandRunner,
        requestConfirmation: suspend (DeviceCommandPreview, DeviceTransport) -> Int = { _, _ -> 0 },
    ): String {
        val expectedRevision = synchronized(lock) { revision }
        val expectedApproval = currentApprovalSnapshot()
        return mutex.withLock {
            coroutineScope {
                val checked = DeviceCommandPolicy.preview(preview.command, preview.explanation, preview.impact, preview.riskExplanation)
                val job = currentCoroutineContext()[Job]
                var allowed = false
                try {
                    synchronized(lock) {
                        checkAccess(expectedRevision, expectedApproval)
                        activeJob = job
                    }
                    check(checked.risk != DeviceCommandRisk.BLOCKED) { checked.rejectionReason ?: "Blocked device command" }
                    val confirmations = when {
                        expectedApproval.mode == ExecutionApprovalMode.UNRESTRICTED -> 0
                        checked.risk == DeviceCommandRisk.KERNEL_CRITICAL -> 3
                        checked.risk == DeviceCommandRisk.DESTRUCTIVE || transport == DeviceTransport.ROOT -> 1
                        checked.requiresConfirmation -> 1
                        else -> 0
                    }
                    if (confirmations > 0) {
                        val count = requestConfirmation(checked.copy(confirmationCount = confirmations, requiresConfirmation = true), transport)
                        if (count < confirmations) throw DeviceCommandRejectedException("用户拒绝了操作，或设备确认已超时；请勿自动重试")
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) { checkAccess(expectedRevision, expectedApproval) }
                    allowed = true
                    val output = runner.run(checked.command)
                    currentCoroutineContext().ensureActive()
                    append(DeviceAuditEntry(checked.command, transport, checked.risk, true, output))
                    output
                } catch (error: Exception) {
                    append(DeviceAuditEntry(checked.command, transport, checked.risk, allowed,
                        (error as? DeviceShellException)?.result?.output.orEmpty(), error.message))
                    throw error
                } finally {
                    synchronized(lock) { if (activeJob === job) activeJob = null }
                }
            }
        }
    }

    private fun checkAccess(expectedRevision: Long, expectedApproval: ExecutionApprovalSnapshot) {
        check(!stopped) { "设备 Agent 已停止，请在设备控制页开启新的会话" }
        check(authorization != DeviceAuthorization.REVOKED) { "设备会话授权已撤销，请在设备控制页重新开启" }
        check(revision == expectedRevision) { "设备会话已改变，旧请求不能继续执行" }
        check(currentApprovalSnapshot() == expectedApproval) { "执行授权模式已改变，请重新发起请求" }
    }
}
