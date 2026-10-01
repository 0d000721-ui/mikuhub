package me.rerere.rikkahub.data.ai.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.serialization.json.Json

/** Never put OAuth credentials in SettingsStore, exportable backups, or API-key fields. */
internal class ChatGptEncryptedAccountStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-accounts-v1.enc"))
    private val keyAlias = "${context.packageName}.chatgpt.accounts.v1"
    private val aad = "$keyAlias:1".toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): ChatGptAccountState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return ChatGptAccountState(hostId = newChatGptHostId())
        }
        require(file.baseFile.length() <= MAX_FILE_BYTES) { "ChatGPT account file is too large" }
        val encrypted = file.readFully()
        require(encrypted.size <= MAX_FILE_BYTES) { "ChatGPT account file is too large" }
        val plaintext = ChatGptAccountCipher.decrypt(encrypted, key(create = false), aad)
        try {
            val state = json.decodeFromString(ChatGptAccountState.serializer(), plaintext.toString(Charsets.UTF_8))
            require(state.hostId.isNotBlank()) { "Missing ChatGPT host identity" }
            require(state.records.all { (id, record) -> id == record.id && id == record.clientId }) {
                "Invalid ChatGPT account identity"
            }
            state.records.values.forEach { record ->
                record.credentials?.let {
                    requireSameChatGptIdentity(record.clientId, record.subject, it.clientId, it.subject)
                }
            }
            return state
        } finally {
            plaintext.fill(0)
        }
    }

    fun write(state: ChatGptAccountState) {
        val plaintext = json.encodeToString(ChatGptAccountState.serializer(), state).toByteArray(Charsets.UTF_8)
        val encrypted = try { ChatGptAccountCipher.encrypt(plaintext, key(create = true), aad) }
        finally { plaintext.fill(0) }
        require(encrypted.size <= MAX_FILE_BYTES) { "ChatGPT account file is too large" }
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun key(create: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "ChatGPT account encryption key is unavailable" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object { const val MAX_FILE_BYTES = 2L * 1024 * 1024 }
}
