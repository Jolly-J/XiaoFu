package dev.local.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioEngineTest {
    @Test fun legacyAacExcessiveDelayIsIgnored() {
        assertTrue(shouldIgnoreLegacyAacEditList(28, MediaFormat.MIMETYPE_AUDIO_AAC, 45158))
        assertTrue(shouldIgnoreLegacyAacEditList(28, MediaFormat.MIMETYPE_AUDIO_AAC, 4097))
    }
    @Test fun legacyAacNegativeDelayIsIgnored() {
        assertTrue(shouldIgnoreLegacyAacEditList(28, MediaFormat.MIMETYPE_AUDIO_AAC, -1))
    }
    @Test fun validGaplessDelayIsPreserved() {
        for (delay in listOf(0, 1024, 2112, 4096)) {
            assertFalse(shouldIgnoreLegacyAacEditList(28, MediaFormat.MIMETYPE_AUDIO_AAC, delay))
        }
    }
    @Test fun modernAndroidMetadataIsNotAltered() {
        assertFalse(shouldIgnoreLegacyAacEditList(26, MediaFormat.MIMETYPE_AUDIO_AAC, 45158))
        assertFalse(shouldIgnoreLegacyAacEditList(27, MediaFormat.MIMETYPE_AUDIO_AAC, 45158))
        assertFalse(shouldIgnoreLegacyAacEditList(29, MediaFormat.MIMETYPE_AUDIO_AAC, 45158))
        assertFalse(shouldIgnoreLegacyAacEditList(35, MediaFormat.MIMETYPE_AUDIO_AAC, -1))
    }
    @Test fun otherCodecsMetadataIsNotAltered() {
        assertFalse(shouldIgnoreLegacyAacEditList(28, MediaFormat.MIMETYPE_AUDIO_VORBIS, 45158))
        assertFalse(shouldIgnoreLegacyAacEditList(28, MediaFormat.MIMETYPE_AUDIO_MPEG, -1))
    }
    @Test fun missingGaplessMetadataIsNotAltered() {
        assertFalse(shouldIgnoreLegacyAacEditList(28, MediaFormat.MIMETYPE_AUDIO_AAC, null))
        assertFalse(shouldIgnoreLegacyAacEditList(28, null, 45158))
    }

    @Test fun eosSentinelFlagsAreNotEncryption() {
        assertEquals(MediaCodec.BUFFER_FLAG_END_OF_STREAM, decoderInputFlags(-1, -1, -1, -1))
    }
    @Test fun eosTrackIsCheckedBeforeFlags() {
        assertEquals(MediaCodec.BUFFER_FLAG_END_OF_STREAM, decoderInputFlags(10, -1, 12, -1))
    }
    @Test fun negativePtsWithValidSampleIsNotEos() {
        for (pts in listOf(-23219L, -1L, Long.MIN_VALUE)) {
            assertEquals(0, decoderInputFlags(pts, 1, 258, MediaExtractor.SAMPLE_FLAG_SYNC))
        }
    }
    @Test(expected = IllegalStateException::class)
    fun negativePtsDoesNotBypassDrm() {
        decoderInputFlags(-23219, 1, 258, MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
    }
    @Test(expected = IllegalStateException::class)
    fun minusOnePtsDoesNotBypassInvalidFlags() { decoderInputFlags(-1, 1, 258, -1) }
    @Test fun negativeOriginIsAnchoredExactlyOnce() {
        val timeline = AudioTimeline()
        assertEquals(0L, timeline.relative(-46438))
        assertEquals(23219L, timeline.relative(-23219))
        assertEquals(46438L, timeline.relative(0))
        assertEquals(69657L, timeline.relative(23219))
    }
    @Test fun minusOneOriginIsARealTimestamp() {
        val timeline = AudioTimeline()
        assertEquals(0L, timeline.relative(-1))
        assertEquals(1L, timeline.relative(0))
        assertEquals(23220L, timeline.relative(23219))
    }
    @Test fun positiveTimelineKeepsRelativeSpacing() {
        val timeline = AudioTimeline()
        assertEquals(0L, timeline.relative(120000))
        assertEquals(23219L, timeline.relative(143219))
    }
    @Test(expected = ArithmeticException::class)
    fun timestampOverflowIsRejectedInsteadOfWrapping() {
        val timeline = AudioTimeline()
        timeline.relative(Long.MIN_VALUE)
        timeline.relative(Long.MAX_VALUE)
    }
    @Test fun readFailureSignalsEosBeforeFlags() {
        assertEquals(MediaCodec.BUFFER_FLAG_END_OF_STREAM, decoderInputFlags(10, 0, -1, -1))
    }
    @Test fun validUnencryptedSamplesAreQueuedNormally() {
        assertEquals(0, decoderInputFlags(0, 0, 100, MediaExtractor.SAMPLE_FLAG_SYNC))
        assertEquals(0, decoderInputFlags(100, 0, 0, 0))
        assertEquals(MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            decoderInputFlags(100, 0, 100, MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
    }
    @Test(expected = IllegalStateException::class)
    fun validEncryptedSamplesRemainRejected() {
        decoderInputFlags(10, 0, 100, MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
    }
    @Test(expected = IllegalStateException::class)
    fun encryptedPartialSamplesRemainRejected() {
        decoderInputFlags(10, 0, 100, MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
    }
    @Test(expected = IllegalStateException::class)
    fun invalidFlagsOnValidSampleAreNotSilentlyAccepted() { decoderInputFlags(10, 0, 100, -1) }

    @Test fun wavHeaderIsLittleEndianPcm() {
        val bytes = wavHeader(48_000, 2, 192_000)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(192_036, data.getInt(4))
        assertEquals("WAVEfmt ", String(bytes, 8, 8, Charsets.US_ASCII))
        assertEquals(1, data.getShort(20).toInt())
        assertEquals(2, data.getShort(22).toInt())
        assertEquals(48_000, data.getInt(24))
        assertEquals(192_000, data.getInt(28))
        assertEquals(4, data.getShort(32).toInt())
        assertEquals(16, data.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(192_000, data.getInt(40))
    }
    @Test(expected = IllegalArgumentException::class)
    fun wavRejectsOverFourGigabytes() { wavHeader(48_000, 2, 0x1_0000_0000L) }
    @Test(expected = IllegalArgumentException::class)
    fun wavRejectsPartialFrame() { wavHeader(48_000, 2, 3) }

    @Test fun removesOnlyLastExtension() { assertEquals("my.video", exportBaseName("my.video.mp4")) }
    @Test fun sanitizesUnsafeCharacters() { assertEquals("a_b_c", exportBaseName("a/b:c.mp4")) }
    @Test fun emptyNameHasFallback() { assertEquals("音频", exportBaseName(".mp4")) }
    @Test fun preservesChinese() { assertEquals("旅行记录", exportBaseName("旅行记录.mov")) }
    @Test fun boundsNameLength() { assertEquals(64, exportBaseName("a".repeat(200)).length) }
    @Test fun clampsProgress() {
        assertEquals(0f, extractionProgress(-1, 100), 0f)
        assertEquals(0f, extractionProgress(99, 0), 0f)
        assertEquals(0.5f, extractionProgress(50, 100), 0.0001f)
        assertTrue(extractionProgress(200, 100) < 1f)
    }
}
