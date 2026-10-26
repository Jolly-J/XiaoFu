package dev.local.audio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun TranscriptionScreen(file: File, onBack: () -> Unit, audioActions: @Composable () -> Unit = {}) {
    val vm: TranscriptionViewModel = viewModel(key = "transcription:${file.absolutePath}")
    val state by vm.state.collectAsStateWithLifecycle()
    var keyDialog by remember { mutableStateOf(false) }
    var configuredKey by remember { mutableStateOf<Boolean?>(null) }
    val hasKey = configuredKey ?: state.hasKey
    var consent by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.saveText(uri) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        vm.start(file)
        if (!allowed) vm.message("通知未开启，可在应用内查看进度")
    }
    LaunchedEffect(file.absolutePath) { vm.load(file) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = onBack)
            Text("转文字", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(enabled = !state.busy, onClick = { keyDialog = true }) { Icon(Icons.Outlined.Key, "API Key 设置") }
        }
    }, bottomBar = {
        if (!state.complete) SoftSurface(Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(if (state.busy) state.status else if (!hasKey) "连接 MiniMax" else if (state.status.isNotBlank()) state.status else "音频转文字", fontWeight = FontWeight.Medium)
                    val detail = when {
                        state.busy && state.progress != null -> "${((state.progress ?: 0f) * 100).toInt()}%"
                        state.total > 1 && !state.complete -> "${state.completed}/${state.total} 段完成"
                        else -> null
                    }
                    detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
                if (state.busy) IconButton(onClick = vm::cancel) { Icon(Icons.Outlined.StopCircle, "取消转写") }
                else FilledIconButton(onClick = { if (!hasKey) keyDialog = true else consent = true }, shape = CircleShape) {
                    Icon(if (!hasKey) Icons.Outlined.Key else if (state.hasPartial || state.status.isNotBlank()) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                        if (!hasKey) "配置 API Key" else if (state.hasPartial || state.status.isNotBlank()) "继续转写" else "开始转写")
                }
            }
        }
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val textMaxHeight = minOf(360.dp, maxHeight * .45f)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                audioActions()
                if (state.busy) {
                    val track = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                    if (state.progress != null) LinearProgressIndicator(
                        progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary, trackColor = track,
                        strokeCap = StrokeCap.Round, drawStopIndicator = {},
                    ) else LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary, trackColor = track, strokeCap = StrokeCap.Round,
                    )
                }
                if (state.complete) {
                    SoftSurface(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("转写结果", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { clipboard.setText(AnnotatedString(state.text)); vm.message("已复制") }) {
                                    Icon(Icons.Outlined.ContentCopy, "复制全文", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = { save.launch(file.nameWithoutExtension + ".txt") }) {
                                    Icon(Icons.Outlined.SaveAlt, "另存全文", modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = {
                                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, state.text) }, "分享文字")) }.onFailure { vm.message("没有可用的分享应用") }
                                }) { Icon(Icons.Outlined.Share, "分享全文", modifier = Modifier.size(20.dp)) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SelectionContainer(Modifier.fillMaxWidth().heightIn(max = textMaxHeight).verticalScroll(rememberScrollState()).padding(18.dp)) {
                                Text(state.text.ifBlank { "未识别到语音" }, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("开始转写？") }, text = { Text("音频将上传至 MiniMax，可能产生 API 费用。可在后台继续，完成后交付全文。取消无法撤回已上传内容。") }, confirmButton = {
        TextButton(onClick = {
            consent = false
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else vm.start(file)
        }) { Text("开始") }
    }, dismissButton = { TextButton(onClick = { consent = false }) { Text("取消") } })
    if (keyDialog) ApiKeySettingsDialog(onDismiss = { keyDialog = false }, onKeyChanged = { configuredKey = it })
}
