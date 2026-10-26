package dev.local.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Synthetic compatibility matrix. Run long972s separately after pushing its fixture. */
@RunWith(AndroidJUnit4::class)
class CompatibilityMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val engine = AudioEngine(context)
    @get:Rule val testName = TestName()
    @After fun persistDiagnostics() {
        // Only the deliberately allowlisted, privacy-filtered summary: no exception text or URIs.
        val directory = File(context.filesDir, "compat-diagnostics").apply { mkdirs() }
        File(directory, "${testName.methodName}.json").writeText(Diagnostics.copySummary())
    }
    private fun asset(name: String): Uri {
        val file = File(context.cacheDir, "compat-$name")
        instrumentation.context.assets.open("compat/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }
    private fun provider(mode: String) = Uri.parse("content://${CompatibilityProvider.AUTHORITY}/$mode")
    private data class Packets(val count: Int, val digest: String, val firstTime: Long, val lastTime: Long)
    private fun packets(uri: Uri): Packets {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bytes = ByteArray(buffer.capacity())
            val digest = MessageDigest.getInstance("SHA-256")
            var first: Long? = null; var last = 0L; var count = 0
            while (extractor.sampleTrackIndex >= 0) {
                val time = extractor.sampleTime
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (count < 3) Log.i("CompatMatrix", "sdk=${Build.VERSION.SDK_INT} sample=$count time=$time track=${extractor.sampleTrackIndex} size=$size flags=${extractor.sampleFlags}")
                if (first == null) first = time
                last = time
                buffer.position(0); buffer.get(bytes, 0, size)
                digest.update(ByteBuffer.allocate(4).putInt(size).array())
                digest.update(bytes, 0, size)
                count++
                if (!extractor.advance()) break
            }
            Log.i("CompatMatrix", "sdk=${Build.VERSION.SDK_INT} EOS time=${extractor.sampleTime} track=${extractor.sampleTrackIndex} size=${if (Build.VERSION.SDK_INT >= 28) extractor.sampleSize else -2} flags=${extractor.sampleFlags} count=$count")
            return Packets(count, digest.digest().joinToString("") { "%02x".format(it) }, first ?: -1, last)
        } finally { extractor.release() }
    }
    private suspend fun verifyOutputs(uri: Uri, expectedSeconds: Double, lossless: Boolean, expectedChannels: Int = 2, packetReference: Uri = uri, expectedSourceChannels: Int = expectedChannels, expectedDecodedRate: Int? = null) {
        val source = engine.inspect(uri)
        assertTrue("Expected an audio track", source.trackIndex >= 0)
        assertEquals(expectedSourceChannels, source.channelCount)
        val original = if (lossless) packets(packetReference) else null
        if (original != null) assertTrue("Fixture must contain audio packets", original.count > 0)
        val m4a = engine.extract(source, OutputFormat.M4A) {}
        try {
            val result = engine.inspect(Uri.fromFile(m4a.file))
            assertEquals("audio/mp4a-latm", result.mime)
            val output = packets(Uri.fromFile(m4a.file))
            assertTrue(output.count > 0)
            assertTrue("Output starts before zero: ${output.firstTime}", output.firstTime >= 0)
            if (original != null) {
                assertEquals("No AAC packet may be dropped, including negative-PTS priming", original.count, output.count)
                assertEquals(original.digest, output.digest)
                assertTrue("Time differences must be preserved within two PCM frames", kotlin.math.abs((original.lastTime - original.firstTime) - (output.lastTime - output.firstTime)) <= 2_000_000L / source.sampleRate.coerceAtLeast(1))
            }
            assertEquals(expectedSeconds, result.durationUs / 1_000_000.0, 0.20)
        } finally { m4a.file.delete() }
        val wav = engine.extract(source, OutputFormat.WAV) {}
        try {
            RandomAccessFile(wav.file, "r").use { file ->
                val header = ByteArray(44); file.readFully(header)
                assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
                val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val rate = data.getInt(24)
                if (expectedDecodedRate != null) assertEquals(expectedDecodedRate.toInt(), rate)
                val channels = data.getShort(22).toInt()
                assertEquals(expectedChannels, channels)
                val pcmBytes = data.getInt(40).toLong() and 0xffffffffL
                assertEquals(wav.file.length() - 44, pcmBytes)
                val seconds = pcmBytes.toDouble() / (rate * channels * 2)
                Log.i("CompatMatrix", "WAV rate=$rate channels=$channels bytes=$pcmBytes seconds=$seconds expected=$expectedSeconds")
                assertEquals("WAV must not truncate priming metadata into audible content", expectedSeconds, seconds, 0.20)
            }
        } finally { wav.file.delete() }
    }
    @Test fun negativeEditListPrimingIsPreserved() = runBlocking<Unit> { verifyOutputs(asset("aac-editlist-default.mp4"), 2.0, true, 1) }
    @Test fun negativeOneMicrosecondIsNotEos() = runBlocking<Unit> {
        val uri = asset("aac-first-minus-one-us.mp4")
        val original = packets(uri)
        assertTrue(original.count > 0)
        if (Build.VERSION.SDK_INT >= 35) assertEquals("This fixture is specifically a valid -1us first sample", -1L, original.firstTime)
        verifyOutputs(uri, 2.0, true, 1)
    }
    @Test fun nonnegativeControl() = runBlocking<Unit> { verifyOutputs(asset("aac-nonnegative-control.mp4"), 2.0, true, 1) }
    @Test fun genuinePlatformHeAac() = runBlocking<Unit> { verifyOutputs(asset("platform-he-profile-5.mp4"), 8.0, true) }
    @Test fun genuinePlatformHeAacV2() = runBlocking<Unit> { verifyOutputs(asset("platform-he-profile-29.mp4"), 8.0, true, expectedSourceChannels = 1, expectedDecodedRate = 44100) }
    @Test fun aacLcH264() = runBlocking<Unit> { verifyOutputs(asset("lc-h264.mp4"), 8.0, true) }
    @Test fun fragmentedMp4() = runBlocking<Unit> { verifyOutputs(asset("fragmented.mp4"), 8.0, true) }
    @Test fun movContainer() = runBlocking<Unit> { verifyOutputs(asset("lc.mov"), 8.0, true) }
    @Test fun hevcContainer() = runBlocking<Unit> { verifyOutputs(asset("lc-hevc.mp4"), 8.0, true) }
    @Test fun multiAudioTrackSelectsFirstDeterministically() = runBlocking<Unit> {
        val uri = asset("multitrack.mp4")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            assertEquals(2, (0 until extractor.trackCount).count { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
        } finally { extractor.release() }
        val source = engine.inspect(uri)
        assertEquals(44100, source.sampleRate)
        verifyOutputs(uri, 8.0, true)
    }
    @Test fun opusWebm() = runBlocking<Unit> { verifyOutputs(asset("opus.webm"), 8.0, false) }
    @Test fun mp3TrackInMp4() = runBlocking<Unit> { verifyOutputs(asset("mp3-track.mp4"), 8.0, false) }
    @Test fun providerKnownLength() = runBlocking<Unit> { verifyOutputs(provider("known"), 8.0, true) }
    @Test fun providerNonzeroOffsetKnownLength() = runBlocking<Unit> { verifyOutputs(provider("offset"), 8.0, true) }
    @Test fun providerUnknownLengthZeroOffset() = runBlocking<Unit> { verifyOutputs(provider("unknown"), 8.0, true) }
    @Test fun providerNonSeekablePipe() = runBlocking<Unit> { verifyOutputs(provider("pipe"), 8.0, false); assertNoStagingFiles() }
    private fun assertNoStagingFiles() {
        val files = File(context.cacheDir, "source-staging").listFiles().orEmpty()
        assertTrue("Source staging must be empty after success/failure/cancellation", files.isEmpty())
    }
    @Test fun pipeOffsetKnownLengthPreservesOnlyDeclaredMedia() = runBlocking<Unit> {
        verifyOutputs(provider("pipe-offset"), 8.0, true, packetReference = asset("lc-h264.mp4"))
        assertNoStagingFiles()
    }
    @Test fun pipePrematureEofFailsAndCleansStaging() = runBlocking<Unit> {
        assertNoStagingFiles()
        var failure: Exception? = null
        try { engine.inspect(provider("pipe-truncated")) } catch (expected: Exception) { failure = expected }
        assertNotNull("EOF before declared AFD length must fail, even if received prefix is valid media", failure)
        assertNoStagingFiles()
    }
    @Test fun emptyPipeFailsAndCleansStaging() = runBlocking<Unit> {
        assertNoStagingFiles()
        var failure: Exception? = null
        try { engine.inspect(provider("empty")) } catch (expected: Exception) { failure = expected }
        assertNotNull("An empty pipe must fail rather than return an empty source", failure)
        assertFalse("An empty stream is not DRM", failure?.message?.contains("加密") == true)
        assertNoStagingFiles()
    }
    @Test fun reliablePipeErrorIsNotSuccessAndCleansStaging() = runBlocking<Unit> {
        assertNoStagingFiles()
        var failure: Exception? = null
        try { engine.inspect(provider("reliable-error")) } catch (expected: Exception) { failure = expected }
        assertNotNull("Provider-reported failure must not be treated as successful EOF even after valid media bytes", failure)
        assertNoStagingFiles()
    }
    @Test fun stalledPipeCancellationIsPromptAndCleansStaging() = runBlocking<Unit> {
        assertNoStagingFiles()
        // Release even a broken uncancellable read after five seconds: fail timing, never hang the suite.
        val watchdog = Thread {
            try {
                Thread.sleep(5000)
                context.contentResolver.call(provider("stall"), "close-stall", null, null)
            } catch (_: InterruptedException) { /* Normal cleanup stopped the watchdog. */ }
        }.apply { isDaemon = true; start() }
        val started = android.os.SystemClock.elapsedRealtime()
        var timedOut = false
        try {
            try { withTimeout(1000) { engine.inspect(provider("stall")) } }
            catch (_: TimeoutCancellationException) { timedOut = true }
            assertTrue("Stalled stream must remain cancellable", timedOut)
            assertTrue("Cancelling a blocked source must finish within 3 seconds", android.os.SystemClock.elapsedRealtime() - started < 3000)
            assertNoStagingFiles()
        } finally {
            watchdog.interrupt()
            context.contentResolver.call(provider("stall"), "close-stall", null, null)
        }
    }
    @Test fun long972sStereo44100() = runBlocking<Unit> {
        val file = File(context.cacheDir, "compat-long-972s.mp4")
        assumeTrue("Runner must push compatibility-tests/long-media/long-972s.mp4 into target cache/compat-long-972s.mp4 before this explicit long test", file.isFile)
        val source = engine.inspect(Uri.fromFile(file))
        assertEquals(44100, source.sampleRate)
        verifyOutputs(Uri.fromFile(file), 972.0, true)
    }
}
