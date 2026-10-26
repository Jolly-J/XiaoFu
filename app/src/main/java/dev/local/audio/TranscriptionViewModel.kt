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
    val provider: AsrProvider = AsrProvider.SILICON_FLOW
)

class TranscriptionViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(TranscriptionUiState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null

    fun load(file: File) {
        observation?.cancel()
        observation = viewModelScope.launch(Dispatchers.IO) {
            val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val fast = runCatching { TranscriptionEngine(getApplication()).cachedFast(file) }.getOrNull()
            val currentTask = TranscriptionTasks.states.value[path]
            val preferred = ApiKeyStore.getPreferredProvider(getApplication())

            withContext(Dispatchers.Main) {
                if (currentTask != null) {
                    mutable.value = currentTask
                } else if (fast?.complete == true) {
                    mutable.value = mutable.value.copy(
                        complete = true,
                        text = fast.text,
                        status = "",
                        busy = false,
                        provider = fast.provider
                    )
                } else if (fast != null && !fast.complete) {
                    mutable.value = mutable.value.copy(
                        hasPartial = true,
                        complete = false,
                        text = fast.text,
                        provider = fast.provider
                    )
                } else {
                    mutable.value = mutable.value.copy(provider = preferred)
                }
            }

            try {
                TranscriptionTasks.load(getApplication(), file)
                val cached = TranscriptionEngine(getApplication()).cached(file)
                val activeProvider = currentTask?.provider ?: cached?.provider ?: preferred
                val key = !ApiKeyStore(getApplication(), activeProvider).read().isNullOrBlank()

                TranscriptionTasks.states.collect { tasks ->
                    val task = tasks[path] ?: TranscriptionUiState(provider = activeProvider)
                    val result = cached?.takeIf { it.complete && !task.busy }
                    val currentProvider = if (task.busy || task.complete) task.provider else result?.provider ?: activeProvider
                    val currentKey = !ApiKeyStore(getApplication(), currentProvider).read().isNullOrBlank()

                    withContext(Dispatchers.Main) {
                        mutable.value = task.copy(
                            hasKey = currentKey,
                            complete = task.complete || result != null,
                            text = if (task.complete) task.text else result?.text.orEmpty(),
                            hasPartial = task.hasPartial || cached?.complete == false,
                            provider = currentProvider
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { message("无法读取转写，请重新打开") }
            }
        }
    }

    fun start(file: File, provider: AsrProvider = mutable.value.provider) {
        runCatching {
            TranscriptionService.start(getApplication(), file, provider)
        }.onFailure {
            message(it.localizedMessage ?: "无法启动转写")
        }
    }

    fun setProvider(provider: AsrProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = !ApiKeyStore(getApplication(), provider).read().isNullOrBlank()
            withContext(Dispatchers.Main) {
                mutable.update { it.copy(provider = provider, hasKey = key) }
            }
        }
    }

    fun cancel() {
        runCatching { TranscriptionService.cancel(getApplication()) }.onFailure { message("无法取消，请重试") }
    }

    fun clearMessage() {
        mutable.update { it.copy(message = null) }
    }

    fun message(text: String) {
        mutable.update { it.copy(message = text) }
    }

    fun resetTranscript(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                TranscriptionEngine(getApplication()).deleteLocalTranscript(file)
                TranscriptionTasks.forget(getApplication(), file)
                val preferred = ApiKeyStore.getPreferredProvider(getApplication())
                val key = !ApiKeyStore(getApplication(), preferred).read().isNullOrBlank()
                withContext(Dispatchers.Main) {
                    mutable.value = TranscriptionUiState(
                        hasKey = key,
                        provider = preferred
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message("重置失败，请重试")
                }
            }
        }
    }

    fun saveText(uri: Uri) {
        if (!mutable.value.complete) return
        val text = mutable.value.text
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法打开保存位置")
                }
                message("已保存")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message("保存失败，请重试")
            }
        }
    }
}
