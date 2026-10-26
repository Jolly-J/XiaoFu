package dev.local.audio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provider-isolated secure API Key storage in AndroidKeyStore.
 * The key never enters backups, logs, or exported content.
 */
class ApiKeyStore(context: Context, val provider: AsrProvider = AsrProvider.SILICON_FLOW) {
    constructor(context: Context) : this(context, AsrProvider.SILICON_FLOW)

    private val file = File(context.noBackupFilesDir, "${provider.id}-key.bin")
    private val alias = when (provider) {
        AsrProvider.MINIMAX -> "shengyu.minimax.api-key.v1"
        AsrProvider.SILICON_FLOW -> "xiaofu.siliconflow.api-key.v1"
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(value: String) {
        val cleaned = value.trim()
        require(cleaned.isNotEmpty() && cleaned.none { it == '\r' || it == '\n' }) { "请输入有效的 API Key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val temporary = File(file.parentFile, "${provider.id}-key.tmp")
        temporary.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(cleaned.toByteArray(Charsets.UTF_8)))
        check(temporary.renameTo(file)) { "无法保存 API Key" }
    }

    fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size > 13) { "密钥存储损坏，请重新填写" }
        val ivSize = bytes[0].toInt() and 255
        require(ivSize == 12 && bytes.size > ivSize + 1) { "密钥存储损坏，请重新填写" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivSize))) }
        return String(cipher.doFinal(bytes.copyOfRange(1 + ivSize, bytes.size)), Charsets.UTF_8)
    }

    fun clear() {
        check(!file.exists() || file.delete()) { "无法删除密钥" }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    companion object {
        private const val PREFS = "transcription_prefs"
        private const val KEY_PROVIDER = "preferred_provider"

        fun getPreferredProvider(context: Context): AsrProvider {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_PROVIDER, null)
            return if (saved != null) AsrProvider.fromId(saved) else AsrProvider.SILICON_FLOW
        }

        fun setPreferredProvider(context: Context, provider: AsrProvider) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PROVIDER, provider.id).apply()
        }
    }
}
