package me.rerere.rikkahub.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ShizukuState { UNAVAILABLE, UNAUTHORIZED, AUTHORIZED, DENIED, UNSUPPORTED, ERROR }

class ShizukuManager internal constructor(private val backend: ShizukuPermissionBackend) : AutoCloseable {
    constructor() : this(AndroidShizukuPermissionBackend)

    private val _state = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var registration: AutoCloseable? = null
    private var pendingRequestCode: Int? = null
    private var closed = false

    init {
        try {
            registration = backend.observe(
                onBinderReceived = ::refresh,
                onBinderDead = {
                    if (!closed) {
                        pendingRequestCode = null
                        _state.value = ShizukuState.UNAVAILABLE
                        _message.value = "Shizuku 已停止或连接断开，请打开 Shizuku 启动服务后返回。"
                    }
                },
                onPermissionResult = ::handlePermissionResult,
            )
            refresh()
        } catch (error: Exception) {
            showError("初始化 Shizuku 监听失败", error)
        }
    }

    fun refresh() {
        if (closed) return
        try {
            _state.value = when {
                !backend.isBinderAlive() -> ShizukuState.UNAVAILABLE
                backend.isPreV11() -> ShizukuState.UNSUPPORTED
                backend.isPermissionGranted() -> ShizukuState.AUTHORIZED
                backend.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED
                else -> ShizukuState.UNAUTHORIZED
            }
            _message.value = when (_state.value) {
                ShizukuState.UNAVAILABLE -> "尚未连接到 Shizuku。请先在 Shizuku 首页启动服务，再返回此页重新检查。"
                ShizukuState.UNSUPPORTED -> "Shizuku 版本过旧，请更新到支持 API v11 或以上的版本。"
                ShizukuState.DENIED -> "Shizuku 已拒绝再次询问。请打开 Shizuku，在已授权应用/应用管理中允许下方包名对应的应用。"
                else -> null
            }
        } catch (error: Exception) {
            showError("检查 Shizuku 状态失败", error)
        }
    }

    fun requestPermission(requestCode: Int) {
        if (closed) return
        refresh()
        when (_state.value) {
            ShizukuState.UNAUTHORIZED -> {
                try {
                    pendingRequestCode = requestCode
                    _message.value = "已发送授权请求，请在 Shizuku 弹窗中选择允许。若未出现弹窗，请打开 Shizuku 检查应用授权。"
                    backend.requestPermission(requestCode)
                } catch (error: Exception) {
                    pendingRequestCode = null
                    showError("请求 Shizuku 授权失败", error)
                }
            }
            ShizukuState.AUTHORIZED -> _message.value = "Shizuku 已授权。"
            else -> Unit // refresh() provides the reason and recovery steps.
        }
    }

    private fun handlePermissionResult(requestCode: Int, granted: Boolean) {
        if (closed || requestCode != pendingRequestCode) return
        pendingRequestCode = null
        if (granted) {
            // Use the actual result instead of relying on a possibly stale permission snapshot.
            _state.value = ShizukuState.AUTHORIZED
            _message.value = "Shizuku 授权成功。"
        } else {
            refresh()
            if (_state.value == ShizukuState.UNAUTHORIZED) {
                _message.value = "Shizuku 授权被拒绝，可重新请求授权或打开 Shizuku 修改应用授权。"
            }
        }
    }

    private fun showError(action: String, error: Exception) {
        _state.value = ShizukuState.ERROR
        _message.value = "$action：${error.message ?: error.javaClass.simpleName}"
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingRequestCode = null
        registration?.close()
        registration = null
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }
}
