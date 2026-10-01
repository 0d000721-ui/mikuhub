package me.rerere.rikkahub.data.ai.chatgpt

import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptAccountCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val aad = "mikuhub-chatgpt-v1".toByteArray(UTF_8)
    private val plaintext = "access-secret refresh-secret id-secret".toByteArray(UTF_8)

    @Test fun credentialsRoundTripWithoutAppearingInStoredBytes() {
        val encrypted = ChatGptAccountCipher.encrypt(plaintext, key, aad)
        assertFalse(String(encrypted, UTF_8).contains("access-secret"))
        assertArrayEquals(plaintext, ChatGptAccountCipher.decrypt(encrypted, key, aad))
    }

    @Test fun editingTheEncryptedFileCannotProduceAcceptedCredentials() {
        val encrypted = ChatGptAccountCipher.encrypt(plaintext, key, aad)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { ChatGptAccountCipher.decrypt(encrypted, key, aad) }.isFailure)
    }

    @Test fun aDifferentApplicationCannotUseCopiedCiphertext() {
        val encrypted = ChatGptAccountCipher.encrypt(plaintext, key, aad)
        assertTrue(runCatching {
            ChatGptAccountCipher.decrypt(encrypted, key, "another-package".toByteArray(UTF_8))
        }.isFailure)
    }

    @Test fun repeatedWritesNeverReuseTheSameEncryptionNonce() {
        val first = ChatGptAccountCipher.encrypt(plaintext, key, aad)
        val second = ChatGptAccountCipher.encrypt(plaintext, key, aad)
        assertFalse(first.contentEquals(second))
    }

    @Test fun truncatedAndUnknownEnvelopesAreRejected() {
        assertTrue(runCatching { ChatGptAccountCipher.decrypt(byteArrayOf(1), key, aad) }.isFailure)
        val encrypted = ChatGptAccountCipher.encrypt(plaintext, key, aad)
        encrypted[0] = 99
        assertTrue(runCatching { ChatGptAccountCipher.decrypt(encrypted, key, aad) }.isFailure)
    }
}
