package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
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
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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
class AgentBrowserController(
    context: Context,
    private val downloads: DownloadInstallManager,
    private val imageChatBridge: BrowserImageChatBridge,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val permission = BrowserPermission()
    private val _state = MutableStateFlow(AgentBrowserState())
    val state = _state.asStateFlow()
    private var webView: WebView? = null
    private var webViewContext: MutableContextWrapper? = null
    private val displayOwnership = BrowserDisplayOwnership()
    private var pendingNavigation: String? = null
    private var renderJob: Job? = null
    private var visualVersion = -1L
    private var renderedVersion = -1L
    private var resourceFailures = 0
    private var consoleFailures = 0
    private var mainFrameFailed = false
    private var imageCacheKey: String? = null
    private var documentVersion = 0L
    private var snapshotVersion = -1L
    private var elementIds: Set<String> = emptySet()
    private var downloadAllowed = true
    private var downloadPermit: Long? = null
    private val pendingDownloads = mutableSetOf<Job>()
    private var activeAiJob: Job? = null
    private val imageDownloadGrant = BrowserImageDownloadGrant()
    private val imageSaver = BrowserImageSaver(this.context)
    private var imageDownloadJob: Job? = null

    fun openChatGptImages() {
        manualNavigate(CHATGPT_IMAGES_URL)
        _state.value = state.value.copy(message = "请在 ChatGPT 网页内登录，从 Images／创建图片进入生图；Image 2／2.5 的可用项以官方界面为准。生成后点网页的保存按钮。AI 操作仍需你开启下方开关。")
    }

    fun setAiEnabled(enabled: Boolean) {
        mainThread()
        permission.setEnabled(enabled)
        if (!enabled) {
            imageChatBridge.invalidate()
            activeAiJob?.cancel(BrowserOperationStopped())
            downloadAllowed = false
            pendingDownloads.toList().forEach { it.cancel() }
            imageDownloadJob?.cancel()
            imageDownloadGrant.clear()
            imageCacheKey?.let { key -> webView?.evaluateJavascript(BrowserImageScripts.clear(key), null) }
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
        invalidateDocument()
        _state.value = state.value.copy(url = target, loading = true, error = null, message = null, renderNotice = null, renderDiagnostics = null)
        val view = webView
        if (view == null) pendingNavigation = target else view.loadUrl(target)
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

    fun newDisplayOwner(): Long = displayOwnership.newOwner()

    fun webViewForDisplay(displayContext: Context, owner: Long): WebView {
        mainThread()
        var activityContext = displayContext
        while (activityContext is ContextWrapper && activityContext !is Activity && activityContext.baseContext !== activityContext) {
            activityContext = activityContext.baseContext
        }
        check(activityContext is Activity) { "浏览器需要当前页面的显示环境" }
        val view = webView ?: createWebView(displayContext)
        displayOwnership.claim(owner)
        webViewContext?.baseContext = displayContext
        (view.parent as? ViewGroup)?.removeView(view)
        view.post {
            if (view === webView && displayOwnership.owns(owner)) {
                view.onResume()
                val target = pendingNavigation
                pendingNavigation = null
                if (target != null) view.loadUrl(target)
                else if (isBrowserUrl(view.url)) diagnoseRendering(view, documentVersion)
            }
        }
        return view
    }

    fun detachDisplay(owner: Long) {
        mainThread()
        if (!displayOwnership.release(owner)) return
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        // The session can keep running in chat without retaining the Activity/window.
        webViewContext?.baseContext = context
        renderJob?.cancel()
    }

    fun releaseBrowser() {
        mainThread()
        imageChatBridge.invalidate()
        permission.setEnabled(false)
        activeAiJob?.cancel(BrowserOperationStopped())
        pendingDownloads.toList().forEach { it.cancel() }
        imageDownloadJob?.cancel()
        imageDownloadGrant.clear()
        downloadAllowed = false
        val old = webView
        webView = null
        displayOwnership.clear()
        webViewContext?.baseContext = context
        webViewContext = null
        pendingNavigation = null
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
        pendingNavigation = null
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
        imageCacheKey?.let { imageScript(view, BrowserImageScripts.arm(it), documentVersion, permit) }
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
        if (isChatGptImagePage(view.url)) {
            prepareAiDownload(permit)
            check(imageDownloadGrant.consume(SystemClock.elapsedRealtime(), documentVersion)) { "请先点击网页保存按钮" }
            val saved = saveChatGptImage(view, url, permit, documentVersion)
            return@aiAction buildJsonObject {
                put("saved", true)
                put("uri", saved.uri.toString())
                put("message", saved.message)
            }
        }
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
        pendingNavigation = null
        permission.invalidate()
        activeAiJob?.cancel(BrowserOperationStopped())
        imageDownloadJob?.cancel()
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
        imageChatBridge.cancelPending()
        renderJob?.cancel()
        visualVersion = -1
        renderedVersion = -1
        resourceFailures = 0
        consoleFailures = 0
        mainFrameFailed = false
        imageCacheKey?.let { key -> webView?.let { view -> runCatching { view.evaluateJavascript(BrowserImageScripts.uninstall(key), null) } } }
        imageCacheKey = null
        if (imageDownloadJob != null) _state.value = state.value.copy(message = "页面或操作已改变，图片保存已停止，未保存")
        imageDownloadJob?.cancel()
        imageDownloadGrant.clear()
        documentVersion++
        snapshotVersion = -1
        elementIds = emptySet()
    }

    private fun prepareAiDownload(permit: Long) {
        downloadPermit = permit
        downloadAllowed = true
        imageDownloadGrant.allow(SystemClock.elapsedRealtime(), documentVersion)
    }

    private fun installImageCapture(view: WebView) {
        if (view !== webView || !isChatGptImagePage(view.url)) return
        val key = imageCacheKey ?: ("__miku_blob_" + UUID.randomUUID().toString().replace("-", "")).also { imageCacheKey = it }
        runCatching { view.evaluateJavascript(BrowserImageScripts.install(key), null) }
    }

    /** Counts only: never reads page text, form values, URLs, console text, or cookies. */
    private fun diagnoseRendering(view: WebView, version: Long) {
        renderJob?.cancel()
        renderJob = scope.launch {
            try {
                repeat(5) { attempt ->
                    if (view !== webView || version != documentVersion) return@launch
                    if (view.isAttachedToWindow && view.width > 0 && view.height > 0) {
                        view.postVisualStateCallback(version, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (view === webView && version == documentVersion) {
                                    visualVersion = version
                                    view.invalidate()
                                }
                            }
                        })
                    }
                    val raw = withTimeout(3_000) {
                        suspendCancellableCoroutine { continuation ->
                            view.evaluateJavascript(BrowserScripts.renderCounts) { if (continuation.isActive) continuation.resume(it) }
                        }
                    }
                    if (view !== webView || version != documentVersion) return@launch
                    val counts = Json.parseToJsonElement(decodeBrowserJavascriptResult(raw)).jsonObject
                    val elements = counts["elements"]?.jsonPrimitive?.intOrNull ?: 0
                    val visible = counts["visible"]?.jsonPrimitive?.intOrNull ?: 0
                    val attached = view.isAttachedToWindow && view.isShown
                    val rendered = !mainFrameFailed && browserPageRendered(attached, visualVersion == version, visible)
                    _state.value = state.value.copy(
                        progress = if (rendered) 100 else 95,
                        renderNotice = if (rendered || !displayOwnership.hasOwner) null else if (attempt < 4) "正在检查网页是否已显示…" else "网页内容未能显示，请重新加载；若仍为空白，请尝试系统浏览器",
                        renderDiagnostics = "宿主${if (attached) "已挂载" else "未挂载"} · ${view.width}×${view.height} · DOM $elements · 可见 $visible · 绘制${if (visualVersion == version) "就绪" else "等待"} · 资源失败 $resourceFailures · 脚本错误 $consoleFailures",
                    )
                    Log.i("MikuHubBrowserRender", state.value.renderDiagnostics.orEmpty())
                    if (rendered) { renderedVersion = version; return@launch }
                    delay(1_500)
                }
            } catch (_: TimeoutCancellationException) {
                if (view === webView && version == documentVersion) {
                    _state.value = state.value.copy(progress = 95, renderNotice = "网页显示检查超时，请重新加载或尝试系统浏览器", renderDiagnostics = "网页计数探测超时 · 资源失败 $resourceFailures · 脚本错误 $consoleFailures")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (view === webView && version == documentVersion) {
                    _state.value = state.value.copy(progress = 95, renderNotice = "无法确认网页已显示，请重新加载或尝试系统浏览器", renderDiagnostics = "网页计数探测未完成 · 资源失败 $resourceFailures · 脚本错误 $consoleFailures")
                }
            }
        }
    }

    private fun verifyImageDownload(view: WebView, version: Long, permit: Long?) {
        check(view === webView && version == documentVersion && isChatGptImagePage(view.url)) { "页面已跳转，图片保存已取消" }
        permit?.let(permission::verify)
    }

    private suspend fun imageScript(view: WebView, script: String, version: Long, permit: Long?): JsonObject {
        verifyImageDownload(view, version, permit)
        val raw = withTimeout(10_000) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
            }
        }
        verifyImageDownload(view, version, permit)
        return Json.parseToJsonElement(decodeBrowserJavascriptResult(raw)).jsonObject
    }

    private suspend fun saveChatGptImage(view: WebView, source: String, permit: Long?, version: Long): SavedBrowserImage {
        require(isChatGptImageSource(view.url, source)) { "只支持当前 ChatGPT 网页的官方图片或 Blob 图片，未保存" }
        check(imageDownloadJob == null || imageDownloadJob === currentCoroutineContext()[Job]) { "已有图片正在保存，请稍后再点保存" }
        val job = currentCoroutineContext()[Job]
        imageDownloadJob = job
        val key = "__miku_image_" + UUID.randomUUID().toString().replace("-", "")
        val chatTarget = imageChatBridge.captureTarget()
        var pending: PendingBrowserImage? = null
        var savedImage: SavedBrowserImage? = null
        var stage = BrowserImageStage.START
        var scriptFailure: String? = null
        var transport = if (source.startsWith("blob:")) "blob" else "https"
        fun advance(value: BrowserImageStage) {
            stage = value
            Log.i("MikuHubBrowserImage", "stage=${value.name}")
        }
        try {
            advance(BrowserImageStage.START)
            _state.value = state.value.copy(error = null, message = "正在读取网页图片并保存，请保持当前页面")
            val chunks = BrowserImageChunks()
            withTimeout(50_000) {
                advance(BrowserImageStage.FETCH)
                val start = imageScript(view, BrowserImageScripts.begin(key, source, imageCacheKey), version, permit)
                if (start["status"]?.jsonPrimitive?.content != "pending") {
                    scriptFailure = browserImageScriptFailure(start["code"]?.jsonPrimitive?.content)
                }
                check(start["status"]?.jsonPrimitive?.content == "pending") { "网页未允许图片读取，未保存" }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val result = imageScript(view, BrowserImageScripts.poll(key), version, permit)
                    when (result["status"]?.jsonPrimitive?.content) {
                        "pending" -> delay(50)
                        "chunk" -> {
                            if (stage != BrowserImageStage.CHUNKS) advance(BrowserImageStage.CHUNKS)
                            chunks.append(
                                result.getValue("offset").jsonPrimitive.int,
                                result.getValue("total").jsonPrimitive.int,
                                result.getValue("mime").jsonPrimitive.content,
                                result.getValue("data").jsonPrimitive.content,
                            )
                        }
                        "complete" -> break
                        else -> {
                            scriptFailure = browserImageScriptFailure(result["code"]?.jsonPrimitive?.content)
                            transport = browserImageTransport(result["transport"]?.jsonPrimitive?.content)
                            error("网页图片下载失败或不是受支持的图片，未保存；请在官方网页重新点保存")
                        }
                    }
                }
            }
            advance(BrowserImageStage.SIGNATURE)
            val bytes = chunks.finish()
            pending = imageSaver.prepare(bytes, chunks.mime, ::advance)
            currentCoroutineContext().ensureActive()
            verifyImageDownload(view, version, permit)
            // Main-thread publication and feedback are atomic with respect to navigation/revocation.
            advance(BrowserImageStage.PUBLISH)
            val saved = pending.commit { verifyImageDownload(view, version, permit) }
            savedImage = saved
            advance(BrowserImageStage.COMPLETE)
            _state.value = state.value.copy(error = null, message = saved.message)
            verifyImageDownload(view, version, permit)
            val receipt = imageChatBridge.deliver(chatTarget, saved.uri)
            return saved.copy(message = listOf(saved.message, receipt.message).filter(String::isNotBlank).joinToString("\n"))
                .also { if (view === webView && version == documentVersion) _state.value = state.value.copy(error = null, message = it.message) }
        } catch (cancelled: CancellationException) {
            Log.i("MikuHubBrowserImage", "stage=${stage.name} code=${browserImageNativeFailure(cancelled)} transport=$transport")
            if (view === webView && version == documentVersion) {
                _state.value = if (savedImage != null) {
                    state.value.copy(message = "${savedImage.message}\n回传聊天已停止", error = null)
                } else {
                    state.value.copy(message = null, error = "图片保存已停止或超时，未保存")
                }
            }
            throw cancelled
        } catch (error: Exception) {
            Log.i("MikuHubBrowserImage", "stage=${stage.name} code=${scriptFailure ?: browserImageNativeFailure(error)} transport=$transport")
            savedImage?.let { saved ->
                val result = saved.copy(message = "${saved.message}\n图片未能回传聊天，已保留相册文件")
                if (view === webView && version == documentVersion) _state.value = state.value.copy(message = result.message, error = null)
                return result
            }
            if (view === webView && version == documentVersion) {
                _state.value = state.value.copy(message = null, error = "图片保存失败，未保存；请检查网页登录、图片格式及存储空间后重新点保存")
            }
            // Browser/HTTP exception text can contain a signed image URL. Do not expose it to AI or UI.
            error("图片保存失败，未保存；请在官方网页重试")
        } finally {
            pending?.discard()
            if (view === webView) runCatching { view.evaluateJavascript(BrowserImageScripts.cancel(key), null) }
            if (imageDownloadJob === job) imageDownloadJob = null
        }
    }

    private fun startChatGptImageDownload(view: WebView, source: String) {
        if (!isChatGptImageSource(view.url, source)) {
            _state.value = state.value.copy(error = "只支持当前 ChatGPT 网页的官方图片或 Blob 图片，未保存")
            return
        }
        if (imageDownloadJob != null) {
            _state.value = state.value.copy(error = "已有图片正在保存，请稍后再点保存")
            return
        }
        if (!imageDownloadGrant.consume(SystemClock.elapsedRealtime(), documentVersion)) {
            _state.value = state.value.copy(error = "请在当前网页点击保存图片；图片下载需要近期手动点击或已授权的 AI 操作")
            return
        }
        val version = documentVersion
        val permit = downloadPermit
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { saveChatGptImage(view, source, permit, version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (view === webView && version == documentVersion) {
                    _state.value = state.value.copy(message = null, error = "图片保存失败，未保存；请在官方网页重试")
                }
            }
        }
        pendingDownloads += job
        job.invokeOnCompletion { scope.launch { pendingDownloads -= job } }
        job.start()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun ensureWebView(): WebView {
        mainThread()
        return webView ?: error("请先打开内置浏览器页面，再开启 AI 操作")
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWebView(displayContext: Context): WebView {
        val wrapper = MutableContextWrapper(displayContext)
        webViewContext = wrapper
        return WebView(wrapper).also { view ->
            webView = view
            view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    permission.invalidate()
                    activeAiJob?.cancel(BrowserOperationStopped())
                    imageDownloadJob?.cancel()
                    downloadPermit = null
                    downloadAllowed = true
                    imageDownloadGrant.allow(SystemClock.elapsedRealtime(), documentVersion)
                }
                false
            }
            view.webChromeClient = object : WebChromeClient() {
                // Detached sessions have no Activity window; keep dialogs inside the webpage.
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
                    _state.value = state.value.copy(progress = if (renderedVersion == documentVersion) newProgress else newProgress.coerceAtMost(95))
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (view === webView && consoleMessage.messageLevel() in setOf(ConsoleMessage.MessageLevel.ERROR, ConsoleMessage.MessageLevel.WARNING)) consoleFailures++
                    return true // Consume it without logging potentially private console messages.
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (view !== webView) return
                    _state.value = state.value.copy(title = title?.takeIf { it.isNotBlank() } ?: "内置浏览器")
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (view === webView && request.isForMainFrame && request.url.scheme == "blob" && isChatGptImagePage(view.url)) {
                        startChatGptImageDownload(view, request.url.toString())
                        return true
                    }
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
                    _state.value = state.value.copy(url = url.orEmpty(), loading = true, progress = 0, error = null, renderNotice = null, renderDiagnostics = null)
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    if (view !== webView) return
                    installImageCapture(view)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (view !== webView) return
                    _state.value = state.value.copy(
                        url = view.url?.takeIf(::isBrowserUrl) ?: state.value.url,
                        loading = false, progress = 95, canGoBack = view.canGoBack(), canGoForward = view.canGoForward(),
                    )
                    installImageCapture(view)
                    diagnoseRendering(view, documentVersion)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (view !== webView) return
                    resourceFailures++
                    if (request.isForMainFrame) {
                        mainFrameFailed = true
                        _state.value = state.value.copy(loading = false, error = "网页加载失败，请检查网络后重新加载")
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (view !== webView) return
                    resourceFailures++
                    if (request.isForMainFrame) {
                        mainFrameFailed = true
                        _state.value = state.value.copy(loading = false, error = "网页服务器返回错误（HTTP ${response.statusCode}），请稍后重新加载")
                    }
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
                if (isChatGptImagePage(view.url)) {
                    startChatGptImageDownload(view, url)
                    return@setDownloadListener
                }
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
