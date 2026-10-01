package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChatGptResponseErrorTest {
    @Test
    fun `qualification reason retains its code and parameter while credentials and URL parameters are hidden`() {
        val jwt = "eyJfixtureHeader.fixturePayload.fixtureSignature"
        val reason = "Ultrafast is unavailable for this workspace. See " +
            "https://images.example.test/asset.png?signature=fixture-query-secret#fixture-fragment-secret; " +
            "Bearer fixture-bearer-secret, $jwt, sk-fixture-api-secret."
        val error = error(reason)
        val message = error.message.orEmpty()
        assertTrue(error is HttpException)
        assertFalse(error is IOException)
        assertTrue(message.contains("subscription_sharing_unsupported_capability"))
        assertTrue(message.contains("service_tier"))
        assertTrue(message.contains("Ultrafast is unavailable for this workspace."))
        assertTrue(message.contains("https://images.example.test/asset.png"))
        for (secret in listOf("fixture-query-secret", "fixture-fragment-secret", "fixture-bearer-secret", jwt, "sk-fixture-api-secret")) {
            assertFalse("Credential fragments must not reach exception or application logs", message.contains(secret))
            assertFalse(error.stackTraceToString().contains(secret))
        }
    }

    @Test
    fun `URL query and fragment removal happens before truncation and preserves the following recovery instruction`() {
        val prefix = "x".repeat(900)
        val query = "fixture-private-query".repeat(200)
        val reason = "$prefix https://images.example.test/asset.png?signature=$query#fixture-fragment Choose Standard."
        val message = error(reason).message.orEmpty()
        assertTrue(message.contains("https://images.example.test/asset.png"))
        assertTrue("A long secret URL must not displace the recovery instruction", message.contains("Choose Standard."))
        assertFalse(message.contains("fixture-private-query"))
        assertFalse(message.contains("fixture-fragment"))
        assertFalse(message.contains("signature="))
    }

    @Test
    fun `Bearer credential removal happens before truncation and preserves the following ordinary reason`() {
        val prefix = "ordinary ".repeat(105)
        val token = "fixture-private-bearer".repeat(200)
        val message = error("$prefix Bearer $token Workspace access is restricted.").message.orEmpty()
        assertTrue("A long credential must not displace the actual rejection reason", message.contains("Workspace access is restricted."))
        assertFalse(message.contains("fixture-private-bearer"))
    }

    private fun error(reason: String): Throwable = chatGptResponseError(buildJsonObject {
        put("error", buildJsonObject {
            put("code", "subscription_sharing_unsupported_capability")
            put("param", "service_tier")
            put("message", reason)
        })
    }, statusCode = 400)
}
