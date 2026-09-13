package me.rerere.rikkahub.device

import android.content.Context
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64

data class DebugCertificateInfo(val file: File, val fingerprintSha256: String)

class DebugCertificateStore(context: Context) {
    private val file = File(context.filesDir, "traffic-debug-ca.cer")
    fun info(): DebugCertificateInfo? = if (!file.exists()) null else DebugCertificateInfo(file, fingerprint(file.readBytes()))
    fun revoke() { file.delete() }
    fun generate(): DebugCertificateInfo {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        // Store a public-key artifact only; installation/trust remains an explicit user action.
        file.writeBytes(pair.public.encoded)
        return DebugCertificateInfo(file, fingerprint(pair.public.encoded))
    }
    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
}
