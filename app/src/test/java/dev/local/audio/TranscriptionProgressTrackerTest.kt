package dev.local.audio

import org.junit.Assert.*
import org.junit.Test

class TranscriptionProgressTrackerTest {
    @Test fun singleSliceShowsUploadThenIndeterminateRecognition() {
        val events = mutableListOf<TranscriptionProgress>()
        val tracker = TranscriptionProgressTracker(listOf(1000), events::add)
        tracker.preparing(0)
        tracker.transfer(0, .4f)
        assertEquals(.4f, events.last().uploadProgress!!, .0001f)
        tracker.transfer(0, null)
        assertEquals("正在识别", events.last().stage)
        assertNull("Waiting for one result must not be a static zero-percent bar", events.last().uploadProgress)
        assertEquals(0, events.last().completed)
        tracker.done(0)
        assertEquals(1, events.last().completed)
    }
    @Test fun concurrentUploadUsesBytesNotLastCallbackOrPartCount() {
        val events = mutableListOf<TranscriptionProgress>()
        val tracker = TranscriptionProgressTracker(listOf(100, 300, 0), events::add)
        tracker.done(2) // Cached slice is not uploaded.
        tracker.transfer(0, .5f)
        tracker.transfer(1, .5f)
        assertEquals(.5f, events.last().uploadProgress!!, .0001f)
        tracker.transfer(0, null)
        assertEquals(.625f, events.last().uploadProgress!!, .0001f)
        tracker.transfer(1, null)
        assertNull(events.last().uploadProgress)
        assertEquals(1, events.last().completed)
    }
    @Test fun preparingAndMergingNeverPretendToBeRecognitionPercentages() {
        val events = mutableListOf<TranscriptionProgress>()
        val tracker = TranscriptionProgressTracker(listOf(0, 0), events::add)
        tracker.preparing(0); assertNull(events.last().uploadProgress)
        tracker.done(0); tracker.done(1)
        assertEquals("正在整理全文", events.last().stage)
        assertNull(events.last().uploadProgress)
    }
    @Test fun failedSliceDoesNotIncreaseCompletedCountOrStopOtherProgress() {
        val events = mutableListOf<TranscriptionProgress>()
        val tracker = TranscriptionProgressTracker(listOf(100, 100), events::add)
        tracker.transfer(0, null); tracker.failed(0)
        tracker.transfer(1, .5f)
        assertEquals(0, events.last().completed)
        tracker.done(1)
        assertEquals(1, events.last().completed)
    }
}
