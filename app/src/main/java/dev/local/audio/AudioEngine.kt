package dev.local.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.StatFs
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ByteBuffer

/** All media stays on the device. Callers own the export after successful completion. */
data class AudioSource(
    val uri: Uri,
    val name: String,
    val durationUs: Long,
    val mime: String,
    val sampleRate: Int,
    val channelCount: Int,
    val trackIndex: Int,
) {
    val isLossless: Boolean get() = mime == MediaFormat.MIMETYPE_AUDIO_AAC
}

enum class OutputFormat(val extension: String, val mime: String) {
    M4A("m4a", "audio/mp4"), WAV("wav", "audio/wav")
}

data class AudioExport(val file: File, val durationUs: Long, val mime: String = "audio/mp4")

class AudioEngine(private val context: Context) {
    private fun diagnostic(uri: Uri, output: DiagnosticOutput): DiagnosticSession {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.0"
        return Diagnostics.begin(version, Build.VERSION.SDK_INT, uri.scheme, output)
    }
    private suspend fun open(extractor: MediaExtractor, uri: Uri, report: DiagnosticSession) {
        report.stage(DiagnosticStage.OPEN_SOURCE)
        try {
            // Same local AssetFileDescriptor contract used by MediaExtractor's Context overload.
            // Record numeric offset/length from the actual opened descriptor, never the URI.
            check(uri.scheme == "content" || uri.scheme == "file") { "请选择设备上的视频文件" }
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: error("无法打开所选视频")
            descriptor.use { afd ->
                report.descriptor(afd.startOffset, afd.declaredLength)
                val seekable = try {
                    Os.lseek(afd.fileDescriptor, 0, OsConstants.SEEK_CUR)
                    true
                } catch (failure: ErrnoException) {
                    // Unknown length alone is not a reason to copy. Never hide regular-file errors.
                    if (failure.errno == OsConstants.ESPIPE) false else throw failure
                }
                if (seekable) {
                    if (afd.declaredLength < 0) extractor.setDataSource(afd.fileDescriptor)
                    else extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                } else {
                    report.stage(DiagnosticStage.STAGING_SOURCE)
                    stageNonSeekableSource(extractor, afd)
                    report.stage(DiagnosticStage.OPEN_SOURCE)
                }
            }
        } catch (failure: Throwable) {
            report.fail(if (failure is kotlinx.coroutines.CancellationException) DiagnosticError.CANCELLED else DiagnosticError.SOURCE_READ)
            throw failure
        }
    }
    /** Only invoked after lseek reports ESPIPE on the very descriptor we will read. */
    private suspend fun stageNonSeekableSource(extractor: MediaExtractor, afd: AssetFileDescriptor) {
        val maximumBytes = 8L * 1024 * 1024 * 1024
        val reserveBytes = 64L * 1024 * 1024
        val maximumElapsedMs = 30L * 60 * 1000
        val idleTimeoutMs = 30_000L
        check(afd.startOffset >= 0 && afd.startOffset <= maximumBytes) { "视频流起始偏移无效或超过 8 GiB 暂存上限" }
        check(afd.declaredLength < 0 || afd.declaredLength <= maximumBytes - afd.startOffset) { "此视频流超过 8 GiB 本地暂存上限，请先保存到设备后再选择" }
        val directory = File(context.cacheDir, "source-staging")
        check(directory.mkdirs() || directory.isDirectory) { "无法创建视频本地暂存目录" }
        val available = StatFs(directory.absolutePath).availableBytes
        check(available > reserveBytes && (afd.declaredLength < 0 || afd.declaredLength <= available - reserveBytes)) {
            "设备空间不足，无法本地暂存此视频；请释放空间后重试"
        }
        val temporary = File.createTempFile("source_", ".partial", directory)
        val fd = afd.fileDescriptor
        var input: ParcelFileDescriptor.AutoCloseInputStream? = null
        try {
            currentCoroutineContext().ensureActive()
            // FileChannel is an InterruptibleChannel on API 1+. A dedicated duplicate
            // lets cancellation close our reader without closing the source AFD or
            // modifying shared O_NONBLOCK flags (fcntlInt is only public since API 30).
            input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd))
            val channel = input.channel
            val readFd = input.fd
            val descriptor = StructPollfd().apply {
                this.fd = readFd
                events = OsConstants.POLLIN.toShort()
            }
            val pollDescriptors = arrayOf(descriptor)
            val buffer = ByteArray(64 * 1024)
            var skipped = 0L
            var copied = 0L
            var consumed = 0L
            val started = SystemClock.elapsedRealtime()
            var lastProgress = started
            FileOutputStream(temporary).use { output ->
                while (afd.declaredLength < 0 || copied < afd.declaredLength || skipped < afd.startOffset) {
                    currentCoroutineContext().ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    check(now - started < maximumElapsedMs) { "视频本地暂存超过 30 分钟，请先将视频保存到设备后重试" }
                    check(now - lastProgress < idleTimeoutMs) { "视频来源连续 30 秒没有返回数据，请先保存到设备后重试" }
                    check(StatFs(directory.absolutePath).availableBytes > reserveBytes + buffer.size) {
                        "设备空间不足，视频本地暂存已停止；请释放空间后重试"
                    }
                    val ready = try {
                        Os.poll(pollDescriptors, 100)
                    } catch (failure: ErrnoException) {
                        if (failure.errno == OsConstants.EINTR) continue else throw failure
                    }
                    currentCoroutineContext().ensureActive()
                    if (ready == 0) continue
                    check(descriptor.revents.toInt() and OsConstants.POLLNVAL == 0) { "视频来源已关闭，无法完成本地暂存" }
                    val remaining = if (skipped < afd.startOffset) afd.startOffset - skipped
                        else if (afd.declaredLength >= 0) afd.declaredLength - copied else buffer.size.toLong()
                    val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                    if (wanted == 0) break
                    // poll readiness is not a cancellation guarantee. If a competing
                    // reader consumes it first, interrupting the FileChannel read still
                    // closes this duplicate and promptly wakes the blocked syscall.
                    val remainingWaitMs = minOf(
                        idleTimeoutMs - (SystemClock.elapsedRealtime() - lastProgress),
                        maximumElapsedMs - (SystemClock.elapsedRealtime() - started),
                    ).coerceAtLeast(1)
                    val count = withTimeoutOrNull(remainingWaitMs) {
                        val readContext = currentCoroutineContext()
                        runInterruptible {
                            try {
                                channel.read(ByteBuffer.wrap(buffer, 0, wanted))
                            } catch (failure: IOException) {
                                // FileChannel reports interrupt as IOException, not InterruptedException.
                                // Preserve the timeout/user-cancellation cause instead of masking it.
                                readContext.ensureActive()
                                throw failure
                            }
                        }
                    } ?: error("视频来源读取超时，请先保存到设备后重试")
                    if (count < 0) {
                        // Do not treat a reliable pipe's remote failure as successful EOF.
                        afd.parcelFileDescriptor.checkError()
                        break
                    }
                    if (count == 0) continue
                    consumed += count
                    check(consumed <= maximumBytes) { "视频流超过 8 GiB 本地暂存上限，请先保存到设备后再选择" }
                    lastProgress = SystemClock.elapsedRealtime()
                    if (skipped < afd.startOffset) skipped += count else {
                        output.write(buffer, 0, count)
                        copied += count
                    }
                }
                currentCoroutineContext().ensureActive()
                afd.parcelFileDescriptor.checkError()
                check(skipped == afd.startOffset && (afd.declaredLength < 0 || copied == afd.declaredLength)) {
                    "视频来源提前结束，未能完整读取所选文件"
                }
                check(copied > 0) { "所选视频没有可读取的数据" }
            }
            currentCoroutineContext().ensureActive()
            FileInputStream(temporary).use { staged ->
                // MediaExtractor owns its own descriptor reference after this returns.
                extractor.setDataSource(staged.fd, 0, temporary.length())
            }
        } finally {
            // No descriptor status flags are changed. Closing only the duplicate preserves
            // the original AFD for its owner and the reliable-pipe error channel.
            runCatching { input?.close() }
            temporary.delete()
        }
    }

    private fun describe(extractor: MediaExtractor, source: AudioSource, report: DiagnosticSession) {
        val format = extractor.getTrackFormat(source.trackIndex)
        report.source(format.getString(MediaFormat.KEY_MIME), extractor.trackCount, source.trackIndex,
            format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0), format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0),
            format.longOr(MediaFormat.KEY_DURATION, 0))
        report.stage(DiagnosticStage.SELECT_TRACK)
    }
    suspend fun inspect(uri: Uri): AudioSource = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val report = diagnostic(uri, DiagnosticOutput.SOURCE_SCAN)
        val extractor = MediaExtractor()
        try {
            open(extractor, uri, report)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("这个视频没有可提取的音轨。请选择带声音的视频。")
            val format = extractor.getTrackFormat(index)
            val name = if (uri.scheme == "file") File(checkNotNull(uri.path)).name else runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: "视频"
            AudioSource(uri, name, format.longOr(MediaFormat.KEY_DURATION, 0L),
                format.getString(MediaFormat.KEY_MIME) ?: error("无法识别音轨格式"),
                format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0), format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0), index).also { source ->
                describe(extractor, source, report)
                report.finish()
            }
        } catch (failure: Throwable) {
            report.fail(diagnosticError(failure))
            throw failure
        } finally {
            extractor.release()
        }
    }

    suspend fun extract(source: AudioSource, format: OutputFormat = OutputFormat.M4A, onProgress: (Float) -> Unit): AudioExport = withContext(Dispatchers.IO) {
        val report = diagnostic(source.uri, if (format == OutputFormat.M4A) DiagnosticOutput.M4A else DiagnosticOutput.WAV)
        val directory = File(context.filesDir, "exports")
        val file = try {
            check(directory.mkdirs() || directory.isDirectory) { "无法创建音频保存目录" }
            // A killed process may leave this file, but history never mistakes it for an export.
            File.createTempFile((exportBaseName(source.name) + "_").padEnd(3, '_'), ".partial", directory)
        } catch (failure: Throwable) {
            report.fail(DiagnosticError.STORAGE)
            throw failure
        }
        val completed = File(directory, file.name.removeSuffix(".partial") + ".${format.extension}")
        var committed = false
        try {
            currentCoroutineContext().ensureActive()
            when {
                format == OutputFormat.WAV -> decodeWav(source, file, onProgress, report)
                source.isLossless -> remux(source, file, onProgress, report)
                else -> transcode(source, file, onProgress, report)
            }
            currentCoroutineContext().ensureActive()
            check(file.length() > 0) { "未能生成音频文件" }
            report.stage(DiagnosticStage.COMMIT)
            check(!completed.exists() && file.renameTo(completed)) { "无法完成音频文件保存，请检查剩余空间" }
            committed = true
            onProgress(1f)
            report.finish(completed.length())
            AudioExport(completed, source.durationUs, format.mime)
        } catch (failure: Throwable) {
            report.fail(diagnosticError(failure))
            file.delete()
            if (committed) completed.delete()
            throw failure
        }
    }

    private suspend fun remux(source: AudioSource, file: File, progress: (Float) -> Unit, report: DiagnosticSession) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            open(extractor, source.uri, report)
            describe(extractor, source, report)
            extractor.selectTrack(source.trackIndex)
            val format = extractor.getTrackFormat(source.trackIndex)
            val writer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val outputTrack = writer.addTrack(format)
            writer.start()
            started = true
            var buffer = ByteBuffer.allocateDirect(format.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024).coerceIn(4096, 16 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val timeline = AudioTimeline()
            var lastPts = -1L
            var count = 0
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                report.stage(DiagnosticStage.READ_SAMPLE)
                val size = if (Build.VERSION.SDK_INT >= 28) extractor.sampleSize else 0L
                check(size in 0..(16 * 1024 * 1024).toLong()) { "音频数据包异常或过大" }
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) {
                    report.sample(extractor.sampleTime, extractor.sampleTrackIndex, -1)
                    break
                }
                val sampleFlags = extractor.sampleFlags
                report.sample(extractor.sampleTime, extractor.sampleTrackIndex, read.toLong(), sampleFlags)
                validateReadableSampleFlags(sampleFlags)
                check(sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME == 0) { "不支持分片音频数据包的无损封装" }
                val pts = timeline.relative(extractor.sampleTime)
                check(pts >= lastPts) { "音轨时间戳异常，无法无损提取" }
                info.set(0, read, pts, if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                report.stage(DiagnosticStage.MUX)
                writer.writeSampleData(outputTrack, buffer, info)
                lastPts = pts
                count++
                progress(extractionProgress(pts, source.durationUs))
                extractor.advance()
            }
            if (extractor.sampleTrackIndex < 0) report.sample(extractor.sampleTime, -1, -1)
            check(count > 0) { "音轨没有可读取的音频数据" }
            writer.stop()
            started = false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    /** EOS is a state, not a sample. Never interpret getSampleFlags() at EOS. */
    private fun queueDecoderInput(extractor: MediaExtractor, decoder: MediaCodec, inputIndex: Int, report: DiagnosticSession): Boolean {
        val timeUs = extractor.sampleTime
        val trackIndex = extractor.sampleTrackIndex
        report.stage(DiagnosticStage.READ_SAMPLE)
        if (trackIndex < 0) {
            report.sample(timeUs, trackIndex, -1)
            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        val buffer = checkNotNull(decoder.getInputBuffer(inputIndex)).apply { clear() }
        if (Build.VERSION.SDK_INT >= 28) {
            check(extractor.sampleSize <= buffer.capacity()) { "音频数据包超过设备解码器容量" }
        }
        val size = extractor.readSampleData(buffer, 0)
        // readSampleData may itself discover EOS. Do not ask for flags in that case either.
        val observedFlags = if (size >= 0) extractor.sampleFlags else null
        report.sample(timeUs, trackIndex, size.toLong(), observedFlags)
        val flags = decoderInputFlags(timeUs, trackIndex, size, observedFlags ?: 0)
        report.stage(DiagnosticStage.DECODE)
        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            decoder.queueInputBuffer(inputIndex, 0, 0, 0, flags)
            return true
        }
        decoder.queueInputBuffer(inputIndex, 0, size, timeUs, flags)
        extractor.advance()
        return false
    }

    /** Used for local temporary PCM preparation. Caller owns/deletes a successful file. */
    suspend fun decodeToWav(source: AudioSource, file: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val report = diagnostic(source.uri, DiagnosticOutput.LOCAL_ASR_DECODE)
        try {
            currentCoroutineContext().ensureActive()
            decodeWav(source, file, onProgress, report)
            currentCoroutineContext().ensureActive()
            onProgress(1f)
            report.finish(file.length())
        } catch (failure: Throwable) {
            report.fail(diagnosticError(failure))
            file.delete()
            throw failure
        }
    }

    private suspend fun decodeWav(source: AudioSource, file: File, progress: (Float) -> Unit, report: DiagnosticSession) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            open(extractor, source.uri, report)
            describe(extractor, source, report)
            extractor.selectTrack(source.trackIndex)
            val inputFormat = extractor.getTrackFormat(source.trackIndex).apply { preparePcmDecode() }
            val dec = MediaCodec.createDecoderByType(source.mime)
            decoder = dec
            dec.configure(inputFormat, null, null, 0)
            dec.start()
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0)
                output.write(ByteArray(44)) // Header finalized only after a complete decode.
                var inputEnded = false
                var outputEnded = false
                var rate = 0
                var channels = 0
                var bytesWritten = 0L
                val timeline = AudioTimeline()
                var lastActivity = SystemClock.elapsedRealtime()
                val info = MediaCodec.BufferInfo()
                val copyBuffer = ByteArray(32 * 1024)
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    var advanced = false
                    if (!inputEnded) {
                        val index = dec.dequeueInputBuffer(1_000)
                        if (index >= 0) {
                            inputEnded = queueDecoderInput(extractor, dec, index, report)
                            advanced = true
                        }
                    }
                    when (val index = dec.dequeueOutputBuffer(info, 1_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = dec.outputFormat
                            check(format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT) { "设备解码器不支持 16 位 PCM 输出" }
                            val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            check(newChannels in 1..2) { "WAV 暂支持单声道和立体声音轨" }
                            check(rate == 0 || (rate == newRate && channels == newChannels)) { "不支持中途变化的音轨格式" }
                            rate = newRate
                            channels = newChannels
                            advanced = true
                        }
                        else -> if (index >= 0) {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(rate > 0 && channels > 0) { "设备解码器未提供音频格式" }
                                check(bytesWritten + info.size <= 0xFFFF_FFFFL - 36) { "WAV 文件将超过 4 GB，请改用 M4A" }
                                val buffer = checkNotNull(dec.getOutputBuffer(index)).apply {
                                    position(info.offset)
                                    limit(info.offset + info.size)
                                }
                                while (buffer.hasRemaining()) {
                                    currentCoroutineContext().ensureActive()
                                    val length = minOf(buffer.remaining(), copyBuffer.size)
                                    buffer.get(copyBuffer, 0, length)
                                    output.write(copyBuffer, 0, length)
                                    bytesWritten += length
                                }
                                progress(extractionProgress(timeline.relative(info.presentationTimeUs), source.durationUs))
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            dec.releaseOutputBuffer(index, false)
                            advanced = true
                        }
                    }
                    if (advanced) lastActivity = SystemClock.elapsedRealtime()
                    check(SystemClock.elapsedRealtime() - lastActivity < 30_000) { "设备解码器长时间没有响应，请尝试其他视频" }
                }
                check(bytesWritten > 0 && rate > 0 && channels > 0) { "音轨没有可读取的音频数据" }
                check(bytesWritten % (channels * 2) == 0L) { "设备返回了不完整的 PCM 数据" }
                output.seek(0)
                output.write(wavHeader(rate, channels, bytesWritten))
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    /** Decode to PCM16 and encode AAC-LC using the device codecs; no bundled network service. */
    private suspend fun transcode(source: AudioSource, file: File, progress: (Float) -> Unit, report: DiagnosticSession) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            open(extractor, source.uri, report)
            describe(extractor, source, report)
            extractor.selectTrack(source.trackIndex)
            val inputFormat = extractor.getTrackFormat(source.trackIndex)
            inputFormat.preparePcmDecode()
            val dec = MediaCodec.createDecoderByType(source.mime)
            decoder = dec
            dec.configure(inputFormat, null, null, 0)
            dec.start()
            val writer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            var inputEnded = false
            var decoderEnded = false
            var encoderEosSent = false
            var outputEnded = false
            var outputTrack = -1
            var pendingIndex = -1
            var pending: ByteBuffer? = null
            var pendingPts = 0L
            var pendingConsumed = 0
            var pendingEos = false
            var rate = 0
            var channels = 0
            val timeline = AudioTimeline()
            var nextPts = 0L
            var written = 0
            var firstEncodedPts: Long? = null
            var lastEncodedPts = -1L
            var lastActivity = SystemClock.elapsedRealtime()
            val decodedInfo = MediaCodec.BufferInfo()
            val encodedInfo = MediaCodec.BufferInfo()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                var advanced = false
                if (!inputEnded) {
                    val index = dec.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        inputEnded = queueDecoderInput(extractor, dec, index, report)
                        advanced = true
                    }
                }
                if (pendingIndex < 0 && !decoderEnded) {
                    when (val index = dec.dequeueOutputBuffer(decodedInfo, 1_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decodedFormat = dec.outputFormat
                            check(decodedFormat.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT) { "设备解码器不支持 16 位 PCM 输出" }
                            val newRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val newChannels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            check(newChannels in 1..2) { "暂不支持将多声道音轨转为 AAC；请选择单声道或立体声视频" }
                            if (encoder != null) {
                                check(rate == newRate && channels == newChannels) { "不支持中途变化的音轨格式" }
                            } else {
                                rate = newRate
                                channels = newChannels
                                val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                                    setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 128_000 else 192_000)
                                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
                                }
                                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                                    it.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                                    it.start()
                                }
                            }
                            advanced = true
                        }
                        else -> if (index >= 0) {
                            pendingEos = decodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (decodedInfo.size == 0 || decodedInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                dec.releaseOutputBuffer(index, false)
                                decoderEnded = pendingEos
                                check(!decoderEnded || encoder != null) { "音轨没有可读取的音频数据" }
                            } else {
                                check(encoder != null) { "设备解码器未提供音频格式" }
                                pendingIndex = index
                                pending = checkNotNull(dec.getOutputBuffer(index)).apply {
                                    position(decodedInfo.offset)
                                    limit(decodedInfo.offset + decodedInfo.size)
                                }
                                pendingPts = timeline.relative(decodedInfo.presentationTimeUs)
                                pendingConsumed = 0
                            }
                            advanced = true
                        }
                    }
                }
                val enc = encoder
                if (pendingIndex >= 0 && enc != null) {
                    val pcm = checkNotNull(pending)
                    if (pcm.hasRemaining()) {
                        val index = enc.dequeueInputBuffer(1_000)
                        if (index >= 0) {
                            val buffer = checkNotNull(enc.getInputBuffer(index)).apply { clear() }
                            val bytesPerFrame = channels * 2
                            val length = minOf(pcm.remaining(), buffer.remaining()) / bytesPerFrame * bytesPerFrame
                            check(length > 0 && pcm.remaining() % bytesPerFrame == 0) { "设备返回了不完整的 PCM 数据" }
                            val oldLimit = pcm.limit()
                            pcm.limit(pcm.position() + length)
                            buffer.put(pcm)
                            pcm.limit(oldLimit)
                            val pts = pendingPts + pendingConsumed.toLong() / bytesPerFrame * 1_000_000 / rate
                            enc.queueInputBuffer(index, 0, length, pts, 0)
                            pendingConsumed += length
                            nextPts = pendingPts + pendingConsumed.toLong() / bytesPerFrame * 1_000_000 / rate
                            advanced = true
                        }
                    }
                    if (!pcm.hasRemaining()) {
                        dec.releaseOutputBuffer(pendingIndex, false)
                        pendingIndex = -1
                        pending = null
                        decoderEnded = pendingEos
                        advanced = true
                    }
                }
                if (enc != null && decoderEnded && !encoderEosSent) {
                    val index = enc.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        enc.queueInputBuffer(index, 0, 0, nextPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderEosSent = true
                        advanced = true
                    }
                }
                if (enc != null) {
                    when (val index = enc.dequeueOutputBuffer(encodedInfo, 1_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "编码器输出格式重复变化" }
                            outputTrack = writer.addTrack(enc.outputFormat)
                            writer.start()
                            muxerStarted = true
                            advanced = true
                        }
                        else -> if (index >= 0) {
                            if (encodedInfo.size > 0 && encodedInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted) { "编码器未提供音轨格式" }
                                if (firstEncodedPts == null) firstEncodedPts = encodedInfo.presentationTimeUs
                                encodedInfo.presentationTimeUs = (encodedInfo.presentationTimeUs - checkNotNull(firstEncodedPts)).coerceAtLeast(0)
                                check(encodedInfo.presentationTimeUs >= lastEncodedPts) { "设备编码器返回了异常时间戳" }
                                writer.writeSampleData(outputTrack, checkNotNull(enc.getOutputBuffer(index)), encodedInfo)
                                lastEncodedPts = encodedInfo.presentationTimeUs
                                written++
                                progress(extractionProgress(encodedInfo.presentationTimeUs, source.durationUs))
                            }
                            outputEnded = encodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            enc.releaseOutputBuffer(index, false)
                            advanced = true
                        }
                    }
                }
                if (advanced) lastActivity = SystemClock.elapsedRealtime()
                check(SystemClock.elapsedRealtime() - lastActivity < 30_000) { "设备编解码器长时间没有响应，请尝试其他视频" }
            }
            check(written > 0) { "音轨没有可读取的音频数据" }
            writer.stop()
            muxerStarted = false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}

internal fun exportBaseName(name: String): String = name.substringBeforeLast('.', name)
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(64).ifBlank { "音频" }

/** Reserve 100% for a successfully finalized file, never for a merely processed packet. */
internal fun extractionProgress(timeUs: Long, durationUs: Long): Float =
    if (durationUs <= 0) 0f else (timeUs.toDouble() / durationUs).coerceIn(0.0, 0.99).toFloat()

private fun MediaFormat.intOr(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback
private fun MediaFormat.longOr(key: String, fallback: Long): Long = if (containsKey(key)) getLong(key) else fallback

/** Standard little-endian RIFF/WAVE, PCM signed 16-bit interleaved. */
internal fun wavHeader(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
    require(sampleRate in 1..384_000 && channels in 1..2)
    require(dataBytes in 0..(0xFFFF_FFFFL - 36) && dataBytes % (channels * 2) == 0L)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt((36 + dataBytes).toInt())
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1.toShort())
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(sampleRate * channels * 2)
        putShort((channels * 2).toShort())
        putShort(16.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataBytes.toInt())
    }.array()
}

/** Only real, readable samples may be checked for encryption or partial-frame flags. */
@Suppress("UNUSED_PARAMETER") // A valid sample can have any signed PTS, including -1; time never identifies EOS.
internal fun decoderInputFlags(timeUs: Long, trackIndex: Int, sampleBytes: Int, sampleFlags: Int): Int {
    if (trackIndex < 0 || sampleBytes < 0) return MediaCodec.BUFFER_FLAG_END_OF_STREAM
    validateReadableSampleFlags(sampleFlags)
    return if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0
}

private fun validateReadableSampleFlags(sampleFlags: Int) {
    check(sampleFlags >= 0) { "无法读取当前音频数据包的标志" }
    check(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "不支持受保护或加密的音轨" }
}

/**
 * The observed Android 9 MP4 extractor convert edit-list media_time with the movie timescale
 * instead of the audio-track timescale (observed 45158 delay samples on a 2 s AAC file).
 * Match AOSP's bounded-delay guard on the verified API 28 release only. Preserve valid
 * gapless metadata and do not change compressed AAC remux output.
 * See frameworks/av a4af050bab773a75c799bb9cbd886e3b83655daf MPEG4Extractor.cpp.
 */
internal fun shouldIgnoreLegacyAacEditList(sdkInt: Int, mime: String?, encoderDelay: Int?): Boolean =
    sdkInt == 28 && mime == MediaFormat.MIMETYPE_AUDIO_AAC && encoderDelay != null &&
        (encoderDelay < 0 || encoderDelay > 4096)

private fun MediaFormat.preparePcmDecode() {
    val delay = if (containsKey(MediaFormat.KEY_ENCODER_DELAY)) getInteger(MediaFormat.KEY_ENCODER_DELAY) else null
    if (shouldIgnoreLegacyAacEditList(Build.VERSION.SDK_INT, getString(MediaFormat.KEY_MIME), delay)) {
        // API 28 has no removeKey(); zero disables trimming from this invalid edit list.
        setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
        setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
    }
    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
}

/** Anchor exactly once, including when the first timestamp is negative or exactly -1. */
internal class AudioTimeline {
    private var firstPtsUs: Long? = null
    fun relative(timeUs: Long): Long {
        val origin = firstPtsUs ?: timeUs.also { firstPtsUs = it }
        return Math.subtractExact(timeUs, origin).coerceAtLeast(0)
    }
}

/** Never put a raw exception or its message in a copied diagnostic. */
private fun diagnosticError(failure: Throwable): DiagnosticError = when {
    failure is kotlinx.coroutines.CancellationException -> DiagnosticError.CANCELLED
    failure is MediaCodec.CodecException -> DiagnosticError.CODEC
    failure is ArithmeticException -> DiagnosticError.INVALID_TIMESTAMPS
    failure.message == "音轨没有可读取的音频数据" || failure.message == "未能生成音频文件" -> DiagnosticError.NO_AUDIO_DATA
    failure.message == "不支持受保护或加密的音轨" -> DiagnosticError.PROTECTED_AUDIO
    failure.message == "无法读取当前音频数据包的标志" -> DiagnosticError.INVALID_SAMPLE_FLAGS
    failure.message == "这个视频没有可提取的音轨。请选择带声音的视频。" -> DiagnosticError.NO_AUDIO_TRACK
    failure.message == "音轨时间戳异常，无法无损提取" -> DiagnosticError.INVALID_TIMESTAMPS
    failure.message == "设备编解码器长时间没有响应，请尝试其他视频" || failure.message == "设备解码器长时间没有响应，请尝试其他视频" -> DiagnosticError.CODEC_TIMEOUT
    failure is java.io.IOException -> DiagnosticError.SOURCE_READ
    else -> DiagnosticError.UNKNOWN
}
