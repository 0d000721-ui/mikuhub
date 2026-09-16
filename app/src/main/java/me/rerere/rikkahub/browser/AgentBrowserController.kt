package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.device.DownloadInstallManager
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.coroutines.resume

private class BrowserOperationStopped : CancellationException("浏览器操作已由用户停止")

/**
 * One explicit browser session, owned by the application rather than an Activity.
 * Leaving its page detaches the view, but retains the document for AI tools in chat.
 * Closing the session destroys the WebView and revokes every outstanding operation.
 */
class AgentBrowserController(context: Context, private val downloads: DownloadInstallManager) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val permission = BrowserPermission()
    private val _state = MutableStateFlow(AgentBrowserState())
    val state = _state.asStateFlow()
    private var webView: WebView? = null
    private var documentVersion = 0L
    private var snapshotVersion = -1L
    private var elementIds: Set<String> = emptySet()
    private var downloadAllowed = true
    private var downloadPermit: Long? = null
    private val pendingDownloads = mutableSetOf<Job>()
    private var activeAiJob: Job? = null

    fun setAiEnabled(enabled: Boolean) {
        mainThread()
        permission.setEnabled(enabled)
        if (!enabled) {
            activeAiJob?.cancel(BrowserOperationStopped())
            downloadAllowed = false
            pendingDownloads.toList().forEach { it.cancel() }
            webView?.stopLoading()
        }
        _state.value = state.value.copy(
            aiEnabled = enabled,
            aiBusy = false,
            loading = if (enabled) state.value.loading else false,
            message = if (enabled) "已允许 AI 读取及操作此浏览器；点击、填写和跳转仍需确认" else "AI 浏览器操作已停止",
        )
    }

    fun manualNavigate(url: String) = manualAction {
        val target = browserUrl(url, addHttps = true)
        val view = ensureWebView()
        invalidateDocument()
        _state.value = state.value.copy(url = target, loading = true, error = null, message = null)
        view.loadUrl(target)
    }

    fun manualGoBack() = manualAction { webView?.let { if (it.canGoBack()) it.goBack() } }
    fun manualGoForward() = manualAction { webView?.let { if (it.canGoForward()) it.goForward() } }
    fun reload() = manualAction { webView?.reload() }
    fun stop() = manualAction {
        webView?.stopLoading()
        _state.value = state.value.copy(loading = false, aiBusy = false)
    }

    fun clearMessage() {
        mainThread()
        _state.value = state.value.copy(message = null, error = null)
    }

    fun webViewForDisplay(): WebView {
        mainThread()
        return ensureWebView().also { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun detachDisplay() {
        mainThread()
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun releaseBrowser() {
        mainThread()
        permission.setEnabled(false)
        activeAiJob?.cancel(BrowserOperationStopped())
        pendingDownloads.toList().forEach { it.cancel() }
        downloadAllowed = false
        val old = webView
        webView = null
        invalidateDocument()
        old?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.setDownloadListener(null)
            it.removeAllViews()
            it.destroy()
        }
        _state.value = AgentBrowserState(session = state.value.session + 1, message = "浏览器会话已关闭，AI 权限已撤销")
    }

    suspend fun status(): JsonObject = withContext(Dispatchers.Main.immediate) {
        buildJsonObject {
            put("enabled", permission.enabled)
            put("busy", state.value.aiBusy)
            put("instructions", "输入框工具栏的浏览器图标 → 允许 AI 操作此浏览器。用户可随时关闭开关停止操作。")
            if (permission.enabled) {
                put("url", state.value.url)
                put("loading", state.value.loading)
                state.value.error?.let { put("error", it) }
            }
        }
    }

    suspend fun navigate(url: String): JsonObject = aiAction("打开网页") { view, permit ->
        val target = browserUrl(url)
        permission.verify(permit)
        invalidateDocument()
        prepareAiDownload(permit)
        _state.value = state.value.copy(url = target, loading = true, error = null, message = null)
        view.loadUrl(target)
        awaitDocument(permit)
        snapshot(view, permit)
    }

    suspend fun readPage(): JsonObject = aiAction("读取网页") { view, permit ->
        awaitDocument(permit)
        snapshot(view, permit)
    }

    suspend fun click(elementId: String): JsonObject = aiAction("点击网页元素") { view, permit ->
        requireCurrentElement(elementId)
        prepareAiDownload(permit)
        val result = evaluate(view, BrowserScripts.click(elementId), permit)
        // A click may start a navigation asynchronously. The next read waits for its load.
        delay(250)
        permission.verify(permit)
        result
    }

    suspend fun fill(elementId: String, value: String): JsonObject = aiAction("填写网页字段") { view, permit ->
        require(value.length <= 8000) { "一次填写最多 8000 个字符" }
        requireCurrentElement(elementId)
        evaluate(view, BrowserScripts.fill(elementId, value), permit)
    }

    suspend fun scroll(direction: String): JsonObject = aiAction("滚动网页") { view, permit ->
        requireWebPage()
        evaluate(view, BrowserScripts.scroll(direction), permit)
    }

    suspend fun download(url: String, name: String?): JsonObject = aiAction("下载网页文件") { view, permit ->
        val target = browserUrl(url)
        permission.verify(permit)
        val id = downloads.enqueueDownload(
            url = target,
            name = name,
            userAgent = view.settings.userAgentString,
            referer = state.value.url.takeIf(::isBrowserUrl),
        )
        _state.value = state.value.copy(message = "已创建下载任务 #$id，请在下载中心查看真实进度")
        buildJsonObject {
            put("task_id", id)
            put("message", "已交给下载管理器。文件仍在下载中，请使用下载状态工具确认完成；需要网站 Cookie 的受保护下载不支持。")
        }
    }

    private fun manualAction(action: () -> Unit) {
        mainThread()
        permission.invalidate()
        activeAiJob?.cancel(BrowserOperationStopped())
        downloadPermit = null
        downloadAllowed = true
        invalidateDocument()
        try { action() } catch (error: Exception) {
            _state.value = state.value.copy(error = error.message ?: "浏览器操作未完成", loading = false)
        }
    }

    private suspend fun <T> aiAction(label: String, block: suspend (WebView, Long) -> T): T {
        val permit = withContext(Dispatchers.Main.immediate) { permission.permit() }
        return operations.withLock {
            withContext(Dispatchers.Main.immediate) {
                permission.verify(permit)
                val operationSession = state.value.session
                _state.value = state.value.copy(aiBusy = true, lastAction = label, error = null)
                try {
                    coroutineScope {
                        val action = async(start = CoroutineStart.LAZY) { block(ensureWebView(), permit) }
                        activeAiJob = action
                        action.start()
                        try { action.await() } finally { if (activeAiJob === action) activeAiJob = null }
                    }
                } catch (stopped: BrowserOperationStopped) {
                    currentCoroutineContext().ensureActive()
                    if (state.value.session == operationSession) {
                        _state.value = state.value.copy(error = "浏览器操作已由用户停止，请勿继续自动操作")
                    }
                    error("浏览器操作已由用户停止，请勿继续自动操作")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (state.value.session == operationSession) {
                        _state.value = state.value.copy(error = error.message ?: "AI 浏览器操作未完成")
                    }
                    throw error
                } finally {
                    if (state.value.session == operationSession) _state.value = state.value.copy(aiBusy = false)
                }
            }
        }
    }

    private suspend fun awaitDocument(permit: Long) {
        repeat(100) {
            permission.verify(permit)
            if (!state.value.loading) return
            delay(200)
        }
        // Partially loaded documents are still useful; snapshot explicitly reports loading.
        permission.verify(permit)
    }

    private suspend fun snapshot(view: WebView, permit: Long): JsonObject {
        requireWebPage()
        val version = documentVersion
        val data = evaluate(view, BrowserScripts.snapshot(UUID.randomUUID().toString()), permit)
        check(documentVersion == version) { "页面正在跳转，请重新读取页面" }
        elementIds = data["elements"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet()
        snapshotVersion = version
        return JsonObject(data + buildJsonObject {
            put("loading", state.value.loading)
            state.value.message?.let { put("browser_notice", it) }
            state.value.error?.let { put("browser_error", it) }
        })
    }

    private suspend fun evaluate(view: WebView, script: String, permit: Long): JsonObject {
        permission.verify(permit)
        check(view === webView) { "浏览器会话已关闭" }
        val raw = withTimeout(10_000) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }
        permission.verify(permit)
        check(view === webView) { "浏览器会话已关闭" }
        return Json.parseToJsonElement(decodeBrowserJavascriptResult(raw)).jsonObject
    }

    private fun requireWebPage() {
        require(isBrowserUrl(webView?.url)) { "浏览器还没有打开网页，请先使用 browser_navigate" }
    }

    private fun requireCurrentElement(id: String) {
        requireWebPage()
        check(snapshotVersion == documentVersion && id in elementIds) { "元素编号已失效，请先重新读取当前页面再操作" }
    }

    private fun invalidateDocument() {
        documentVersion++
        snapshotVersion = -1
        elementIds = emptySet()
    }

    private fun prepareAiDownload(permit: Long) {
        downloadPermit = permit
        downloadAllowed = true
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun ensureWebView(): WebView {
        mainThread()
        webView?.let { return it }
        return WebView(context).also { view ->
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                safeBrowsingEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            // Give detached documents a real viewport so DOM layout remains usable in chat.
            val metrics = context.resources.displayMetrics
            view.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    permission.invalidate()
                    activeAiJob?.cancel(BrowserOperationStopped())
                    downloadPermit = null
                    downloadAllowed = true
                }
                false
            }
            view.webChromeClient = object : WebChromeClient() {
                // This session uses an application Context, not an Activity window.
                // Do not let WebView attempt to create its default native JS dialogs.
                override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel()
                    unsupportedDialog(view)
                    return true
                }

                override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel()
                    unsupportedDialog(view)
                    return true
                }

                override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                    result.cancel()
                    unsupportedDialog(view)
                    return true
                }

                override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel()
                    unsupportedDialog(view)
                    return true
                }

                private fun unsupportedDialog(view: WebView) {
                    if (view === webView) _state.value = state.value.copy(error = "此网页请求原生弹窗，内置浏览器暂不支持；已取消该请求，请使用网页内的按钮或表单继续")
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view !== webView) return
                    _state.value = state.value.copy(progress = newProgress)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (view !== webView) return
                    _state.value = state.value.copy(title = title?.takeIf { it.isNotBlank() } ?: "内置浏览器")
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!isBrowserUrl(request.url.toString())) {
                        if (view === webView) _state.value = state.value.copy(error = "已阻止应用跳转或不支持的链接；只在内置浏览器打开 HTTP(S) 网页")
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme in setOf("file", "content", "intent", "javascript")) {
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (view !== webView) return
                    invalidateDocument()
                    if (!isBrowserUrl(url)) {
                        view.stopLoading()
                        _state.value = state.value.copy(loading = false, error = "不支持的网页地址")
                        return
                    }
                    _state.value = state.value.copy(url = url.orEmpty(), loading = true, progress = 0, error = null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (view !== webView) return
                    _state.value = state.value.copy(
                        url = view.url?.takeIf(::isBrowserUrl) ?: state.value.url,
                        loading = false, progress = 100, canGoBack = view.canGoBack(), canGoForward = view.canGoForward(),
                    )
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (view !== webView || !request.isForMainFrame) return
                    _state.value = state.value.copy(loading = false, error = "网页加载失败：${error.description}")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    if (view === webView) _state.value = state.value.copy(loading = false, error = "网站证书无效，已停止连接")
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (view === webView) {
                        releaseBrowser()
                        _state.value = state.value.copy(error = "网页进程已退出，请重新打开页面并开启 AI 操作")
                    }
                    return true
                }
            }
            view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (view !== webView || !downloadAllowed) return@setDownloadListener
                val permit = downloadPermit
                val referer = view.url?.takeIf(::isBrowserUrl)
                val job = scope.launch {
                    try {
                        permit?.let(permission::verify)
                        val source = browserUrl(url)
                        val name = URLUtil.guessFileName(source, contentDisposition, mimeType)
                        val id = downloads.enqueueDownload(source, name, mimeType, userAgent, referer)
                        _state.value = state.value.copy(loading = false, message = "下载任务 #$id 已开始，可在下载中心查看进度")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _state.value = state.value.copy(loading = false, error = error.message ?: "下载未启动；暂不支持 blob 文件或必须携带登录凭据的下载")
                    }
                }
                pendingDownloads += job
                job.invokeOnCompletion { scope.launch { pendingDownloads -= job } }
            }
        }
    }

    private fun mainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Browser UI must run on the main thread" }
    }
}
