package dev.local.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * SiliconFlow SenseVoiceSmall Speech-to-Text Provider.
 * Strategy: Whole-file direct upload prioritized (<= 3540s and <= 45MB).
 * Falls back to dynamic PCM WAV chunking only when necessary.
 */
class SiliconFlowProvider(
    private val context: Context,
    private val connectionFactory: () -> HttpURLConnection = {
        URL("https://api.siliconflow.cn/v1/audio/transcriptions").openConnection() as HttpURLConnection
    }
) : SpeechToTextProvider {

    override val provider: AsrProvider = AsrProvider.SILICON_FLOW

    override val capabilities: AsrCapabilities = AsrCapabilities(
        textOnly = true,
        supportsServerProgress = false,
        supportsTimestamps = false,
        maxFileDurationSeconds = 3540L,
        maxFileSizeBytes = 45_000_000L
    )

    override suspend fun transcribe(
        audio: File,
        key: String,
        onProgress: (TranscriptionProgress) -> Unit
    ): TranscriptResult = withContext(Dispatchers.IO) {
        val trimmedKey = key.trim()
        require(trimmedKey.isNotEmpty() && trimmedKey.none { it == '\r' || it == '\n' }) {
            "请先在右上角或设置中填写硅基流动 API Key"
        }
        check(audio.isFile) { "原音频已不存在" }

        val durationSec = runCatching {
            val engine = AudioEngine(context)
            engine.inspect(Uri.fromFile(audio)).durationUs / 1_000_000L
        }.getOrDefault(0L)
        val fileBytes = audio.length()

        // 1. Whole-file direct streaming upload if within limits
        val canUploadDirect = durationSec <= capabilities.maxFileDurationSeconds &&
                fileBytes <= capabilities.maxFileSizeBytes &&
                (audio.extension.equals("m4a", true) || audio.extension.equals("wav", true) || audio.extension.equals("mp3", true) || audio.extension.equals("aac", true))

        if (canUploadDirect) {
            onProgress(TranscriptionProgress("准备上传音频", 0, 1, 0f))
            val text = uploadSingleFile(audio, trimmedKey) { stage, fraction ->
                onProgress(TranscriptionProgress(stage, 1, 1, fraction))
            }
            return@withContext TranscriptResult(
                text = text,
                file = audio,
                segments = emptyList(),
                complete = true,
                provider = AsrProvider.SILICON_FLOW
            )
        }

        // 2. Fallback: Dynamic PCM WAV chunking for oversized files
        onProgress(TranscriptionProgress("音频超出单次限制，正在准备分段", 0, 0))
        val engine = AudioEngine(context)
        var temporaryWav: File? = null
        try {
            val wav = if (audio.extension.equals("wav", true)) audio else {
                val source = engine.inspect(Uri.fromFile(audio))
                File(context.cacheDir, "sf-decode-${UUID.randomUUID()}.wav").also { decoded ->
                    temporaryWav = decoded
                    engine.decodeToWav(source, decoded) { p ->
                        onProgress(TranscriptionProgress("正在解码音频", 0, 0, p))
                    }
                }
            }

            val info = readWavInfo(wav)
            // Calculate max safe frames per chunk (e.g. min(3540s, 45MB / frameBytes))
            val maxChunkFrames = minOf(
                info.rate.toLong() * capabilities.maxFileDurationSeconds,
                (capabilities.maxFileSizeBytes - 44) / info.frameBytes
            )
            val totalFrames = info.bytes / info.frameBytes
            val plan = mutableListOf<AudioChunk>()
            var pos = 0L
            while (pos < totalFrames) {
                val end = minOf(totalFrames, pos + maxChunkFrames)
                plan += AudioChunk(pos, end, pos, end)
                pos = end
            }

            val pieces = mutableListOf<String>()
            val totalParts = plan.size
            plan.forEachIndexed { index, chunk ->
                currentCoroutineContext().ensureActive()
                val partFile = File(context.cacheDir, "sf-chunk-${UUID.randomUUID()}.wav")
                try {
                    onProgress(TranscriptionProgress("准备第 ${index + 1}/$totalParts 段", index, totalParts, 0f))
                    createChunk(wav, info, chunk, partFile)
                    val chunkText = uploadSingleFile(partFile, trimmedKey) { stage, fraction ->
                        onProgress(TranscriptionProgress("正在处理第 ${index + 1}/$totalParts 段 ($stage)", index + 1, totalParts, fraction))
                    }
                    if (chunkText.isNotBlank()) {
                        pieces += chunkText.trim()
                    }
                } finally {
                    partFile.delete()
                }
            }

            onProgress(TranscriptionProgress("整理完成", totalParts, totalParts))
            val fullText = pieces.joinToString("\n\n")
            TranscriptResult(
                text = fullText,
                file = audio,
                segments = emptyList(),
                complete = true,
                provider = AsrProvider.SILICON_FLOW
            )
        } finally {
            temporaryWav?.delete()
        }
    }

    private suspend fun uploadSingleFile(
        file: File,
        key: String,
        onProgress: (String, Float?) -> Unit
    ): String = coroutineScope {
        val boundary = "XiaofuSF${UUID.randomUUID().toString().replace("-", "")}"
        val model = "FunAudioLLM/SenseVoiceSmall"
        val ext = file.extension.lowercase()
        val mimeType = when (ext) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            else -> "audio/wav"
        }
        val filename = "audio.$ext"

        val prefix = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            append("$model\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
            append("Content-Type: $mimeType\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val totalLength = prefix.size.toLong() + file.length() + suffix.size.toLong()

        val connection = connectionFactory()
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setRequestProperty("Accept", "application/json")
        connection.setFixedLengthStreamingMode(totalLength)

        val operation = currentCoroutineContext()[Job]!!
        val cancellationWatcher = launch(Dispatchers.IO) {
            try { awaitCancellation() } finally {
                if (operation.isCancelled) connection.disconnect()
            }
        }

        try {
            onProgress("正在上传音频", 0f)
            connection.outputStream.use { output ->
                output.write(prefix)
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    var sent = 0L
                    val fileLen = file.length().coerceAtLeast(1L)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        sent += n
                        onProgress("正在上传音频", (sent.toFloat() / fileLen).coerceIn(0f, 1f))
                    }
                }
                output.write(suffix)
                output.flush()
            }

            onProgress("小蝠正在整理文字...", null)
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()

                val userMessage = when (statusCode) {
                    401 -> "硅基流动 API Key 无效，请前往设置重新填写"
                    402 -> "硅基流动账号额度不足或欠费停用，请登录平台充值或检查额度"
                    403 -> "访问受限，请确认硅基流动账号已实名认证并创建有效 Key"
                    404 -> "模型接口未找到（$model）"
                    413 -> "音频体积超出限制（50MB）"
                    429 -> "硅基流动请求频率超限，请稍候重试"
                    in 500..599 -> "硅基流动服务器暂时繁忙（HTTP $statusCode），请重试"
                    else -> "转写请求失败（HTTP $statusCode）: $errorBody"
                }
                error(userMessage)
            }

            val responseString = connection.inputStream.bufferedReader().use { it.readText() }
            val parsed = JSONObject(responseString)
            check(parsed.has("text") && parsed.get("text") is String) {
                "硅基流动服务未返回识别文本"
            }
            parsed.getString("text")
        } finally {
            cancellationWatcher.cancel()
            connection.disconnect()
        }
    }
}
