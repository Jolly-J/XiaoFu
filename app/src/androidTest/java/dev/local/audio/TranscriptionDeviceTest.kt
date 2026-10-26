package dev.local.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class TranscriptionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun wav(seconds: Int = 1): File {
        val count = 8000 * seconds * 2
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(count+36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count).array()
        return File(context.cacheDir,"test-${UUID.randomUUID()}.wav").apply { outputStream().use { out -> out.write(header); val data=ByteArray(count); UUID.randomUUID().toString().toByteArray().copyInto(data); out.write(data) } }
    }
    private class MockHttp(private val responses:List<Pair<Int,String>>, private val pause: CountDownLatch?=null): AutoCloseable {
        private val server=ServerSocket(0)
        val requests=CopyOnWriteArrayList<String>()
        val received=CountDownLatch(1)
        val url=URL("http://127.0.0.1:${server.localPort}/v1/speech_to_text")
        private val worker=thread(isDaemon=true) {
            try { responses.forEach { (code, body) ->
                server.accept().use { socket ->
                    socket.soTimeout=10000
                    val input=socket.getInputStream().buffered()
                    val header=StringBuilder()
                    while(!header.endsWith("\r\n\r\n")) { val b=input.read(); if(b<0)error("EOF");header.append(b.toChar()) }
                    val length=Regex("(?i)Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    val bytes=ByteArray(length);var read=0
                    while(read<length) { val n=input.read(bytes,read,length-read);if(n<0)error("EOF body");read+=n }
                    requests.add(header.toString()+String(bytes,Charsets.ISO_8859_1));received.countDown()
                    pause?.await(10,TimeUnit.SECONDS)
                    val output=socket.getOutputStream();val payload=body.toByteArray()
                    output.write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray());output.write(payload);output.flush()
                }
            } } catch (_: Exception) { /* Expected when cancellation closes a socket. */ }
        }
        override fun close() { pause?.countDown();server.close();worker.join(1000) }
    }
    private fun engine(mock:MockHttp)=TranscriptionEngine(context) { mock.url.openConnection() as HttpURLConnection }
    @Test fun multipartAndTimestampMerge() = runBlocking<Unit> {
        val file=wav()
        MockHttp(listOf(200 to """{"text":"Hello world","segments":[{"start":0.1,"end":0.3,"text":"Hello"},{"start":0.4,"end":0.6,"text":"world"}]}""")).use { mock ->
            val result=engine(mock).transcribe(file,"test-only-key") {}
            assertEquals("Hello world",result.text)
            val request=mock.requests.single()
            assertTrue(request.contains("Authorization: Bearer test-only-key",ignoreCase=true))
            assertTrue(request.contains("multipart/form-data; boundary="))
            listOf("model","response_format","timestamp_level","stream","file").forEach { assertTrue(request.contains("name=\"$it\"")) }
            assertTrue(request.contains("asr-1.0"));assertTrue(request.contains("RIFF"))
            assertEquals(2,result.segments.size)
            assertTrue(engine(mock).cached(file)!!.complete)
            engine(mock).deleteLocalTranscript(file)
            assertNull(engine(mock).cached(file))
            assertTrue(file.exists())
        }
        file.delete()
    }
    @Test fun m4aAudioDecodesBeforeMockUpload() = runBlocking<Unit> {
        val video=File(context.cacheDir,"asr-combination-${UUID.randomUUID()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("aac-video.mp4").use { source -> video.outputStream().use { source.copyTo(it) } }
        val audioEngine=AudioEngine(context)
        val source=audioEngine.inspect(android.net.Uri.fromFile(video))
        val audio=audioEngine.extract(source) {}.file
        try {
            MockHttp(listOf(200 to """{"text":"decoded audio"}""")).use { mock ->
                val result=engine(mock).transcribe(audio,"test-only-key") {}
                assertEquals("decoded audio",result.text)
                assertTrue(mock.requests.single().contains("RIFF"))
                engine(mock).deleteLocalTranscript(audio)
            }
        } finally { audio.delete();video.delete() }
    }
    @Test fun authAndRateLimitErrorsAreActionable() = runBlocking<Unit> {
        for((code,message) in listOf(401 to "API Key",429 to "额度")) {
            val file=wav()
            MockHttp(listOf(code to "{}")).use { mock ->
                try { engine(mock).transcribe(file,"test-only-key") {};fail("Expected HTTP failure") }
                catch(e:IllegalStateException) { assertTrue(e.message.orEmpty().contains(message)) }
            }
            file.delete()
        }
    }
    @Test fun failedSegmentCanRetry() = runBlocking<Unit> {
        val file=wav()
        MockHttp(listOf(500 to "{}",200 to """{"text":"retry works"}""")).use { mock ->
            val engine=engine(mock)
            try { engine.transcribe(file,"test-only-key") {};fail("Expected transient failure") } catch(_:IllegalStateException) {}
            assertEquals("retry works",engine.transcribe(file,"test-only-key") {}.text)
            assertEquals(2,mock.requests.size)
        }
        file.delete()
    }
    @Test fun cancellationDisconnectsWaitingRequest() = runBlocking<Unit> {
        val file=wav();val latch=CountDownLatch(1)
        MockHttp(listOf(200 to """{"text":"late"}"""),latch).use { mock ->
            val job=launch(Dispatchers.IO) { engine(mock).transcribe(file,"test-only-key") {} }
            assertTrue(withContext(Dispatchers.IO) { mock.received.await(20,TimeUnit.SECONDS) })
            withTimeout(5000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
        file.delete()
    }
}
