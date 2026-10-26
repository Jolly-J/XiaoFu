package dev.local.audio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared settings, available before video selection. Never reveals stored plaintext or persists input in saved instance state. */
@Composable
fun ApiKeySettingsDialog(
    initialProvider: AsrProvider = AsrProvider.SILICON_FLOW,
    onDismiss: () -> Unit,
    onKeyChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedProvider by remember { mutableStateOf(initialProvider) }
    var preferredProvider by remember { mutableStateOf(ApiKeyStore.getPreferredProvider(context)) }
    val store = remember(context, selectedProvider) { ApiKeyStore(context.applicationContext, selectedProvider) }

    var input by remember(selectedProvider) { mutableStateOf("") }
    var hasKey by remember(selectedProvider) { mutableStateOf(false) }
    var loading by remember(selectedProvider) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember(selectedProvider) { mutableStateOf<String?>(null) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentChanged by rememberUpdatedState(onKeyChanged)

    LaunchedEffect(selectedProvider) {
        try {
            loading = true
            hasKey = withContext(Dispatchers.IO) { !store.read().isNullOrBlank() }
            preferredProvider = ApiKeyStore.getPreferredProvider(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            problem = "无法读取已保存的 Key。可输入新的 Key 覆盖修复。"
        } finally {
            loading = false
        }
    }

    fun dismiss() { if (!busy) { input = ""; currentDismiss() } }

    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.Inherit),
        onDismissRequest = ::dismiss,
        shape = ContinuousCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(selectedProvider.markRes),
                    contentDescription = selectedProvider.displayName,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "${selectedProvider.displayName} API Key",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    AsrProvider.entries.forEachIndexed { index, provider ->
                        SegmentedButton(
                            selected = selectedProvider == provider,
                            onClick = { if (!busy) selectedProvider = provider },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = AsrProvider.entries.size),
                            icon = {}
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(provider.markRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "${provider.displayName} · ${provider.featureTag}",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedProvider == provider) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = ContinuousCornerShape(14.dp),
                    color = if (selectedProvider == AsrProvider.SILICON_FLOW) Color(0xFF1B8755).copy(alpha = 0.08f) else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = ContinuousCornerShape(6.dp),
                                color = if (selectedProvider == AsrProvider.SILICON_FLOW) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary
                            ) {
                                Text(
                                    selectedProvider.featureTag,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                selectedProvider.billingTip,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selectedProvider == AsrProvider.SILICON_FLOW) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Text(
                            when (selectedProvider) {
                                AsrProvider.SILICON_FLOW -> "【申请教程】\n1. 点击下方按钮前往官网 (cloud.siliconflow.cn)\n2. 注册并登录，在左侧「API 密钥」中新建 Key\n3. 复制生成的 Key 粘贴到下方，无需充值费用即可使用。"
                                AsrProvider.MINIMAX -> "【申请教程】\n1. 点击下方按钮前往 MiniMax 开放平台 (platform.minimaxi.com)\n2. 注册并登录，在「接口密钥」中创建 Key\n3. MiniMax 转写需自充值，资费约 2.5 元/小时，充值后粘贴到下方。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selectedProvider.keyDocUrl)))
                                }
                            },
                            shape = ContinuousCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text(
                                if (selectedProvider == AsrProvider.SILICON_FLOW) "去官网免费申请 API Key" else "前往 MiniMax 申请与充值",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (loading) "正在读取设置…" else if (hasKey) "已配置 · 加密保存" else "尚未配置",
                        color = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall
                    )
                    val isDefault = preferredProvider == selectedProvider
                    FilterChip(
                        selected = isDefault,
                        onClick = {
                            ApiKeyStore.setPreferredProvider(context, selectedProvider)
                            preferredProvider = selectedProvider
                        },
                        label = { Text(if (isDefault) "当前优先" else "设为优先", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                if (isDefault) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        shape = ContinuousCornerShape(8.dp),
                        modifier = Modifier.height(28.dp)
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; problem = null },
                    label = { Text(if (hasKey) "粘贴新 Key 以替换" else "粘贴你的 API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                    shape = ContinuousCornerShape(14.dp),
                    enabled = !busy && !loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text("仅在设备密钥库加密保存，不进备份与日志。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                if (hasKey) {
                    TextButton(
                        enabled = !busy && !loading,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { store.clear() }
                                    input = ""; hasKey = false; currentChanged(false)
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { problem = "删除失败，请重试。" }
                                finally { busy = false }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 6.dp)
                    ) { Text("删除当前服务商 Key") }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = input.isNotBlank() && !busy && !loading,
                shape = ContinuousCornerShape(12.dp),
                onClick = {
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
                }
            ) { Text(if (busy) "处理中…" else "加密保存") }
        },
        dismissButton = {
            OutlinedButton(enabled = !busy, onClick = ::dismiss, shape = ContinuousCornerShape(12.dp)) { Text("关闭") }
        },
    )
}
private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.hostActivity()
    else -> null
}
