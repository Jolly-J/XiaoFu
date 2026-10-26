package dev.local.audio

/** One aggregate upload fraction, with unknown server processing represented as indeterminate. */
internal class TranscriptionProgressTracker(
    private val uploadBytes: List<Long>,
    private val emit: (TranscriptionProgress) -> Unit,
) {
    private enum class Part { Queued, Preparing, Uploading, Recognizing, Done, Failed }
    private val states = Array(uploadBytes.size) { Part.Queued }
    private val uploaded = FloatArray(uploadBytes.size)
    private val expectedBytes = uploadBytes.sum().toDouble()
    init { require(uploadBytes.isNotEmpty() && uploadBytes.all { it >= 0 }) }

    @Synchronized fun preparing(index: Int) { states[index] = Part.Preparing; publish() }
    @Synchronized fun transfer(index: Int, progress: Float?) {
        states[index] = if (progress == null) Part.Recognizing else Part.Uploading
        uploaded[index] = if (progress == null) 1f else maxOf(uploaded[index], progress.coerceIn(0f, 1f))
        publish()
    }
    @Synchronized fun done(index: Int) { states[index] = Part.Done; uploaded[index] = 1f; publish() }
    @Synchronized fun failed(index: Int) { states[index] = Part.Failed; publish() }
    private fun publish() {
        val completed = states.count { it == Part.Done }
        val stage: String
        val progress: Float?
        when {
            states.any { it == Part.Uploading } -> {
                stage = "正在上传音频"
                progress = if (expectedBytes > 0) (uploadBytes.indices.sumOf { uploadBytes[it] * uploaded[it].toDouble() } / expectedBytes).toFloat().coerceIn(0f, 1f) else null
            }
            states.any { it == Part.Recognizing } -> { stage = "正在识别"; progress = null }
            states.any { it == Part.Queued || it == Part.Preparing } -> { stage = "正在准备音频"; progress = null }
            states.any { it == Part.Failed } -> { stage = "转写未完成"; progress = null }
            else -> { stage = "正在整理全文"; progress = null }
        }
        emit(TranscriptionProgress(stage, completed, states.size, progress))
    }
}
