package me.rerere.oauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-displayable errors assembled locally; no server body, URL, code, or token. */
class ChatGptOAuthException internal constructor(message: String) : IOException(message)

/** A verified account registration. Persist only in protected local storage. */
@Serializable
data class ChatGptCredentials(
    val clientId: String,
    val subject: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String,
    val expiresAt: Long,
    val scopes: List<String>,
) {
    override fun toString(): String = "ChatGptCredentials(<redacted>)"
}

/**
 * Official public-client Sign in with ChatGPT flow for an open-source agent.
 *
 * Pass a dedicated HTTP client without logging interceptors. Refresh calls for one
 * registration must be serialized by the credential owner to protect token rotation.
 * Neither token endpoint errors nor parser exceptions expose server response bodies.
 */
class ChatGptOAuthClient(httpClient: OkHttpClient) {
    private val httpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val oauth = OAuthHttpClient(this.httpClient)
    private val json = Json { ignoreUnknownKeys = true }

    fun authorizationUrl(
        hostId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        pkce: OAuthHttpClient.Pkce,
        clientId: String? = null,
        idTokenHint: String? = null,
        loginHint: String? = null,
    ): String {
        requireHostId(hostId)
        requireLoopbackRedirect(redirectUri)
        require(state.isNotBlank() && nonce.isNotBlank() && pkce.challenge.isNotBlank()) {
            "ChatGPT 授权缺少 state、nonce 或 PKCE。"
        }
        val selectedId = clientId?.takeIf { it.isNotBlank() && it != DYNAMIC_CLIENT_ID }
        return oauth.buildAuthorizationUrl(
            OAuthHttpClient.AuthorizationRequest(
                authorizationEndpoint = AUTHORIZE_ENDPOINT,
                clientId = selectedId ?: DYNAMIC_CLIENT_ID,
                redirectUri = redirectUri,
                pkce = pkce,
                state = state,
                scope = SCOPES.joinToString(" "),
                resources = listOf(RESOURCE),
                additionalParameters = buildMap {
                    put("ext_agent_host_id", hostId)
                    put("nonce", nonce)
                    if (selectedId == null) {
                        put("agent_name_hint", "MikuHub")
                    } else {
                        idTokenHint?.takeIf(String::isNotBlank)?.let { put("id_token_hint", it) }
                        loginHint?.takeIf(String::isNotBlank)?.let { put("login_hint", it) }
                    }
                },
            )
        )
    }

    /** Never replace an existing registration with a callback-selected client ID. */
    fun resolveClientId(selectedClientId: String?, returnedClientId: String?): String {
        val selected = selectedClientId?.takeIf { it.isNotBlank() && it != DYNAMIC_CLIENT_ID }
        val returned = returnedClientId?.takeIf(String::isNotBlank)
        if (selected == null) {
            if (returned == null || returned == DYNAMIC_CLIENT_ID) {
                throw ChatGptOAuthException("ChatGPT 注册未返回有效客户端 ID，请重新登录。")
            }
            return returned
        }
        if (returned != null && returned != selected) {
            throw ChatGptOAuthException("ChatGPT 回调与所选账号的客户端 ID 不一致。")
        }
        return selected
    }

    suspend fun exchangeCode(
        code: String,
        clientId: String,
        verifier: String,
        redirectUri: String,
        nonce: String,
    ): ChatGptCredentials = withContext(Dispatchers.IO) {
        requireIssuedClientId(clientId)
        requireLoopbackRedirect(redirectUri)
        require(code.isNotBlank() && verifier.isNotBlank() && nonce.isNotBlank()) {
            "ChatGPT 授权码、PKCE 或 nonce 缺失。"
        }
        val response = postToken(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", redirectUri)
                .add("resource", RESOURCE)
                .build()
        )
        val idToken = response.idToken?.takeIf(String::isNotBlank)
            ?: throw ChatGptOAuthException("ChatGPT 未返回 ID 令牌，无法验证账号。")
        val identity = validateIdentity(idToken, clientId, nonce)
        ChatGptCredentials(
            clientId = clientId,
            subject = identity.subject,
            email = identity.email,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken?.takeIf(String::isNotBlank),
            idToken = idToken,
            expiresAt = response.expiryMillis(),
            scopes = response.scope.grantedScopes(),
        )
    }

    suspend fun refresh(credentials: ChatGptCredentials): ChatGptCredentials = withContext(Dispatchers.IO) {
        requireIssuedClientId(credentials.clientId)
        val refreshToken = credentials.refreshToken?.takeIf(String::isNotBlank)
            ?: throw ChatGptOAuthException("ChatGPT 会话无法续期，请重新登录。")
        val response = postToken(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", credentials.clientId)
                .add("refresh_token", refreshToken)
                .add("resource", RESOURCE)
                // Omit scope to retain the current grant, as required by the public flow.
                .build()
        )
        val replacementIdToken = response.idToken?.takeIf(String::isNotBlank)
        val identity = replacementIdToken?.let {
            validateIdentity(it, credentials.clientId, expectedNonce = null).also { verified ->
                if (verified.subject != credentials.subject) {
                    throw ChatGptOAuthException("ChatGPT 续期返回的账号身份不一致，请重新登录。")
                }
            }
        }
        credentials.copy(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken?.takeIf(String::isNotBlank) ?: refreshToken,
            idToken = replacementIdToken ?: credentials.idToken,
            email = identity?.email?.takeIf(String::isNotBlank) ?: credentials.email,
            expiresAt = response.expiryMillis(),
            scopes = response.scope?.grantedScopes() ?: credentials.scopes,
        )
    }

    /** Revoke the renewable session before its owner clears local tokens. */
    suspend fun revoke(credentials: ChatGptCredentials): Unit = withContext(Dispatchers.IO) {
        requireIssuedClientId(credentials.clientId)
        val refreshToken = credentials.refreshToken?.takeIf(String::isNotBlank) ?: return@withContext
        val metadata = safeParse("ChatGPT 授权配置无效。") {
            json.parseToJsonElement(get(DISCOVERY_ENDPOINT)) as JsonObject
        }
        val endpoint = metadata.string("revocation_endpoint")?.toHttpUrlOrNull()
            ?: throw ChatGptOAuthException("ChatGPT 未提供撤销端点。")
        if (endpoint.scheme != "https" || endpoint.host != "auth.openai.com" ||
            endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty() || endpoint.port != 443
        ) {
            throw ChatGptOAuthException("ChatGPT 撤销端点无效。")
        }
        execute(
            Request.Builder().url(endpoint)
                .post(
                    FormBody.Builder()
                        .add("token", refreshToken)
                        .add("token_type_hint", "refresh_token")
                        .add("client_id", credentials.clientId)
                        .build()
                ).build()
        )
    }

    private suspend fun postToken(form: FormBody): OAuthHttpClient.TokenResponse {
        val text = execute(
            Request.Builder().url(TOKEN_ENDPOINT)
                .header("Accept", "application/json")
                .post(form).build()
        )
        return safeParse("ChatGPT 令牌响应无效，请重新登录。") {
            json.decodeFromString(OAuthHttpClient.TokenResponse.serializer(), text).also {
                if (it.accessToken.isBlank() || !it.tokenType.equals("Bearer", ignoreCase = true)) {
                    throw ChatGptOAuthException("ChatGPT 令牌响应无效，请重新登录。")
                }
            }
        }
    }

    private suspend fun validateIdentity(idToken: String, clientId: String, expectedNonce: String?): Identity {
        val jwks = get(JWKS_ENDPOINT)
        return safeParse("ChatGPT ID 令牌验证失败，请重新登录。") {
            val parts = idToken.split('.')
            check(parts.size == 3 && parts.all { it.matches(BASE64URL) })
            val decoder = Base64.getUrlDecoder()
            val header = json.parseToJsonElement(decoder.decode(parts[0]).toString(Charsets.UTF_8)) as JsonObject
            check(header.string("alg") == "RS256")
            check(header["crit"] == null) // No critical extensions are implemented.
            val kid = header.string("kid")?.takeIf(String::isNotBlank) ?: error("Missing key")
            val keys = (json.parseToJsonElement(jwks) as JsonObject)["keys"] as JsonArray
            val key = keys.mapNotNull { it as? JsonObject }.single { it.string("kid") == kid }
            check(key.string("kty") == "RSA")
            check(key.string("use") == null || key.string("use") == "sig")
            check(key.string("alg") == null || key.string("alg") == "RS256")
            val modulus = BigInteger(1, decoder.decode(key.string("n") ?: error("Missing key")))
            val exponent = BigInteger(1, decoder.decode(key.string("e") ?: error("Missing key")))
            check(modulus.bitLength() >= 2048 && exponent >= BigInteger.valueOf(3) && exponent.testBit(0))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            val validSignature = Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(decoder.decode(parts[2]))
            }
            check(validSignature)
            val claims = json.parseToJsonElement(decoder.decode(parts[1]).toString(Charsets.UTF_8)) as JsonObject
            check(claims.string("iss") == ISSUER)
            val audience = claims["aud"]
            val audiences = when (audience) {
                is JsonPrimitive -> listOfNotNull(audience.takeIf { it.isString }?.contentOrNull)
                is JsonArray -> audience.map { (it as JsonPrimitive).takeIf { value -> value.isString }?.contentOrNull ?: error("Invalid audience") }
                else -> emptyList()
            }
            check(clientId in audiences)
            val authorizedParty = claims.string("azp")
            check(authorizedParty == null || authorizedParty == clientId)
            check(audiences.size <= 1 || authorizedParty == clientId)
            val expiry = (claims["exp"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?: error("Missing expiry")
            check(expiry > System.currentTimeMillis() / 1000)
            if (expectedNonce != null) check(claims.string("nonce") == expectedNonce)
            val subject = claims.string("sub")?.takeIf(String::isNotBlank) ?: error("Missing identity")
            Identity(subject = subject, email = claims.string("email").orEmpty())
        }
    }

    private suspend fun get(url: String): String = execute(
        Request.Builder().url(url).header("Accept", "application/json").build()
    )

    private suspend fun execute(request: Request): String {
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(ChatGptOAuthException("无法连接 ChatGPT 授权服务，请检查网络后重试。"))
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }
        return response.use {
            if (!it.isSuccessful) throw ChatGptOAuthException("ChatGPT 请求失败（HTTP ${it.code}）。")
            try {
                it.body.string()
            } catch (_: IOException) {
                throw ChatGptOAuthException("ChatGPT 响应读取失败，请重试。")
            }
        }
    }

    private fun OAuthHttpClient.TokenResponse.expiryMillis(): Long = safeParse("ChatGPT 令牌有效期无效。") {
        val seconds = expiresIn?.takeIf { it > 0 } ?: error("Missing expiry")
        Math.addExact(System.currentTimeMillis(), Math.multiplyExact(seconds, 1000L))
    }

    private fun String?.grantedScopes(): List<String> = this?.trim()
        ?.split(Regex("\\s+"))?.filter(String::isNotBlank)?.distinct().orEmpty()

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private inline fun <T> safeParse(message: String, block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw ChatGptOAuthException(message)
    }

    private fun requireIssuedClientId(clientId: String) {
        require(clientId.isNotBlank() && clientId != DYNAMIC_CLIENT_ID) { "ChatGPT 客户端 ID 无效。" }
    }

    private fun requireHostId(hostId: String) {
        val value = hostId.removePrefix("urn:uuid:")
        require(hostId.startsWith("urn:uuid:") && runCatching {
            UUID.fromString(value).toString().equals(value, ignoreCase = true)
        }.getOrDefault(false)) { "ChatGPT 主机标识无效。" }
    }

    private fun requireLoopbackRedirect(redirectUri: String) {
        val url = runCatching { redirectUri.toHttpUrl() }.getOrNull()
        require(url != null && url.scheme == "http" && url.host == "127.0.0.1" &&
            url.encodedPath == "/auth/callback" && url.query == null && url.fragment == null &&
            url.username.isEmpty() && url.password.isEmpty()
        ) { "ChatGPT 授权必须使用设备本地 /auth/callback 回调。" }
    }

    private data class Identity(val subject: String, val email: String)

    companion object {
        const val RESOURCE = "https://api.openai.com/v1"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        val SCOPES: List<String> = listOf("openid", "profile", "email", "offline_access", "resource.invoke", PLAN_SCOPE)
        private const val ISSUER = "https://auth.openai.com"
        private const val AUTHORIZE_ENDPOINT = "$ISSUER/api/accounts/authorize"
        private const val TOKEN_ENDPOINT = "$ISSUER/api/accounts/oauth/token"
        private const val JWKS_ENDPOINT = "$ISSUER/.well-known/jwks.json"
        private const val DISCOVERY_ENDPOINT = "$ISSUER/.well-known/openid-configuration"
        private const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        private val BASE64URL = Regex("[A-Za-z0-9_-]+")
    }
}
