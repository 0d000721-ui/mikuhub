package me.rerere.rikkahub.data.ai.chatgpt

import android.content.Context
import java.util.concurrent.TimeUnit
import java.net.BindException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.oauth.ChatGptOAuthClient
import me.rerere.oauth.ChatGptOAuthException
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes

/** Official ChatGPT sign-in; credentials only leave the encrypted store for a single request. */
class ChatGptAccountManager(
    context: Context,
    private val appScope: AppScope,
    authHttpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val store = ChatGptEncryptedAccountStore(context.applicationContext)
    private val oauth = ChatGptOAuthClient(authHttpClient)
    private val genericOAuth = OAuthHttpClient(authHttpClient)
    private val callbacks = OAuthLoopbackCallbackServer(port = CALLBACK_PORT, callbackPath = CALLBACK_PATH)
    // State mutations and refresh-token rotation must be serialized with sign-out.
    private val accountMutex = Mutex()
    private var state: ChatGptAccountState? = null
    private val requestSessions = ChatGptRequestSessions()
    private var storageFailure = false
    private val authorizationMonitor = Any()
    private var authorizationJob: Job? = null
    private var authorizationGeneration = 0L
    private var authorizingAccountId: String? = null

    private val _accounts = MutableStateFlow<List<ChatGptAccountSummary>>(emptyList())
    val accounts: StateFlow<List<ChatGptAccountSummary>> = _accounts.asStateFlow()
    private val _authStatus = MutableStateFlow<ChatGptAuthStatus>(ChatGptAuthStatus.Idle)
    val authStatus: StateFlow<ChatGptAuthStatus> = _authStatus.asStateFlow()

    init {
        appScope.launch(Dispatchers.IO) {
            try { accountMutex.withLock { loadLocked() } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { _authStatus.value = ChatGptAuthStatus.Error(STORAGE_ERROR) }
        }
    }

    fun startSignIn(context: Context, accountId: String? = null, onSuccess: (String) -> Unit) {
        val browserContext = context.applicationContext
        synchronized(authorizationMonitor) {
            authorizationJob?.cancel()
            val generation = ++authorizationGeneration
            authorizingAccountId = accountId
            _authStatus.value = ChatGptAuthStatus.Authorizing(accountId)
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val resultId = withContext(Dispatchers.IO) { authorize(browserContext, accountId, generation) }
                    if (isCurrentAuthorization(generation)) {
                        val granted = _accounts.value.firstOrNull { it.id == resultId }?.planEnabled == true
                        _authStatus.value = if (granted) ChatGptAuthStatus.Idle else ChatGptAuthStatus.Error(
                            "ChatGPT 已登录，但账号未授予套餐模型权限；请重新授权或检查套餐资格"
                        )
                        if (granted) onSuccess(resultId)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (isCurrentAuthorization(generation)) {
                        _authStatus.value = ChatGptAuthStatus.Error(
                            when {
                                storageFailure -> STORAGE_ERROR
                                error is ChatGptOAuthException || error is ChatGptAccountException ->
                                    error.message ?: "ChatGPT 登录未完成，请重试"
                                error is BindException -> "本机授权端口 1455 已被占用，请关闭其他授权页面后重试"
                                else -> "ChatGPT 登录未完成。请检查网络、浏览器授权及账号选择后重试；原账号已保留"
                            }
                        )
                    }
                }
            }
            authorizationJob = job
            job.invokeOnCompletion {
                synchronized(authorizationMonitor) {
                    if (authorizationJob === job) {
                        authorizationJob = null
                        authorizingAccountId = null
                    }
                }
            }
            job.start()
        }
    }

    fun cancelSignIn() {
        synchronized(authorizationMonitor) {
            ++authorizationGeneration
            authorizationJob?.cancel()
            authorizationJob = null
            authorizingAccountId = null
            _authStatus.value = ChatGptAuthStatus.Idle
        }
    }

    /** Revoke remotely when possible, always clear this device's credentials while retaining registration identity. */
    suspend fun signOut(accountId: String) {
        // Cancel HTTP/stream watchers before waiting for refresh, storage, or remote revocation.
        requestSessions.revoke(accountId)
        withContext(Dispatchers.IO) {
            val pending = synchronized(authorizationMonitor) {
                if (authorizingAccountId == accountId) {
                    val job = authorizationJob
                    cancelSignIn()
                    job
                } else null
            }
            pending?.join()
            accountMutex.withLock {
                val current = loadLocked()
                val credentials = current.records[accountId]?.credentials ?: return@withLock
                var revokeFailed = false
                // Local sign-out must survive cancellation while the network request is in flight.
                withContext(NonCancellable) {
                    try { oauth.revoke(credentials) }
                    catch (_: Exception) { revokeFailed = true }
                    persistLocked(current.clearCredentials(accountId))
                }
                if (revokeFailed) {
                    val message = "已退出此手机的 ChatGPT 账号，但远程撤销失败。可在 ChatGPT 账号中撤销 MikuHub 授权"
                    _authStatus.value = ChatGptAuthStatus.Error(message)
                    throw IllegalStateException(message)
                }
            }
        }
    }

    /** Call for every model request, including streaming and model lookup. Never persist the returned copy. */
    suspend fun resolveProvider(setting: ProviderSetting.OpenAI): ProviderSetting.OpenAI {
        val accountId = setting.chatGptAccountId ?: return setting
        require(accountId.isNotBlank()) { "请先在 Codex 服务商中连接并选择 ChatGPT 账号" }
        return withContext(Dispatchers.IO) {
            accountMutex.withLock {
                var current = loadLocked()
                var credentials = current.records[accountId]?.credentials
                    ?: error("ChatGPT 账号已退出，请重新连接")
                check(chatGptPlanEnabled(credentials.scopes)) {
                    "此账号未授予 ChatGPT 套餐模型权限，请重新授权或检查套餐资格"
                }
                val requestJob = requestSessions.current(accountId)
                check(requestJob.isActive) { "ChatGPT 账号已退出，请重新连接" }
                if (credentials.accessToken.isBlank() || chatGptNeedsRefresh(credentials.expiresAt, System.currentTimeMillis())) {
                    check(!credentials.refreshToken.isNullOrBlank()) { "ChatGPT 登录已过期，请重新连接" }
                    // Save rotated tokens even if the caller stops its model request mid-refresh.
                    val refreshed = withContext(NonCancellable) {
                        val result = try { oauth.refresh(credentials) }
                        catch (error: ChatGptOAuthException) { throw error }
                        catch (_: Exception) { error("ChatGPT 登录刷新失败，请重新连接账号") }
                        current = current.acceptCredentials(accountId, result)
                        persistLocked(current)
                        result
                    }
                    coroutineContext.ensureActive()
                    credentials = refreshed
                    check(chatGptPlanEnabled(credentials.scopes)) {
                        "ChatGPT 套餐权限已撤销，请重新授权或检查套餐资格"
                    }
                }
                check(requestJob.isActive) { "ChatGPT 账号已退出，请重新连接" }
                setting.copy(
                    apiKey = credentials.accessToken,
                    baseUrl = ChatGptOAuthClient.RESOURCE,
                    useResponseApi = true,
                    responsesPath = "/responses",
                    chatGptRequestJob = requestJob,
                )
            }
        }
    }

    private suspend fun authorize(context: Context, selectedAccountId: String?, generation: Long): String {
        val (initial, initialEpochs) = accountMutex.withLock {
            val loaded = loadLocked().also { current ->
                require(selectedAccountId == null || selectedAccountId in current.records) {
                    "所选 ChatGPT 账号不存在"
                }
            }
            loaded to requestSessions.snapshotEpochs()
        }
        val selected = selectedAccountId?.let(initial.records::get)
        val pkce = genericOAuth.generatePkce()
        val callbackState = genericOAuth.generateState()
        val nonce = genericOAuth.generateState()
        val session = callbacks.openSession(context, callbackState)
        try {
            check(session.redirectUri == REDIRECT_URI) { "ChatGPT 本机授权回调地址不一致" }
            val url = oauth.authorizationUrl(
                hostId = initial.hostId,
                redirectUri = session.redirectUri,
                state = callbackState,
                nonce = nonce,
                pkce = pkce,
                clientId = selected?.clientId,
                idTokenHint = selected?.credentials?.idToken,
                loginHint = selected?.email,
            )
            withContext(Dispatchers.Main) { CustomTabsOAuthAuthorizationLauncher.launch(context, url) }
            val callback = session.awaitCallback(5.minutes)
                ?: throw ChatGptAccountException("ChatGPT 登录超时，请重新打开授权页面")
            if (callback.error != null) throw ChatGptAccountException("ChatGPT 登录已取消或未获得批准，请重新授权")
            val code = callback.code?.takeIf(String::isNotBlank)
                ?: throw ChatGptAccountException("ChatGPT 未返回授权码，请重新授权")
            val clientId = oauth.resolveClientId(selected?.clientId, callback.clientId)
            // Preserve the server-issued registration even if code exchange fails (invalid_grant recovery).
            accountMutex.withLock {
                ensureCurrentAuthorization(generation)
                requestSessions.requireEpoch(clientId, initialEpochs[clientId] ?: 0)
                persistLocked(loadLocked().registerClient(selectedAccountId, clientId))
                synchronized(authorizationMonitor) {
                    if (authorizationGeneration == generation) authorizingAccountId = clientId
                }
                _authStatus.value = ChatGptAuthStatus.Authorizing(clientId)
            }
            val credentials = oauth.exchangeCode(code, clientId, pkce.verifier, session.redirectUri, nonce)
            accountMutex.withLock {
                ensureCurrentAuthorization(generation)
                requestSessions.requireEpoch(clientId, initialEpochs[clientId] ?: 0)
                // acceptCredentials prevents a different browser account from replacing the selected account.
                persistLocked(loadLocked().acceptCredentials(clientId, credentials))
                requestSessions.authorized(clientId, initialEpochs[clientId] ?: 0)
            }
            return clientId
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    /** Must be called on IO while accountMutex is held; a damaged store is never silently replaced. */
    private fun loadLocked(): ChatGptAccountState {
        state?.let { return it }
        check(!storageFailure) { STORAGE_ERROR }
        try {
            val loaded = store.read()
            store.write(loaded) // Persist the stable host ID before any browser authorization.
            state = loaded
            _accounts.value = loaded.records.values.map(ChatGptAccountRecord::summary)
            return loaded
        } catch (_: Exception) {
            storageFailure = true
            // Serialization exceptions can include JSON fragments; never propagate them to logs/UI.
            throw IllegalStateException(STORAGE_ERROR)
        }
    }

    private fun persistLocked(updated: ChatGptAccountState) {
        try { store.write(updated) }
        catch (_: Exception) { throw IllegalStateException(STORAGE_ERROR) }
        state = updated
        _accounts.value = updated.records.values.map(ChatGptAccountRecord::summary)
    }

    private fun isCurrentAuthorization(generation: Long): Boolean =
        synchronized(authorizationMonitor) { authorizationGeneration == generation }

    private suspend fun ensureCurrentAuthorization(generation: Long) {
        coroutineContext.ensureActive()
        check(isCurrentAuthorization(generation)) { "ChatGPT 登录已取消" }
    }

    private companion object {
        const val CALLBACK_PORT = 1455
        const val CALLBACK_PATH = "/auth/callback"
        const val REDIRECT_URI = "http://127.0.0.1:$CALLBACK_PORT$CALLBACK_PATH"
        const val STORAGE_ERROR = "无法读取或保存此手机的加密 ChatGPT 账号，请保留应用数据并重试"
    }
}
