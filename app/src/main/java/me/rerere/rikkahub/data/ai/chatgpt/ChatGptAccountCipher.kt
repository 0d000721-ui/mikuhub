package me.rerere.rikkahub.data.ai.chatgpt

import javax.crypto.SecretKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

internal object ChatGptAccountCipher {
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(plaintext: ByteArray, key: SecretKey, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Let the provider generate a fresh IV; Android Keystore rejects caller-chosen IVs.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        check(cipher.iv.size == IV_BYTES) { "Unexpected AES-GCM IV size" }
        return byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(envelope: ByteArray, key: SecretKey, aad: ByteArray): ByteArray {
        require(envelope.size >= 1 + IV_BYTES + TAG_BITS / 8 && envelope[0] == VERSION) {
            "Invalid encrypted ChatGPT account file"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.copyOfRange(1, 1 + IV_BYTES)))
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope, 1 + IV_BYTES, envelope.size - 1 - IV_BYTES)
    }
}
