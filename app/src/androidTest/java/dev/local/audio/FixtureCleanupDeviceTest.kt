package dev.local.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Removes only artifacts of an interrupted local HTTP fixture test; never real credentials. */
@RunWith(AndroidJUnit4::class)
class FixtureCleanupDeviceTest {
    @Test fun removeInterruptedMockFixtures() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keys = ApiKeyStore(context)
        if (keys.read() == "local-test-only") keys.clear()
        val fixture = Regex("parallel-[a-f0-9-]{36}\\.wav")
        File(context.filesDir, "exports").listFiles()?.filter { fixture.matches(it.name) }?.forEach {
            check(!TranscriptionTasks.isBusy(it))
            TranscriptionEngine(context).deleteLocalTranscript(it)
            TranscriptionTasks.forget(context, it)
            check(it.delete())
        }
        context.getSystemService(android.app.NotificationManager::class.java).cancel(TranscriptionService.ID + 1)
    }
}
