package dev.local.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.thread

class DiagnosticsTest {
    @Test fun validNegativeTimestampsAreNotEosOrDrm() {
        val store = DiagnosticStore()
        val run = store.begin("1.0.2", 35, "content", DiagnosticOutput.WAV)
        run.source("audio/mp4a-latm", 2, 1, 44100, 2, 972000000)
        run.sample(-23220, 1, 278, 1)
        run.sample(-1, 1, 270, 1)
        val report = store.copySummary()
        assertTrue(report.contains("\"validSamples\": 2"))
        assertTrue(report.contains("\"negativePtsSamples\": 2"))
        assertTrue(report.contains("\"eosObserved\": false"))
        assertTrue(report.contains("\"encryptedValidSamples\": 0"))
        assertTrue(report.contains("\"firstSampleTimeUs\": -23220"))
    }
    @Test fun eosSentinelDoesNotCountAsEncryptedSample() {
        val store = DiagnosticStore()
        val run = store.begin("1.0.2", 35, "file", DiagnosticOutput.M4A)
        run.sample(0, 1, 20, 1)
        run.sample(-1, -1, -1, -1)
        run.fail(DiagnosticError.NO_AUDIO_DATA)
        val report = store.copySummary()
        assertTrue(report.contains("\"validSamples\": 1"))
        assertTrue(report.contains("\"eosObserved\": true"))
        assertTrue(report.contains("\"encryptedValidSamples\": 0"))
        assertTrue(report.contains("\"errorCode\": \"NO_AUDIO_DATA\""))
    }
    @Test fun actualEncryptedSamplesAreCounted() {
        val store = DiagnosticStore()
        store.begin("1.0.2", 35, "content", DiagnosticOutput.M4A).sample(0, 0, 20, 2)
        assertTrue(store.copySummary().contains("\"encryptedValidSamples\": 1"))
    }
    @Test fun arbitraryStringsNeverLeak() {
        val secret = "sk-test-secret-not-a-real-key\"\nfile:///private/MyAudio.mp4"
        val store = DiagnosticStore()
        val run = store.begin(secret, 35, secret, DiagnosticOutput.WAV)
        run.source(secret, 2, 1, 44100, 2, 10)
        val text = store.copySummary()
        assertFalse(text.contains(secret))
        listOf("sk-test", "private", "MyAudio", "file:///", "\\nfile").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("\"appVersion\": \"unknown\""))
        assertTrue(text.contains("\"sourceScheme\": \"other\""))
        assertTrue(text.contains("\"audioMime\": \"other\""))
    }
    @Test fun storeIsBoundedAndStaleHandlesCannotCorruptNewRuns() {
        val store = DiagnosticStore(capacity = 2)
        val evicted = store.begin("1.0.2", 35, "file", DiagnosticOutput.SOURCE_SCAN)
        store.begin("1.0.2", 35, "file", DiagnosticOutput.M4A)
        store.begin("1.0.2", 35, "file", DiagnosticOutput.WAV)
        evicted.fail(DiagnosticError.UNKNOWN)
        val text = store.copySummary()
        assertFalse(text.contains("\"outputFormat\": \"SOURCE_SCAN\""))
        assertFalse(text.contains("UNKNOWN"))
        assertEquals(2, Regex("\\\"run\\\":").findAll(text).count())
        assertTrue(text.indexOf("WAV") < text.indexOf("M4A"))
    }
    @Test fun terminalResultsAreImmutableAndElapsedTimeStable() {
        var now = 1_000_000L
        val store = DiagnosticStore(clockNanos = { now })
        val run = store.begin("1.0.2", 35, "file", DiagnosticOutput.M4A)
        now = 4_000_000L
        run.finish(123)
        val before = store.copySummary()
        now = 9_000_000L
        run.sample(55, 0, 10, 1)
        run.fail(DiagnosticError.UNKNOWN)
        assertEquals(before, store.copySummary())
        assertTrue(before.contains("\"elapsedMs\": 3"))
        assertTrue(before.contains("\"outputBytes\": 123"))
    }
    @Test fun clearDoesNotAllowOldHandleToReturn() {
        val store = DiagnosticStore()
        val old = store.begin("1.0.2", 35, "file", DiagnosticOutput.M4A)
        store.clear()
        old.fail(DiagnosticError.UNKNOWN)
        assertFalse(store.copySummary().contains("\"run\":"))
    }
    @Test fun simultaneousSamplesAreCountedWithoutLoss() {
        val store = DiagnosticStore()
        val run = store.begin("1.0.2", 35, "content", DiagnosticOutput.WAV)
        val workers = List(4) { thread { repeat(1000) { run.sample(it.toLong(), 0, 16, 1) } } }
        workers.forEach { it.join() }
        assertTrue(store.copySummary().contains("\"validSamples\": 4000"))
    }
    @Test fun retainsFirstAndExactlyFinalThreeSampleObservations() {
        val store = DiagnosticStore()
        val run = store.begin("1.0.2", 35, "content", DiagnosticOutput.WAV)
        repeat(10) { run.sample(it.toLong(), 0, 12, 1) }
        val text = store.copySummary()
        assertTrue(text.contains("\"firstSampleTimeUs\": 0"))
        assertTrue(text.contains("\"recentSampleCount\": 3"))
        assertTrue(text.contains("\"recentSample0TimeUs\": 7"))
        assertTrue(text.contains("\"recentSample1TimeUs\": 8"))
        assertTrue(text.contains("\"recentSample2TimeUs\": 9"))
        assertFalse(text.contains("recentSample3"))
    }
    @Test fun sourceAndUnknownDescriptorLengthAreNumericOnly() {
        val store = DiagnosticStore()
        val run = store.begin("1.0.2", 35, "content", DiagnosticOutput.WAV)
        run.descriptor(4096, -1)
        run.stage(DiagnosticStage.OPEN_SOURCE)
        run.fail(DiagnosticError.SOURCE_READ)
        val report = store.copySummary()
        assertTrue(report.contains("\"descriptorStartOffset\": 4096"))
        assertTrue(report.contains("\"descriptorDeclaredLength\": -1"))
        assertTrue(report.contains("\"stage\": \"OPEN_SOURCE\""))
    }
}
