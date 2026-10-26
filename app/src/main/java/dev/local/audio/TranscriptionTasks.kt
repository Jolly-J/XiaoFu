package dev.local.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Service-owned state. A dead process is recovered as resumable, never silently re-uploaded. */
internal object TranscriptionTasks {
    private val mutable = MutableStateFlow<Map<String, TranscriptionUiState>>(emptyMap())
    val states = mutable.asStateFlow()
    @Synchronized fun isBusy(file: File) = mutable.value[file.canonicalPath]?.busy == true
    @Synchronized fun activeFile(): File? = mutable.value.entries.firstOrNull { it.value.busy }?.key?.let(::File)
    private fun record(context: Context, file: File) = File(context.noBackupFilesDir, "asr-tasks").apply { mkdirs() }.let { File(it, file.name + ".json") }
    @Synchronized fun load(context: Context, file: File) {
        if (mutable.value.containsKey(file.canonicalPath)) return
        val json = runCatching { JSONObject(record(context, file).readText()) }.getOrNull()
        val state = if (json == null) TranscriptionUiState() else TranscriptionUiState(
            status = if (json.optBoolean("busy")) "任务已中断，可继续" else json.optString("status"),
            completed = json.optInt("completed"), total = json.optInt("total"),
            hasPartial = json.optInt("completed") > 0,
        )
        mutable.value = mutable.value + (file.canonicalPath to state)
    }
    @Synchronized fun begin(file: File): Boolean {
        if (mutable.value.any { it.value.busy }) return false
        val old = mutable.value[file.canonicalPath] ?: TranscriptionUiState()
        mutable.value = mutable.value + (file.canonicalPath to old.copy(busy = true, complete = false, text = "", status = "准备音频", progress = null, message = null))
        return true
    }
    @Synchronized fun update(context: Context, file: File, transform: (TranscriptionUiState) -> TranscriptionUiState) {
        val state = transform(mutable.value[file.canonicalPath] ?: TranscriptionUiState())
        mutable.value = mutable.value + (file.canonicalPath to state)
        val target = record(context, file)
        val pending = File(target.path + ".tmp")
        val json = JSONObject().put("busy", state.busy).put("status", state.status).put("completed", state.completed).put("total", state.total)
        pending.writeText(json.toString())
        check(pending.renameTo(target)) { "无法保存任务状态" }
    }
    @Synchronized fun forget(context: Context, file: File) {
        check(!isBusy(file)) { "请先取消转写再删除" }
        record(context, file).delete()
        mutable.value = mutable.value - file.canonicalPath
    }
}
