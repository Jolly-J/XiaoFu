package dev.local.audio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class TranscriptionUiState(
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val progress: Float? = null,
    val status: String = "",
    val text: String = "",
    val complete: Boolean = false,
    val hasPartial: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val message: String? = null,
)
class TranscriptionViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(TranscriptionUiState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    fun load(file: File) {
        observation?.cancel()
        observation = viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    TranscriptionTasks.load(getApplication(), file)
                    TranscriptionEngine(getApplication()).cached(file)
                }
                val key = withContext(Dispatchers.IO) { !ApiKeyStore(getApplication()).read().isNullOrBlank() }
                TranscriptionTasks.states.collect { tasks ->
                    val task = tasks[file.canonicalPath] ?: TranscriptionUiState()
                    val result = cached?.takeIf { it.complete && !task.busy }
                    mutable.value = task.copy(hasKey = key, complete = task.complete || result != null,
                        text = if (task.complete) task.text else result?.text.orEmpty(),
                        hasPartial = task.hasPartial || cached?.complete == false)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("无法读取转写，请重新打开") }
        }
    }
    fun start(file: File) {
        runCatching { TranscriptionService.start(getApplication(), file) }.onFailure { message(it.localizedMessage ?: "无法启动转写") }
    }
    fun cancel() { runCatching { TranscriptionService.cancel(getApplication()) }.onFailure { message("无法取消，请重试") } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun saveText(uri: Uri) {
        if (!mutable.value.complete) return
        val text = mutable.value.text
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法打开保存位置")
                }
                message("已保存")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("保存失败，请重试") }
        }
    }
}
