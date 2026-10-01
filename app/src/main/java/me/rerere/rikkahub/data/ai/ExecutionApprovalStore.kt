package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Local user choice; excluded from settings exports and Android backup/device transfer. */
class ExecutionApprovalStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "execution-approval-mode"))
    private val mutex = Mutex()
    private val selection = MutableStateFlow(ExecutionApprovalSnapshot(
        mode = ExecutionApprovalMode.fromStoredValue(runCatching {
            file.openRead().bufferedReader().use { it.readText().trim() }
        }.getOrNull()),
        revision = 0L,
    ))
    private val _mode = MutableStateFlow(selection.value.mode)
    val mode = _mode.asStateFlow()
    val selectionChanges = selection.asStateFlow()
    fun snapshot(): ExecutionApprovalSnapshot = selection.value

    suspend fun selectMode(value: ExecutionApprovalMode) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val output = file.startWrite()
            try {
                output.write(value.storageValue.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            selection.value = ExecutionApprovalSnapshot(value, selection.value.revision + 1)
            _mode.value = value
        }
    }
}
