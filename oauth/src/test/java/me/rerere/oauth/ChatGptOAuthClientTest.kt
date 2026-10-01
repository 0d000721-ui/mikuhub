package me.rerere.oauth

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGptOAuthClientTest {
    private val pkce = OAuthHttpClient.Pkce("verifier-fixture", "challenge-fixture")
    private val hostId = "urn:uuid:340948a9-2c88-4f88-8f4c-dba4be6c3782"

    @Test
    fun `first authorization registers the actual app with nonce and PKCE`() {
        val url = ChatGptOAuthClient(OkHttpClient()).authorizationUrl(
            hostId, REDIRECT, "state + &", NONCE, pkce,
        ).toHttpUrl()
        assertEquals("https://auth.openai.com/api/accounts/authorize", url.newBuilder().query(null).build().toString())
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("MikuHub", url.queryParameter("agent_name_hint"))
        assertEquals(hostId, url.queryParameter("ext_agent_host_id"))
        assertEquals(REDIRECT, url.queryParameter("redirect_uri"))
        assertEquals("state + &", url.queryParameter("state"))
        assertEquals(NONCE, url.queryParameter("nonce"))
        assertEquals("challenge-fixture", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", url.queryParameter("scope"))
        assertEquals("https://api.openai.com/v1", url.queryParameter("resource"))
    }

    @Test
    fun `returning authorization retains selected registration and omits app name`() {
        val url = ChatGptOAuthClient(OkHttpClient()).authorizationUrl(
            hostId, REDIRECT, "state", NONCE, pkce,
            clientId = CLIENT_ID, idTokenHint = "retained-id-fixture", loginHint = "user@example.com",
        ).toHttpUrl()
        assertEquals(CLIENT_ID, url.queryParameter("client_id"))
        assertEquals(null, url.queryParameter("agent_name_hint"))
        assertEquals("retained-id-fixture", url.queryParameter("id_token_hint"))
        assertEquals("user@example.com", url.queryParameter("login_hint"))
    }

    @Test
    fun `callback cannot replace a selected account registration`() {
        val client = ChatGptOAuthClient(OkHttpClient())
        assertEquals(CLIENT_ID, client.resolveClientId(null, CLIENT_ID))
        assertEquals(CLIENT_ID, client.resolveClientId(CLIENT_ID, null))
        assertEquals(CLIENT_ID, client.resolveClientId(CLIENT_ID, CLIENT_ID))
        assertThrows(IOException::class.java) { client.resolveClientId(null, null) }
        assertThrows(IOException::class.java) { client.resolveClientId(null, "dynamic_agent_client") }
        assertThrows(IOException::class.java) { client.resolveClientId(CLIENT_ID, "another-client") }
    }

    @Test
    fun `authorization rejects callback URLs that could send codes off device`() {
        val client = ChatGptOAuthClient(OkHttpClient())
        listOf(
            "https://example.com/auth/callback",
            "http://localhost:1455/auth/callback",
            "http://127.0.0.1:1455/callback",
            "http://127.0.0.1:1455/auth/callback?token=hidden",
        ).forEach { redirect ->
            assertThrows(IllegalArgumentException::class.java) {
                client.authorizationUrl(hostId, redirect, "state", NONCE, pkce)
            }
        }
    }

    @Test
    fun `authorization code is exchanged with issued client resource and verified identity`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = clientReturning(tokenJson(signedToken()), requests)
        val credentials = client.exchangeCode("code-fixture", CLIENT_ID, pkce.verifier, REDIRECT, NONCE)
        assertEquals("subject-fixture", credentials.subject)
        assertEquals("user@example.com", credentials.email)
        assertEquals(CLIENT_ID, credentials.clientId)
        assertEquals("access-fixture", credentials.accessToken)
        assertEquals("refresh-fixture", credentials.refreshToken)
        assertTrue(credentials.expiresAt > System.currentTimeMillis())
        assertTrue("chatgpt.tokens.use.direct" in credentials.scopes)
        val form = requests.first { it.url.encodedPath.endsWith("/oauth/token") }.body as FormBody
        assertEquals("authorization_code", form.valueFor("grant_type"))
        assertEquals(CLIENT_ID, form.valueFor("client_id"))
        assertEquals("code-fixture", form.valueFor("code"))
        assertEquals("verifier-fixture", form.valueFor("code_verifier"))
        assertEquals(REDIRECT, form.valueFor("redirect_uri"))
        assertEquals("https://api.openai.com/v1", form.valueFor("resource"))
        assertEquals(null, form.valueFor("client_secret"))
        assertFalse(credentials.toString().contains("access-fixture"))
        assertFalse(credentials.toString().contains("refresh-fixture"))
        assertFalse(credentials.toString().contains(credentials.idToken))
    }

    @Test
    fun `identity token alone does not invent plan permission`() = runBlocking {
        val credentials = clientReturning(tokenJson(signedToken(), scope = "openid profile email"))
            .exchangeCode("code", CLIENT_ID, pkce.verifier, REDIRECT, NONCE)
        assertEquals(listOf("openid", "profile", "email"), credentials.scopes)
        assertFalse("chatgpt.tokens.use.direct" in credentials.scopes)
    }

    @Test
    fun `refresh stores rotating token and granted scope while omitting requested scope`() = runBlocking {
        val requests = mutableListOf<Request>()
        val response = """{"access_token":"new-access-fixture","refresh_token":"rotated-refresh-fixture","token_type":"Bearer","expires_in":1800,"scope":"openid email"}"""
        val renewed = clientReturning(response, requests).refresh(credentials())
        assertEquals("new-access-fixture", renewed.accessToken)
        assertEquals("rotated-refresh-fixture", renewed.refreshToken)
        assertEquals(listOf("openid", "email"), renewed.scopes)
        assertEquals(credentials().idToken, renewed.idToken)
        val form = requests.single().body as FormBody
        assertEquals("refresh_token", form.valueFor("grant_type"))
        assertEquals("refresh-fixture", form.valueFor("refresh_token"))
        assertEquals(CLIENT_ID, form.valueFor("client_id"))
        assertEquals("https://api.openai.com/v1", form.valueFor("resource"))
        assertEquals(null, form.valueFor("scope"))
    }

    @Test
    fun `refresh absent replacements preserve original refresh token and scope`() = runBlocking {
        val response = """{"access_token":"new-access-fixture","token_type":"Bearer","expires_in":1800}"""
        val original = credentials()
        val renewed = clientReturning(response).refresh(original)
        assertEquals(original.refreshToken, renewed.refreshToken)
        assertEquals(original.scopes, renewed.scopes)
    }

    @Test
    fun `refresh rejects signed identity changes`() {
        val response = tokenJson(signedToken(baseClaims().with("sub", JsonPrimitive("another-subject"))))
        assertThrows(IOException::class.java) {
            runBlocking { clientReturning(response).refresh(credentials()) }
        }
    }

    @Test
    fun `revoke uses official discovery endpoint and empty success is accepted`() = runBlocking {
        val requests = mutableListOf<Request>()
        clientReturning("", requests).revoke(credentials())
        val request = requests.last()
        assertEquals("https://auth.openai.com/api/accounts/oauth/revoke", request.url.toString())
        val form = request.body as FormBody
        assertEquals("refresh-fixture", form.valueFor("token"))
        assertEquals("refresh_token", form.valueFor("token_type_hint"))
        assertEquals(CLIENT_ID, form.valueFor("client_id"))
    }

    @Test
    fun `failed token responses cannot leak credentials in errors`() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), "access-fixture refresh-fixture code-fixture", 400)
        }.build()
        val error = assertThrows(IOException::class.java) {
            runBlocking {
                ChatGptOAuthClient(http).exchangeCode("code-fixture", CLIENT_ID, pkce.verifier, REDIRECT, NONCE)
            }
        }
        assertTrue(error.message.orEmpty().contains("400"))
        assertFalse(error.toString().contains("fixture"))
        assertFalse(error.stackTraceToString().contains("fixture"))
    }

    @Test
    fun `malformed token response cannot leak credentials in parser errors`() {
        val error = assertThrows(IOException::class.java) {
            runBlocking {
                clientReturning("access-fixture not json").exchangeCode("code", CLIENT_ID, pkce.verifier, REDIRECT, NONCE)
            }
        }
        assertFalse(error.toString().contains("access-fixture"))
        assertFalse(error.stackTraceToString().contains("access-fixture"))
    }

    @Test fun `wrong nonce is rejected`() = reject(signedToken(baseClaims().with("nonce", JsonPrimitive("wrong"))))
    @Test fun `wrong audience is rejected`() = reject(signedToken(baseClaims().with("aud", JsonPrimitive("wrong-client"))))
    @Test fun `wrong issuer is rejected`() = reject(signedToken(baseClaims().with("iss", JsonPrimitive("https://evil.example"))))
    @Test fun `expired token is rejected`() = reject(signedToken(baseClaims().with("exp", JsonPrimitive(1))))
    @Test fun `unknown key id is rejected`() = reject(signedToken(kid = "unknown-key"))
    @Test fun `unsigned algorithm is rejected`() = reject(signedToken(algorithm = "none"))
    @Test fun `missing subject is rejected`() = reject(signedToken(JsonObject(baseClaims().filterKeys { it != "sub" })))

    @Test
    fun `tampered signature is rejected`() {
        val pieces = signedToken().split('.')
        val altered = baseClaims().with("email", JsonPrimitive("attacker@example.com"))
        reject("${pieces[0]}.${base64(altered.toString().toByteArray())}.${pieces[2]}")
    }

    private fun reject(token: String) {
        assertThrows(IOException::class.java) {
            runBlocking {
                clientReturning(tokenJson(token)).exchangeCode("code", CLIENT_ID, pkce.verifier, REDIRECT, NONCE)
            }
        }
    }

    private fun clientReturning(tokenResponse: String, requests: MutableList<Request> = mutableListOf()): ChatGptOAuthClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when (request.url.encodedPath) {
                "/.well-known/jwks.json" -> jwks
                "/.well-known/openid-configuration" -> """{"revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}"""
                "/api/accounts/oauth/token", "/api/accounts/oauth/revoke" -> tokenResponse
                else -> throw AssertionError("Unexpected auth endpoint")
            }
            response(request, body)
        }.build()
        return ChatGptOAuthClient(http)
    }

    private fun credentials() = ChatGptCredentials(
        CLIENT_ID, "subject-fixture", "user@example.com", "access-fixture", "refresh-fixture",
        "retained-id-fixture", System.currentTimeMillis() + 100_000, listOf("openid", "chatgpt.tokens.use.direct"),
    )

    private fun FormBody.valueFor(name: String): String? = (0 until size).firstOrNull { this.name(it) == name }?.let(::value)

    private companion object {
        const val CLIENT_ID = "oaiapp_fixture"
        const val NONCE = "nonce-fixture"
        const val REDIRECT = "http://127.0.0.1:1455/auth/callback"
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val jwks: String = (keyPair.public as RSAPublicKey).let { key ->
            """{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"fixture-key","n":"${base64(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())}","e":"${base64(key.publicExponent.toByteArray())}"}]}"""
        }
        fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun baseClaims() = buildJsonObject {
            put("iss", "https://auth.openai.com")
            put("aud", CLIENT_ID)
            put("sub", "subject-fixture")
            put("email", "user@example.com")
            put("nonce", NONCE)
            put("exp", System.currentTimeMillis() / 1000 + 3600)
        }
        fun JsonObject.with(name: String, value: JsonPrimitive) = JsonObject(this + (name to value))
        fun signedToken(claims: JsonObject = baseClaims(), kid: String = "fixture-key", algorithm: String = "RS256"): String {
            val header = buildJsonObject { put("alg", algorithm); put("kid", kid) }
            val content = "${base64(header.toString().toByteArray())}.${base64(claims.toString().toByteArray())}"
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(content.toByteArray(Charsets.US_ASCII))
            }.sign()
            return "$content.${base64(signature)}"
        }
        fun tokenJson(idToken: String, scope: String = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct") = buildJsonObject {
            put("access_token", "access-fixture")
            put("refresh_token", "refresh-fixture")
            put("id_token", idToken)
            put("token_type", "Bearer")
            put("expires_in", 3600)
            put("scope", scope)
        }.toString()
        fun response(request: Request, body: String, code: Int = 200) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
}
