package dev.local.audio
import org.junit.Assert.*
import org.junit.Test
class TranscriptionEngineTest {
    @Test fun wordJoiningPreservesChineseEnglishAndPunctuation() {
        assertEquals("Hello world! 你好", joinTranscriptTokens(listOf("Hello","world","!"," ","你","好")))
        assertEquals("This is a test.",joinTranscriptTokens(listOf("This"," ","is"," a"," test",".")))
    }
    @Test fun shortClipHasSingleChunk() { val p=planChunks(16000*60L,16000,2);assertEquals(1,p.size);assertEquals(0L,p[0].startFrame);assertEquals(16000*60L,p[0].endFrame) }
    @Test fun longHighRateAudioRespectsBothLimits() {
        val frames=48000*3600L; val p=planChunks(frames,48000,4)
        assertTrue(p.size>2)
        assertEquals(0L,p.first().keepFromFrame);assertEquals(frames,p.last().keepToFrame)
        p.forEach { assertTrue((it.endFrame-it.startFrame)*4+44<=45_000_000);assertTrue((it.endFrame-it.startFrame)<=48000*450L) }
        p.zipWithNext().forEach { (a,b)->assertEquals(a.keepToFrame,b.keepFromFrame);assertTrue(a.endFrame>b.startFrame) }
    }
    @Test(expected=IllegalArgumentException::class) fun rejectEmptyAudio() { planChunks(0,16000,2) }
}
