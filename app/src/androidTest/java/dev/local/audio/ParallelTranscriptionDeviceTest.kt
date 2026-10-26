package dev.local.audio

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ParallelTranscriptionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun wav(seconds: Int): File {
        val bytes = 8000 * seconds * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes).array()
        return File(File(context.filesDir, "exports").apply { mkdirs() }, "parallel-${UUID.randomUUID()}.wav").apply {
            outputStream().use { out -> out.write(header); val data = ByteArray(bytes); UUID.randomUUID().toString().toByteArray().copyInto(data); out.write(data) }
        }
    }
    private class Server(expected: Int = 3, failMiddle: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0)
        val url = URL("http://127.0.0.1:${server.localPort}/v1/speech_to_text")
        val received = CountDownLatch(expected)
        val release = CountDownLatch(1)
        val lengths = CopyOnWriteArrayList<Int>()
        private val failures = AtomicBoolean(failMiddle)
        private val active = AtomicInteger()
        val peak = AtomicInteger()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val acceptor = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    workers += thread(isDaemon = true) {
                        try {
                            socket.use {
                                socket.soTimeout = 20000
                                val input = socket.getInputStream().buffered()
                                val header = StringBuilder()
                                while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0); header.append(b.toChar()) }
                                val size = Regex("(?i)Content-Length: (\\d+)").find(header)!!.groupValues[1].toInt()
                                val first = ByteArray(minOf(2048, size)); var n = 0
                                while (n < first.size) { val count = input.read(first, n, first.size - n); check(count > 0); n += count }
                                val riff = String(first, Charsets.ISO_8859_1).indexOf("RIFF")
                                check(riff >= 0)
                                val seconds = ByteBuffer.wrap(first, riff + 40, 4).order(ByteOrder.LITTLE_ENDIAN).int / 16000
                                var left = size - first.size; val buffer = ByteArray(65536)
                                while (left > 0) { val count = input.read(buffer, 0, minOf(buffer.size, left)); check(count > 0); left -= count }
                                lengths += seconds
                                val current = active.incrementAndGet(); peak.updateAndGet { maxOf(it, current) }
                                received.countDown(); release.await(25, TimeUnit.SECONDS)
                                if (seconds == 449) Thread.sleep(350) // Force out-of-order completion.
                                val code = if (seconds == 450 && failures.compareAndSet(true, false)) 500 else 200
                                val text = when (seconds) { 449 -> "first"; 450 -> "middle"; 205 -> "last"; else -> "background" }
                                val body = """{"text":"$text","segments":[{"start":2.1,"end":2.5,"text":"$text"}]}""".toByteArray()
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                                }
                            }
                        } catch (_: Exception) { } finally { active.decrementAndGet() }
                    }
                }
            } catch (_: Exception) { }
        }
        override fun close() { release.countDown(); server.close(); acceptor.join(1000); workers.forEach { it.join(1000) } }
    }
    private fun engine(server: Server) = TranscriptionEngine(context) { server.url.openConnection() as HttpURLConnection }
    @Test fun parallelOutOfOrderCommitsOnlyFullText() = runBlocking<Unit> {
        val file = wav(1100)
        Server().use { server ->
            val engine = engine(server)
            val job = async(Dispatchers.IO) { engine.transcribe(file, "test-only") {} }
            try {
                assertTrue("All three uploads must arrive before any response", withContext(Dispatchers.IO) { server.received.await(15, TimeUnit.SECONDS) })
                assertEquals(3, server.peak.get())
                assertNull("No partial deliverable", engine.cached(file))
                server.release.countDown()
                val result = withTimeout(15000) { job.await() }
                assertEquals("first\nmiddle\nlast", result.text)
                assertTrue(result.complete)
                assertEquals(3, result.segments.size)
                assertTrue(result.segments.last().start > 896)
            } finally { job.cancelAndJoin(); engine.deleteLocalTranscript(file); file.delete() }
        }
    }
    @Test fun failureWaitsForSiblingsAndRetryOnlyMissingSlice() = runBlocking<Unit> {
        val file = wav(1100)
        Server(failMiddle = true).use { server ->
            val engine = engine(server)
            val job = async(Dispatchers.IO) { runCatching { engine.transcribe(file, "test-only") {} } }
            try {
                assertTrue(withContext(Dispatchers.IO) { server.received.await(15, TimeUnit.SECONDS) })
                server.release.countDown()
                assertTrue(withTimeout(15000) { job.await() }.isFailure)
                assertNull(engine.cached(file))
                val result = engine.transcribe(file, "test-only") {}
                assertEquals("first\nmiddle\nlast", result.text)
                assertEquals(listOf(205, 449, 450, 450), server.lengths.sorted())
            } finally { job.cancelAndJoin(); engine.deleteLocalTranscript(file); file.delete() }
        }
    }
    @Test fun serviceContinuesOnHomeAndReturnsCompleteResult() = backgroundServiceCheck(grantNotification = true, cancel = false)
    @Test fun notificationDeniedStillFinishesWithScreenOff() = backgroundServiceCheck(grantNotification = false, cancel = false)
    @Test fun cancelBackgroundServiceRemovesOngoingNotification() = backgroundServiceCheck(grantNotification = true, cancel = true)

    private fun backgroundServiceCheck(grantNotification: Boolean, cancel: Boolean) = runBlocking<Unit> {
        val file = wav(5)
        val keys = ApiKeyStore(context)
        val oldKey = keys.read()
        Server(expected = 1).use { server ->
            try {
                keys.save("local-test-only")
                TranscriptionService.engineFactory = { engine(server) }
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                if (grantNotification) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
                else check(context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) { "Run denied test separately after adb pm revoke, outside instrumentation" }
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(TranscriptionService.FILE, file.name))
                delay(1500)
                instrumentation.runOnMainSync { TranscriptionService.start(context, file) }
                assertTrue(withContext(Dispatchers.IO) { server.received.await(15, TimeUnit.SECONDS) })
                delay(300)
                val recognizing = TranscriptionTasks.states.value[file.canonicalPath]!!
                assertEquals("正在识别", recognizing.status)
                assertNull("One slice must show indeterminate recognition", recognizing.progress)
                if (grantNotification && !cancel) {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    File(context.getExternalFilesDir(null), "recognition-progress.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                delay(2000)
                assertTrue("Service must survive leaving Activity", TranscriptionTasks.isBusy(file))
                if (grantNotification) {
                    val ongoing = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == TranscriptionService.ID }.notification
                    assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
                    assertEquals(Notification.CATEGORY_PROGRESS, ongoing.category)
                    assertTrue(ongoing.extras.getBoolean("android.requestPromotedOngoing"))
                    if (!cancel) {
                        instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
                        delay(500)
                        val screenshot = instrumentation.uiAutomation.takeScreenshot()
                        File(context.getExternalFilesDir(null), "background-notification.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        screenshot.recycle()
                        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
                    }
                } else {
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
                    delay(1000)
                    assertTrue(TranscriptionTasks.isBusy(file))
                }
                if (cancel) {
                    instrumentation.runOnMainSync { TranscriptionService.cancel(context) }
                    withTimeout(5000) { while (TranscriptionTasks.isBusy(file)) delay(100) }
                    assertNull(TranscriptionEngine(context).cached(file))
                } else {
                    server.release.countDown()
                    withTimeout(15000) { while (TranscriptionTasks.isBusy(file)) delay(100) }
                    assertEquals("background", TranscriptionEngine(context).cached(file)!!.text)
                }
                delay(300)
                assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == TranscriptionService.ID })
            } finally {
                instrumentation.runOnMainSync { if (TranscriptionTasks.isBusy(file)) TranscriptionService.cancel(context) }
                withTimeoutOrNull(5000) { while (TranscriptionTasks.isBusy(file)) delay(100) }
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                TranscriptionService.engineFactory = { TranscriptionEngine(it) }
                if (oldKey == null) keys.clear() else keys.save(oldKey)
                engine(server).deleteLocalTranscript(file); TranscriptionTasks.forget(context, file); file.delete()
            }
        }
    }
}
