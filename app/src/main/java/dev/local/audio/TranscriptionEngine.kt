package dev.local.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Word times are in seconds relative to the ORIGINAL audio; speakers are segment-local. */
data class TranscriptSegment(val start: Double, val end: Double, val text: String)
data class TranscriptResult(val text: String, val file: File, val segments: List<TranscriptSegment>, val complete: Boolean = true)
data class TranscriptionProgress(val stage: String, val completed: Int, val total: Int, val uploadProgress: Float? = null)
internal data class AudioChunk(val startFrame: Long, val endFrame: Long, val keepFromFrame: Long, val keepToFrame: Long)
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

class TranscriptionEngine internal constructor(private val context: Context, private val connectionFactory: () -> HttpURLConnection) {
    constructor(context: Context) : this(context, { URL("https://api.minimaxi.com/v1/speech_to_text").openConnection() as HttpURLConnection })
    private fun outputFile(audio: File): File = File(context.filesDir,"transcripts").apply { mkdirs() }.let { File(it,"${audio.nameWithoutExtension}.txt") }
    suspend fun deleteLocalTranscript(audio: File): Unit = withContext(Dispatchers.IO) {
        // The caller deletes the source only after associated private data has been removed.
        check(audio.isFile) { "原音频已不存在，无法定位分段缓存" }
        val digest=MessageDigest.getInstance("SHA-256")
        audio.inputStream().use { input ->
            val buffer=ByteArray(65536)
            while(true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) }
        }
        val id=digest.digest().joinToString("") { "%02x".format(it) }
        val directory=File(context.noBackupFilesDir,"transcripts/$id")
        check(!directory.exists() || directory.deleteRecursively()) { "无法删除分段缓存，请重试" }
        val output=outputFile(audio)
        listOf(output,File(output.path+".complete"),File(output.path+".tmp"),File(output.path+".complete.tmp")).forEach { file ->
            check(!file.exists() || file.delete()) { "无法删除关联文字，请重试" }
        }
    }
    suspend fun cached(audio: File): TranscriptResult? = withContext(Dispatchers.IO) {
        val output=outputFile(audio)
        if(!output.exists()) null else TranscriptResult(output.readText(),output,emptyList(),File(output.path+".complete").exists())
    }
    suspend fun transcribe(audio: File, key: String, onProgress: (TranscriptionProgress) -> Unit): TranscriptResult = withContext(Dispatchers.IO) {
        require(key.isNotBlank() && key.none { it == '\r' || it == '\n' }) { "请先填写 API Key" }
        check(audio.isFile) { "原音频已不存在" }
        cached(audio)?.takeIf { it.complete }?.let { return@withContext it }
        val digest = MessageDigest.getInstance("SHA-256")
        audio.inputStream().use { input -> val buf = ByteArray(65536); while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buf); if(n<0)break; digest.update(buf,0,n) } }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        val cache = File(context.noBackupFilesDir,"transcripts/$id").apply { mkdirs() }
        var temporary: File? = null
        try {
            onProgress(TranscriptionProgress("正在准备分段音频",0,0))
            val wav = if(audio.extension.equals("wav",true)) audio else {
                val engine = AudioEngine(context)
                val source = engine.inspect(Uri.fromFile(audio))
                File(context.cacheDir,"asr-decode-${UUID.randomUUID()}.wav").also { decoded -> temporary=decoded; engine.decodeToWav(source,decoded) { p -> onProgress(TranscriptionProgress("正在解码音频",0,0,p)) } }
            }
            val info = readWavInfo(wav)
            val plan = planChunks(info.bytes / info.frameBytes, info.rate, info.frameBytes)
            val all = mutableListOf<TranscriptSegment>()
            val pieces = mutableListOf<String>()
            var missingTimestamps=false
            val output=outputFile(audio)
            File(output.path+".complete").delete()
            val tracker = TranscriptionProgressTracker(plan.mapIndexed { index, chunk ->
                if (File(cache, "segment-$index.json").exists()) 0L else (chunk.endFrame - chunk.startFrame) * info.frameBytes + 44L
            }, onProgress)
            // Each slice has an independent task. Bound disk/network pressure without serial waits.
            val responses = supervisorScope {
                val permits = Semaphore(3)
                plan.mapIndexed { index, chunk ->
                    async {
                        try {
                            permits.withPermit {
                                currentCoroutineContext().ensureActive()
                                tracker.preparing(index)
                                val responseFile = File(cache, "segment-$index.json")
                                val response = if (responseFile.exists()) JSONObject(responseFile.readText()) else {
                                    val part = File(context.cacheDir, "asr-${UUID.randomUUID()}.wav")
                                    try {
                                        createChunk(wav, info, chunk, part)
                                        val raw = request(part, key) { _, fraction -> tracker.transfer(index, fraction) }
                                        val parsed = JSONObject(raw)
                                        validateResponse(parsed)
                                        atomicWrite(responseFile, raw)
                                        parsed
                                    } finally { part.delete() }
                                }
                                validateResponse(response)
                                tracker.done(index)
                                Result.success(response)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { tracker.failed(index); Result.failure<JSONObject>(e) }
                    }
                }.awaitAll()
            }
            // Fail only after all siblings settle; successful slices remain cached for retry.
            val failures = responses.count { it.isFailure }
            if (failures > 0) error("$failures 段未完成。" + responses.first { it.isFailure }.exceptionOrNull()?.localizedMessage.orEmpty())
            onProgress(TranscriptionProgress("正在整理全文", plan.size, plan.size))
            plan.forEachIndexed { index, chunk ->
                currentCoroutineContext().ensureActive()
                val response = responses[index].getOrThrow()
                val items=response.optJSONArray("segments")
                val selected = mutableListOf<TranscriptSegment>()
                if(items!=null && items.length()>0) {
                    for(i in 0 until items.length()) {
                        val item=items.getJSONObject(i)
                        val start=item.optDouble("start",Double.NaN) + chunk.startFrame.toDouble()/info.rate
                        val end=item.optDouble("end",Double.NaN) + chunk.startFrame.toDouble()/info.rate
                        val midpoint=(start+end)/2
                        check(start.isFinite() && end.isFinite() && end >= start) { "服务时间戳格式异常；已保留本段结果" }
                        if(midpoint >= chunk.keepFromFrame.toDouble()/info.rate && midpoint < chunk.keepToFrame.toDouble()/info.rate)
                            selected += TranscriptSegment(start,end,item.optString("text"))
                    }
                    all += selected
                    pieces += joinTranscriptTokens(selected.map { it.text })
                } else { pieces += response.getString("text"); missingTimestamps = true }
            }
            val text = pieces.joinToString("\n") + if(missingTimestamps && plan.size>1) "\n\n[部分段落未返回时间戳，接缝可能有重复，请校对。]" else ""
            atomicWrite(output,text)
            File(cache,"timestamps.json").writeText(JSONArray().apply { all.forEach { put(JSONObject().put("start",it.start).put("end",it.end).put("text",it.text)) } }.toString())
            atomicWrite(File(output.path+".complete"),"complete")
            TranscriptResult(text,output,all)
        } finally { temporary?.delete() }
    }

    private fun validateResponse(parsed: JSONObject) {
        check(parsed.has("text") && parsed.get("text") is String) { "服务未返回文本，请重试本段" }
        parsed.optJSONArray("segments")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val start = item.optDouble("start", Double.NaN)
                val end = item.optDouble("end", Double.NaN)
                check(start.isFinite() && end.isFinite() && start >= 0 && end >= start && item.has("text")) {
                    "服务时间戳格式异常，请重试本段"
                }
            }
        }
    }

    private suspend fun request(file:File,key:String,onProgress:(String,Float?)->Unit):String = coroutineScope {
        val boundary="Shengyu${UUID.randomUUID()}"
        val fields=mapOf("model" to "asr-1.0","response_format" to "verbose_json","timestamp_level" to "word","stream" to "false")
        val prefix=buildString {
            fields.forEach { (k,v)-> append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
        }.toByteArray()
        val suffix="\r\n--$boundary--\r\n".toByteArray()
        val connection=connectionFactory()
        // No redirect can carry the credential to another host.
        connection.instanceFollowRedirects=false; connection.requestMethod="POST"; connection.doOutput=true
        connection.connectTimeout=30_000; connection.readTimeout=180_000
        connection.setRequestProperty("Authorization","Bearer $key")
        connection.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(prefix.size.toLong()+file.length()+suffix.size)
        val operation=currentCoroutineContext()[Job]!!
        val cancellationWatcher=launch(Dispatchers.IO) { try { awaitCancellation() } finally { if(operation.isCancelled) connection.disconnect() } }
        try {
            connection.outputStream.use { output ->
                output.write(prefix)
                file.inputStream().use { input -> val buffer=ByteArray(65536); var sent=0L; while(true) { ensureActive(); val n=input.read(buffer); if(n<0)break; output.write(buffer,0,n);sent+=n;onProgress("正在上传",sent.toFloat()/file.length()) } }
                output.write(suffix)
            }
            onProgress("等待识别结果",null)
            val status=connection.responseCode
            check(status in 200..299) { when(status) { 401,403->"API Key 无效或没有权限，请检查设置"; 429->"请求频率或额度受限，请稍后重试"; 413->"服务拒绝音频大小"; in 500..599->"MiniMax 服务暂时异常，请重试失败段"; else->"转写请求失败（HTTP $status），已完成段落会保留" } }
            connection.inputStream.bufferedReader().use { reader ->
                val result=StringBuilder();val buffer=CharArray(8192)
                while(true) { ensureActive();val n=reader.read(buffer);if(n<0)break;check(result.length+n<=8_000_000){"服务返回内容过大"};result.append(buffer,0,n) }
                result.toString()
            }
        } catch(e:Exception) { ensureActive(); throw e }
        finally { cancellationWatcher.cancel(); connection.disconnect() }
    }
}

internal data class WavInfo(val rate:Int,val channels:Int,val dataOffset:Long,val bytes:Long) { val frameBytes:Int get()=channels*2 }
internal fun readWavInfo(file:File):WavInfo = RandomAccessFile(file,"r").use { input ->
    fun str()=ByteArray(4).also{input.readFully(it)}.toString(Charsets.US_ASCII)
    fun uint()=Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
    check(str()=="RIFF"){"转写需要标准 WAV 音频"};uint();check(str()=="WAVE")
    var rate=0;var channels=0
    while(input.filePointer+8<=input.length()) {
        val type=str();val size=uint();val position=input.filePointer
        check(position+size<=input.length()){ "WAV 文件不完整" }
        if(type=="fmt ") {
            check(size>=16); val format=java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            channels=java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535; rate=uint().toInt();uint();input.readShort()
            val bits=java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            check(format==1 && bits==16 && channels>0 && rate>0){"仅支持 16 位 PCM WAV 转写"}
        } else if(type=="data") { check(rate>0&&channels>0);return@use WavInfo(rate,channels,position,size) }
        input.seek(position+size+(size%2))
    }
    error("WAV 中没有音频数据")
}
private suspend fun createChunk(source:File,info:WavInfo,chunk:AudioChunk,target:File) {
    val bytes=(chunk.endFrame-chunk.startFrame)*info.frameBytes
    val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt((bytes+36).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(info.channels.toShort()).putInt(info.rate).putInt(info.rate*info.frameBytes).putShort(info.frameBytes.toShort()).putShort(16).put("data".toByteArray()).putInt(bytes.toInt())
    RandomAccessFile(source,"r").use { input -> input.seek(info.dataOffset+chunk.startFrame*info.frameBytes);target.outputStream().use { output -> output.write(header.array());var left=bytes;val buffer=ByteArray(65536);while(left>0){currentCoroutineContext().ensureActive();val n=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());check(n>0);output.write(buffer,0,n);left-=n} } }
}

internal fun joinTranscriptTokens(tokens: List<String>): String = buildString {
    tokens.forEach { token ->
        if(isNotEmpty() && token.isNotEmpty() && last().isLetterOrDigit() && token.first().isLetterOrDigit()
            && last().code < 0x2e80 && token.first().code < 0x2e80) append(' ')
        append(token)
    }
}
private fun atomicWrite(file: File, text: String) {
    val pending=File(file.path+".tmp")
    pending.writeText(text)
    check(pending.renameTo(file)) { "无法保存转写文本" }
}
