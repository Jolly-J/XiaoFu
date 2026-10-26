package dev.local.audio

/**
 * Local-only, bounded, in-memory diagnostic summaries. There is intentionally no API for
 * exception messages, stack traces, filenames, URI strings, API keys, media bytes or text.
 * Copying is an explicit UI action; this component neither logs nor uploads anything.
 */
object Diagnostics {
    private val store = DiagnosticStore()

    fun begin(
        appVersion: String,
        sdkInt: Int,
        sourceScheme: String?,
        outputFormat: DiagnosticOutput,
    ): DiagnosticSession = store.begin(appVersion, sdkInt, sourceScheme, outputFormat)

    fun copySummary(): String = store.copySummary()
    fun clear() = store.clear()
}

enum class DiagnosticOutput { SOURCE_SCAN, M4A, WAV, LOCAL_ASR_DECODE }
enum class DiagnosticStage { START, OPEN_SOURCE, STAGING_SOURCE, SELECT_TRACK, READ_SAMPLE, DECODE, ENCODE, MUX, COMMIT, COMPLETE }
enum class DiagnosticError {
    NO_AUDIO_DATA, PROTECTED_AUDIO, INVALID_SAMPLE_FLAGS, SOURCE_READ, CODEC,
    CANCELLED, UNKNOWN, NO_AUDIO_TRACK, INVALID_TIMESTAMPS, UNSUPPORTED_FORMAT,
    INCOMPLETE_SAMPLE, MUXER, STORAGE, CODEC_TIMEOUT,
}

/** A handle cannot write into another run or revive an evicted/cleared run. */
class DiagnosticSession internal constructor(private val store: DiagnosticStore, private val id: Long) {
    fun source(mime: String?, trackCount: Int, trackIndex: Int, sampleRate: Int, channels: Int, durationUs: Long) =
        store.source(id, mime, trackCount, trackIndex, sampleRate, channels, durationUs)
    fun descriptor(startOffset: Long, declaredLength: Long) = store.descriptor(id, startOffset, declaredLength)
    fun stage(stage: DiagnosticStage) = store.stage(id, stage)
    /**
     * Call for observed samples, including EOS. Negative timestamps are valid if the track
     * and read size are valid. Do not fetch sampleFlags after EOS just to fill this field.
     */
    fun sample(timeUs: Long, trackIndex: Int, size: Long, flags: Int? = null) =
        store.sample(id, timeUs, trackIndex, size, flags)
    fun finish(outputBytes: Long? = null) = store.finish(id, outputBytes)
    fun fail(error: DiagnosticError) = store.fail(id, error)
}

internal class DiagnosticStore(
    private val capacity: Int = 3,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init { require(capacity in 1..10) }
    private var nextId = 0L
    private val runs = LinkedHashMap<Long, Run>()

    @Synchronized
    fun begin(version: String, sdk: Int, scheme: String?, output: DiagnosticOutput): DiagnosticSession {
        val id = ++nextId
        if (runs.size == capacity) runs.remove(runs.keys.first())
        runs[id] = Run(
            id = id,
            version = version.takeIf { it.matches(Regex("[0-9]{1,4}(\\.[0-9]{1,4}){1,3}")) } ?: "unknown",
            sdk = sdk.takeIf { it in 1..999 },
            scheme = when (scheme?.lowercase()) { "content" -> "content"; "file" -> "file"; null -> "none"; else -> "other" },
            output = output,
            started = clockNanos(),
        )
        return DiagnosticSession(this, id)
    }

    @Synchronized
    fun source(id: Long, mime: String?, trackCount: Int, trackIndex: Int, rate: Int, channels: Int, durationUs: Long) {
        active(id)?.source = Source(safeAudioMime(mime), trackCount, trackIndex, rate, channels, durationUs)
    }
    @Synchronized
    fun descriptor(id: Long, offset: Long, length: Long) { active(id)?.descriptor = Descriptor(offset, length) }
    @Synchronized
    fun stage(id: Long, stage: DiagnosticStage) { active(id)?.stage = stage }
    @Synchronized
    fun sample(id: Long, timeUs: Long, track: Int, size: Long, flags: Int?) {
        val run = active(id) ?: return
        val sample = Sample(timeUs, track, size, flags)
        run.observed++
        if (run.first == null) run.first = sample
        run.last = sample
        if (run.recent.size == 3) run.recent.removeAt(0)
        run.recent.add(sample)
        // Crucially, a negative presentation timestamp alone is NOT end of stream.
        if (track < 0 || size < 0) {
            run.eosObserved = true
        } else {
            run.validSamples++
            if (timeUs < 0) run.negativePtsSamples++
            run.minimumPts = run.minimumPts?.let { minOf(it, timeUs) } ?: timeUs
            run.maximumPts = run.maximumPts?.let { maxOf(it, timeUs) } ?: timeUs
            if (flags != null && flags >= 0 && flags and 2 != 0) run.encryptedSamples++
        }
    }
    @Synchronized
    fun finish(id: Long, outputBytes: Long?) {
        val run = active(id) ?: return
        run.status = "COMPLETE"
        run.stage = DiagnosticStage.COMPLETE
        run.outputBytes = outputBytes?.takeIf { it >= 0 }
        run.finished = clockNanos()
    }
    @Synchronized
    fun fail(id: Long, error: DiagnosticError) {
        val run = active(id) ?: return
        run.status = if (error == DiagnosticError.CANCELLED) "CANCELLED" else "FAILED"
        run.error = error
        run.finished = clockNanos()
    }
    @Synchronized
    fun clear() { runs.clear() }
    @Synchronized
    fun copySummary(): String = buildString {
        append("{\n  \"diagnosticSchema\": 1,\n  \"privacy\": \"metadata_only_no_key_uri_filename_content\",\n  \"runs\": [")
        runs.values.toList().asReversed().forEachIndexed { index, run ->
            if (index > 0) append(',')
            append("\n    {")
            val values = linkedMapOf<String, Any?>(
                "run" to run.id,
                "appVersion" to run.version,
                "androidApi" to run.sdk,
                "sourceScheme" to run.scheme,
                "outputFormat" to run.output.name,
                "status" to run.status,
                "stage" to run.stage.name,
                "errorCode" to run.error?.name,
                "elapsedMs" to (((run.finished ?: clockNanos()) - run.started).coerceAtLeast(0) / 1_000_000),
                "audioMime" to run.source?.mime,
                "trackCount" to run.source?.tracks,
                "selectedTrack" to run.source?.selected,
                "sampleRate" to run.source?.rate,
                "channels" to run.source?.channels,
                "durationUs" to run.source?.duration,
                "descriptorStartOffset" to run.descriptor?.offset,
                "descriptorDeclaredLength" to run.descriptor?.length,
                "sampleObservations" to run.observed,
                "validSamples" to run.validSamples,
                "negativePtsSamples" to run.negativePtsSamples,
                "encryptedValidSamples" to run.encryptedSamples,
                "eosObserved" to run.eosObserved,
                "minValidPtsUs" to run.minimumPts,
                "maxValidPtsUs" to run.maximumPts,
                "outputBytes" to run.outputBytes,
            )
            fun addSample(prefix: String, value: Sample?) {
                values["${prefix}TimeUs"] = value?.time
                values["${prefix}Track"] = value?.track
                values["${prefix}Size"] = value?.size
                values["${prefix}Flags"] = value?.flags
            }
            addSample("firstSample", run.first)
            addSample("lastSample", run.last)
            values["recentSampleCount"] = run.recent.size
            // Chronological tail: oldest of the final three observations first.
            run.recent.forEachIndexed { tailIndex, sample -> addSample("recentSample$tailIndex", sample) }
            values.entries.forEachIndexed { field, (key, value) ->
                if (field > 0) append(',')
                append("\n      \"").append(key).append("\": ")
                when (value) {
                    null -> append("null")
                    is Number, is Boolean -> append(value)
                    else -> append('"').append(value).append('"') // All strings are fixed enums or strict allowlists.
                }
            }
            append("\n    }")
        }
        append("\n  ]\n}")
    }

    private fun active(id: Long): Run? = runs[id]?.takeIf { it.status == "RUNNING" }
    private data class Source(val mime: String, val tracks: Int, val selected: Int, val rate: Int, val channels: Int, val duration: Long)
    private data class Descriptor(val offset: Long, val length: Long)
    private data class Sample(val time: Long, val track: Int, val size: Long, val flags: Int?)
    private data class Run(
        val id: Long, val version: String, val sdk: Int?, val scheme: String,
        val output: DiagnosticOutput, val started: Long,
        var finished: Long? = null, var status: String = "RUNNING",
        var stage: DiagnosticStage = DiagnosticStage.START, var error: DiagnosticError? = null,
        var source: Source? = null, var descriptor: Descriptor? = null,
        var first: Sample? = null, var last: Sample? = null,
        val recent: MutableList<Sample> = ArrayList(3),
        var observed: Long = 0, var validSamples: Long = 0, var negativePtsSamples: Long = 0,
        var encryptedSamples: Long = 0, var eosObserved: Boolean = false,
        var minimumPts: Long? = null, var maximumPts: Long? = null, var outputBytes: Long? = null,
    )
}

/** Unknown values never pass through, even if a caller accidentally supplies a URI/name/key. */
private fun safeAudioMime(value: String?): String = value?.lowercase()?.takeIf {
    it in setOf(
        "audio/mp4a-latm", "audio/mpeg", "audio/raw", "audio/vorbis", "audio/opus", "audio/flac",
        "audio/3gpp", "audio/amr-wb", "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4",
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/alac", "audio/ape", "audio/wav", "audio/x-wav", "audio/mp4",
    )
} ?: "other"
