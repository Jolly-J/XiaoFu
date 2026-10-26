package dev.local.audio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared settings, available before video selection. Never reveals stored plaintext or persists input in saved instance state. */
@Composable
fun ApiKeySettingsDialog(onDismiss: () -> Unit, onKeyChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember(context) { ApiKeyStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") } // Intentionally not rememberSaveable.
    var hasKey by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentChanged by rememberUpdatedState(onKeyChanged)
    DisposableEffect(context) {
        val window = context.hostActivity()?.window
        val wasSecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(store) {
        try { hasKey = withContext(Dispatchers.IO) { !store.read().isNullOrBlank() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { problem = "无法读取已保存的 Key。可输入新的 Key 覆盖修复。" }
        finally { loading = false }
    }
    fun dismiss() { if (!busy) { input = ""; currentDismiss() } }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = ::dismiss,
        title = { Text("MiniMax API Key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("用于语音转文字。", style = MaterialTheme.typography.bodyMedium)
                Text(if (loading) "正在读取设置…" else if (hasKey) "已配置 · Key 不会明文显示" else "尚未配置", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; problem = null },
                    label = { Text(if (hasKey) "粘贴新 Key 以替换" else "粘贴你的 API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                    enabled = !busy && !loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                Text("仅在此设备加密保存。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)) }
                if (hasKey) TextButton(enabled = !busy && !loading, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.clear() }
                            input = ""; hasKey = false; currentChanged(false); currentDismiss()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { problem = "删除失败，请重试。" }
                        finally { busy = false }
                    }
                }) { Text("删除已保存的 Key") }
            }
        },
        confirmButton = {
            TextButton(enabled = input.isNotBlank() && !busy && !loading, onClick = {
                val candidate = input.trim()
                if (candidate.any { it == '\r' || it == '\n' }) {
                    problem = "Key 不能包含换行，请重新粘贴。"
                } else {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.save(candidate) }
                            input = ""; hasKey = true; currentChanged(true); currentDismiss()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { problem = "无法安全保存，请检查设备存储后重试。" }
                        finally { busy = false }
                    }
                }
            }) { Text(if (busy) "处理中…" else "加密保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = ::dismiss) { Text("关闭") } },
    )
}
private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.hostActivity()
    else -> null
}
