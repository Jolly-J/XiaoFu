package dev.local.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ApiKeyStoreDeviceTest {
    @Test fun encryptReplaceAndDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ApiKeyStore(context)
        store.clear()
        try {
            assertNull(store.read())
            store.save("unit-test-not-a-real-key-123")
            assertEquals("unit-test-not-a-real-key-123", store.read())
            val blob = File(context.noBackupFilesDir, "minimax-key.bin").readBytes()
            assertFalse(blob.toString(Charsets.ISO_8859_1).contains("unit-test-not-a-real-key-123"))
            store.save("replacement-fake-key")
            assertEquals("replacement-fake-key", store.read())
            store.clear()
            assertNull(store.read())
        } finally { store.clear() }
    }
}
