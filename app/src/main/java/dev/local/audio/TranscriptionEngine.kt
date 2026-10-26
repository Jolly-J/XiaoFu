package dev.local.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Word times are in seconds relative to the ORIGINAL audio; speakers are segment-local. */
data class TranscriptSegment(val start: Double, val end: Double, val text: String)

data class TranscriptResult(
    val text: String,
    val file: File,
    val segments: List<TranscriptSegment> = emptyList(),
    val complete: Boolean = true,
    val provider: AsrProvider = AsrProvider.SILICON_FLOW
)

data class TranscriptionProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
    val uploadProgress: Float? = null
)

internal data class AudioChunk(
    val startFrame: Long,
    val endFrame: Long,
    val keepFromFrame: Long,
    val keepToFrame: Long
)

internal fun planChunks(frames: Long, rate: Int, frameBytes: Int): List<AudioChunk> {
    require(frames > 0 && rate > 0 && frameBytes > 0)
    val cap = minOf(rate.toLong() * 450, (45_000_000L - 44) / frameBytes)
    val overlap = minOf(rate.toLong(), cap / 10)
    val core = cap - 2 * overlap
    require(core > 0)
    val result = mutableListOf<AudioChunk>()
    var pos = 0L
    while (pos < frames) {
        val end = minOf(frames, pos + core)
        result += AudioChunk(maxOf(0, pos - overlap), minOf(frames, end + overlap), pos, end)
        pos = end
    }
    return result
}

class TranscriptionEngine(private val context: Context) {
    private val siliconFlowProvider = SiliconFlowProvider(context)
    private val miniMaxProvider = MiniMaxProvider(context)

    fun getProvider(p: AsrProvider): SpeechToTextProvider = when (p) {
        AsrProvider.SILICON_FLOW -> siliconFlowProvider
        AsrProvider.MINIMAX -> miniMaxProvider
    }

    private fun outputFile(audio: File): File =
        File(context.filesDir, "transcripts").apply { mkdirs() }.let { File(it, "${audio.nameWithoutExtension}.txt") }

    private fun providerFile(audio: File): File =
        File(context.filesDir, "transcripts").apply { mkdirs() }.let { File(it, "${audio.nameWithoutExtension}.provider") }

    fun readProvider(audio: File): AsrProvider {
        val pf = providerFile(audio)
        if (pf.exists()) {
            val id = runCatching { pf.readText().trim() }.getOrNull()
            return AsrProvider.fromId(id)
        }
        // Legacy transcripts without .provider metadata file were processed by MiniMax
        return AsrProvider.MINIMAX
    }

    fun cachedFast(audio: File): TranscriptResult? {
        val output = outputFile(audio)
        if (!output.exists()) return null
        val complete = File(output.path + ".complete").exists()
        val provider = readProvider(audio)
        return TranscriptResult(
            text = output.readText(),
            file = output,
            segments = emptyList(),
            complete = complete,
            provider = provider
        )
    }

    suspend fun cached(audio: File): TranscriptResult? = withContext(Dispatchers.IO) {
        cachedFast(audio)
    }

    suspend fun deleteLocalTranscript(audio: File): Unit = withContext(Dispatchers.IO) {
        check(audio.isFile) { "原音频已不存在，无法定位分段缓存" }
        val digest = MessageDigest.getInstance("SHA-256")
        audio.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        val directory = File(context.noBackupFilesDir, "transcripts/$id")
        check(!directory.exists() || directory.deleteRecursively()) { "无法删除分段缓存，请重试" }

        val output = outputFile(audio)
        val filesToDelete = listOf(
            output,
            File(output.path + ".complete"),
            File(output.path + ".tmp"),
            File(output.path + ".complete.tmp"),
            providerFile(audio)
        )
        filesToDelete.forEach { file ->
            check(!file.exists() || file.delete()) { "无法删除关联文字，请重试" }
        }
    }

    suspend fun transcribe(
        audio: File,
        key: String,
        provider: AsrProvider = ApiKeyStore.getPreferredProvider(context),
        onProgress: (TranscriptionProgress) -> Unit
    ): TranscriptResult = withContext(Dispatchers.IO) {
        val impl = getProvider(provider)
        val output = outputFile(audio)
        File(output.path + ".complete").delete()

        val result = impl.transcribe(audio, key, onProgress)

        atomicWrite(output, result.text)
        atomicWrite(File(output.path + ".complete"), "complete")
        atomicWrite(providerFile(audio), provider.id)

        result.copy(file = output, provider = provider)
    }
}

internal data class WavInfo(val rate: Int, val channels: Int, val dataOffset: Long, val bytes: Long) {
    val frameBytes: Int get() = channels * 2
}

internal fun readWavInfo(file: File): WavInfo = RandomAccessFile(file, "r").use { input ->
    fun str() = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
    fun uint() = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
    check(str() == "RIFF") { "转写需要标准 WAV 音频" }
    uint()
    check(str() == "WAVE")
    var rate = 0
    var channels = 0
    while (input.filePointer + 8 <= input.length()) {
        val type = str()
        val size = uint()
        val position = input.filePointer
        check(position + size <= input.length()) { "WAV 文件不完整" }
        if (type == "fmt ") {
            check(size >= 16)
            val format = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            channels = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            rate = uint().toInt()
            uint()
            input.readShort()
            val bits = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            check(format == 1 && bits == 16 && channels > 0 && rate > 0) { "仅支持 16 位 PCM WAV 转写" }
        } else if (type == "data") {
            check(rate > 0 && channels > 0)
            return@use WavInfo(rate, channels, position, size)
        }
        input.seek(position + size + (size % 2))
    }
    error("WAV 中没有音频数据")
}

internal fun createChunk(wav: File, info: WavInfo, chunk: AudioChunk, target: File) {
    val bytes = (chunk.endFrame - chunk.startFrame) * info.frameBytes
    require(bytes > 0 && bytes <= 45_000_000L - 44)
    target.outputStream().use { output ->
        fun int(v: Long) {
            val i = v.toInt()
            output.write(byteArrayOf(i.toByte(), (i shr 8).toByte(), (i shr 16).toByte(), (i shr 24).toByte()))
        }
        fun short(v: Int) = output.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        int(bytes + 36)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        int(16)
        short(1)
        short(info.channels)
        int(info.rate.toLong())
        int(info.rate.toLong() * info.frameBytes)
        short(info.frameBytes)
        short(16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        int(bytes)

        RandomAccessFile(wav, "r").use { input ->
            input.seek(info.dataOffset + chunk.startFrame * info.frameBytes)
            val buf = ByteArray(65536)
            var rem = bytes
            while (rem > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                check(n > 0) { "WAV 文件过短" }
                output.write(buf, 0, n)
                rem -= n
            }
        }
    }
}

internal fun joinTranscriptTokens(tokens: List<String>): String {
    val b = StringBuilder()
    for (t in tokens) {
        val s = t.trim()
        if (s.isEmpty()) continue
        if (b.isNotEmpty()) {
            val last = b.last()
            val first = s.first()
            if (isAsciiAlphaNumeric(last) && isAsciiAlphaNumeric(first)) b.append(' ')
        }
        b.append(s)
    }
    return b.toString()
}

private fun isAsciiAlphaNumeric(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

internal fun atomicWrite(target: File, text: String) {
    val tmp = File(target.path + ".tmp")
    tmp.writeText(text)
    check(tmp.renameTo(target)) { "写入文件失败" }
}
