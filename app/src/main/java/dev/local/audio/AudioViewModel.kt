package dev.local.audio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class AudioUiState(
    val source: AudioSource? = null,
    val format: OutputFormat = OutputFormat.M4A,
    val busy: Boolean = false,
    val inspecting: Boolean = false,
    val progress: Float = 0f,
    val result: File? = null,
    val history: List<File> = emptyList(),
    val message: String? = null,
    val failureMessage: String? = null,
)
class AudioViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = AudioEngine(app)
    private val mutable = MutableStateFlow(AudioUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    init {
        // A killed process can leave an uncommitted export, never a completed history item.
        File(app.filesDir, "exports").listFiles()?.filter { it.extension == "partial" }?.forEach { it.delete() }
        refresh()
    }
    private fun refresh() {
        val files = File(getApplication<Application>().filesDir, "exports").listFiles()
            ?.filter { it.extension in setOf("m4a", "wav") && it.length() > 0 }?.sortedByDescending { it.lastModified() }.orEmpty()
        mutable.update { it.copy(history = files) }
    }
    fun select(uri: Uri) {
        if (mutable.value.busy) return
        work?.cancel()
        work = viewModelScope.launch {
            mutable.update { it.copy(inspecting = true, source = null, result = null, message = null, failureMessage = null) }
            try {
                val source = withContext(Dispatchers.IO) { engine.inspect(uri) }
                mutable.update { it.copy(source = source) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.localizedMessage ?: "无法读取视频，请换一个文件。", failureMessage = e.localizedMessage ?: "无法读取视频，请换一个文件。") } }
            finally { mutable.update { it.copy(inspecting = false) } }
        }
    }
    fun extract() {
        val source = mutable.value.source ?: return
        if (mutable.value.busy || mutable.value.inspecting) return
        mutable.update { it.copy(busy = true, progress = 0f, result = null, message = null, failureMessage = null) }
        work = viewModelScope.launch {
            try {
                val output = withContext(Dispatchers.IO) { engine.extract(source, mutable.value.format) { p -> mutable.update { it.copy(progress = p) } } }
                mutable.update { it.copy(result = output.file, progress = 1f) }
            } catch (e: CancellationException) { mutable.update { it.copy(message = "已取消，不会保留未完成的音频。") }; throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.localizedMessage ?: "提取失败，请重试或更换视频。", failureMessage = e.localizedMessage ?: "提取失败，请重试或更换视频。") } }
            finally { mutable.update { it.copy(busy = false) }; refresh() }
        }
    }
    fun format(format: OutputFormat) { if (!mutable.value.busy) mutable.update { it.copy(format = format) } }
    fun cancel() { work?.cancel() }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun dismissFailure() { mutable.update { it.copy(failureMessage = null) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun delete(file: File) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(!TranscriptionTasks.isBusy(file)) { "请先取消转写再删除" }
                    TranscriptionEngine(getApplication()).deleteLocalTranscript(file)
                    TranscriptionTasks.forget(getApplication(), file)
                    check(!file.exists() || file.delete()) { "无法删除音频，请重试。" }
                }
                mutable.update { it.copy(result = if (it.result == file) null else it.result) }
                refresh()
            } catch (e: Exception) { message(e.localizedMessage ?: "无法删除，请重试。") }
        }
    }
    fun save(file: File, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("无法打开保存位置。")
                }
                message("已保存到所选位置。")
            } catch (e: Exception) { message("保存失败：${e.localizedMessage ?: "请重试"}") }
        }
    }
}
