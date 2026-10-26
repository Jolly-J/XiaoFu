package dev.local.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Real device codecs, real locally generated media; no mocked extractor. */
@RunWith(AndroidJUnit4::class)
class AudioEngineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val engine = AudioEngine(context)
    private fun input(name: String): Uri {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { src -> file.outputStream().use { src.copyTo(it) } }
        return Uri.fromFile(file)
    }
    @Test fun aacRemuxPreservesEveryPacket() = runBlocking {
        val uri = input("aac-video.mp4")
        val source = engine.inspect(uri)
        assertEquals("audio/mp4a-latm", source.mime)
        assertEquals(44100, source.sampleRate)
        val progress = mutableListOf<Float>()
        val result = engine.extract(source) { progress.add(it) }
        try {
            assertEquals(1f, progress.last())
            assertTrue(progress.zipWithNext().all { (a,b) -> b >= a })
            assertTrue(progress.all { it in 0f..1f })
            val before = packets(uri)
            val after = packets(Uri.fromFile(result.file))
            assertEquals(before.size, after.size)
            before.zip(after).forEach { (a,b) -> assertArrayEquals(a,b) }
        } finally { result.file.delete() }
    }
    @Test fun publicSintelAudioRemuxesWithoutPacketLoss() = runBlocking {
        val uri = input("public-sintel.mp4")
        val source = engine.inspect(uri)
        assertTrue(source.durationUs > 50_000_000L)
        val result = engine.extract(source) {}
        try {
            val before = packets(uri)
            val after = packets(Uri.fromFile(result.file))
            assertEquals(before.size, after.size)
            before.zip(after).forEach { (a,b) -> assertArrayEquals(a,b) }
        } finally { result.file.delete() }
    }
    @Test fun hevcVideoContainerExtractsAudio() = runBlocking {
        val source = engine.inspect(input("hevc-aac-video.mp4"))
        val result = engine.extract(source) {}
        try {
            assertEquals("audio/mp4a-latm", engine.inspect(Uri.fromFile(result.file)).mime)
            assertTrue(packets(Uri.fromFile(result.file)).isNotEmpty())
        } finally { result.file.delete() }
    }
    @Test fun vorbisTranscodesToAac() = runBlocking {
        val source = engine.inspect(input("vorbis-video.webm"))
        assertEquals("audio/vorbis", source.mime)
        val result = engine.extract(source) {}
        try {
            val out = engine.inspect(Uri.fromFile(result.file))
            assertEquals("audio/mp4a-latm", out.mime)
            assertEquals(48000, out.sampleRate)
            assertTrue(out.durationUs in 1_800_000L..2_300_000L)
            assertTrue(packets(Uri.fromFile(result.file)).isNotEmpty())
        } finally { result.file.delete() }
    }
    @Test fun aacDecodesToValidWav() = runBlocking {
        val source = engine.inspect(input("aac-video.mp4"))
        val result = engine.extract(source, OutputFormat.WAV) {}
        try {
            val bytes = result.file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertTrue(bytes.size > 170_000)
            assertEquals("audio/wav", result.mime)
        } finally { result.file.delete() }
    }
    @Test fun vorbisDecodesToWav() = runBlocking {
        val source = engine.inspect(input("vorbis-video.webm"))
        val result = engine.extract(source, OutputFormat.WAV) {}
        try {
            val bytes = result.file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertTrue(bytes.size > 180_000)
        } finally { result.file.delete() }
    }
    @Test fun noAudioFailsClearly() = runBlocking {
        try { engine.inspect(input("silent-video.mp4")); fail("Expected no-audio failure") }
        catch(e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("音轨")) }
    }
    @Test fun cancellationRemovesPartialOutput() = runBlocking {
        val source = engine.inspect(input("aac-video.mp4"))
        val directory = File(context.filesDir,"exports")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        val job = launch {
            val self = currentCoroutineContext().job
            engine.extract(source) { self.cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
    }
    private fun packets(uri: Uri): List<ByteArray> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            extractor.selectTrack(track)
            val packets = mutableListOf<ByteArray>()
            val buffer = ByteBuffer.allocate(1 shl 20)
            while(true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer,0)
                if(size<0) break
                val bytes = ByteArray(size)
                buffer.position(0); buffer.get(bytes)
                packets.add(bytes)
                extractor.advance()
            }
            return packets
        } finally { extractor.release() }
    }
}
