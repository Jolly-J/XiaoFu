package dev.local.audio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun TranscriptionScreen(file: File, onBack: () -> Unit, audioActions: @Composable (transcribeAlpha: Float) -> Unit = {}, originRect: Rect? = null) {
    val vm: TranscriptionViewModel = viewModel(key = "transcription:${file.absolutePath}")
    val state by vm.state.collectAsStateWithLifecycle()
    var keyDialog by remember { mutableStateOf(false) }
    var configuredKey by remember { mutableStateOf<Boolean?>(null) }
    val hasKey = configuredKey ?: state.hasKey
    var consent by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.saveText(uri) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        vm.start(file, state.provider)
        if (!allowed) vm.message("通知未开启，可在应用内查看进度")
    }
    LaunchedEffect(file.absolutePath) { vm.load(file) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    val rawProgress = LocalMotionProgress.current
    val motionPhase = LocalMotionPhase.current
    val morphProgress = if (motionPhase != MotionPhase.Idle || rawProgress > 0.0005f) rawProgress else 0f
    val buttonAlpha = ((morphProgress - 0.25f) / 0.75f).coerceIn(0f, 1f)
    var targetCardBounds by remember { mutableStateOf<Rect?>(null) }
    val isLeaving = motionPhase == MotionPhase.Leaving
    val density = LocalDensity.current
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val expectedTop = with(density) { (statusBarInset + 74.dp).toPx() }
    val defaultLeft = with(density) { 20.dp.toPx() }

    val bg = MaterialTheme.colorScheme.background
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = if (morphProgress > 0f) {
            val bgAlpha = if (isLeaving) (1f - (morphProgress / 0.35f)).coerceIn(0f, 1f) else (1f - morphProgress * 0.9f).coerceIn(0f, 1f)
            bg.copy(alpha = bgAlpha)
        } else bg,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)
                    .graphicsLayer {
                        if (morphProgress > 0f) {
                            val threshold = if (isLeaving) 0.22f else 0.52f
                            val p = (morphProgress / threshold).coerceIn(0f, 1f)
                            alpha = 1f - p
                            translationY = -24.dp.toPx() * p
                        } else if (morphProgress < 0f) {
                            alpha = 1f
                            translationY = -12.dp.toPx() * morphProgress
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = onBack)
                Text("转文字", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 14.dp))
                IconButton(enabled = !state.busy, onClick = { keyDialog = true }) { Icon(Icons.Outlined.Key, "API Key 设置") }
            }
        },
        bottomBar = {
            if (!state.complete) SoftSurface(
                Modifier
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        if (morphProgress > 0f) {
                            val threshold = if (isLeaving) 0.22f else 0.52f
                            val p = (morphProgress / threshold).coerceIn(0f, 1f)
                            alpha = 1f - p
                            translationY = 24.dp.toPx() * p
                        } else if (morphProgress < 0f) {
                            alpha = 1f
                            translationY = 12.dp.toPx() * morphProgress
                        }
                    }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            if (state.busy) state.status
                            else if (!hasKey) "连接 ${state.provider.displayName}"
                            else if (state.status.isNotBlank()) state.status
                            else "${state.provider.displayName} 转文字",
                            fontWeight = FontWeight.Medium
                        )
                        val detail = when {
                            state.busy && state.progress != null -> "${((state.progress ?: 0f) * 100).toInt()}%"
                            state.busy && state.total > 1 -> "第 ${state.completed}/${state.total} 段"
                            state.busy -> "处理中..."
                            state.total > 1 && !state.complete -> "${state.completed}/${state.total} 段完成"
                            else -> state.provider.modelDisplayName
                        }
                        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.busy) IconButton(onClick = vm::cancel) { Icon(Icons.Outlined.StopCircle, "取消转写") }
                    else FilledIconButton(onClick = { if (!hasKey) keyDialog = true else consent = true }, shape = ContinuousCornerShape(14.dp)) {
                        Icon(if (!hasKey) Icons.Outlined.Key else if (state.hasPartial || state.status.isNotBlank()) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                            if (!hasKey) "配置 API Key" else if (state.hasPartial || state.status.isNotBlank()) "继续转写" else "开始转写")
                    }
                }
            }
        }
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Box(
                Modifier
                    .zIndex(2f)
                    .fillMaxWidth()
                    .onGloballyPositioned { if (it.isAttached) targetCardBounds = it.boundsInRoot() }
                    .graphicsLayer {
                        val isAnimating = morphProgress != 0f || motionPhase != MotionPhase.Idle
                        if (isAnimating && originRect != null) {
                            val targetTop = targetCardBounds?.top ?: expectedTop
                            val targetLeft = targetCardBounds?.left ?: defaultLeft
                            val dy = originRect.top - targetTop
                            val dx = originRect.left - targetLeft
                            translationY = dy * morphProgress
                            translationX = dx * morphProgress
                            
                            val breath = (4f * morphProgress.coerceAtLeast(0f) * (1f - morphProgress.coerceAtLeast(0f))).coerceIn(0f, 1f)
                            val reboundScale = if (morphProgress < 0f) (-morphProgress * 0.35f) else 0f
                            val dynamicScale = (1f - 0.038f * breath) + reboundScale
                            scaleX = dynamicScale
                            scaleY = dynamicScale
                            shadowElevation = 8.dp.toPx() * breath
                            shape = ContinuousCornerShape(18.dp)
                            clip = true
                        }
                    }
            ) {
                audioActions(buttonAlpha)
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .zIndex(1f)
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        if (morphProgress > 0f) {
                            val threshold = if (isLeaving) 0.25f else 0.65f
                            val p = (morphProgress / threshold).coerceIn(0f, 1f)
                            alpha = 1f - p
                            translationY = 32.dp.toPx() * p
                            scaleX = 0.96f + 0.04f * (1f - p)
                            scaleY = 0.96f + 0.04f * (1f - p)
                        } else if (morphProgress < 0f) {
                            alpha = 1f
                            translationY = 16.dp.toPx() * morphProgress
                            scaleX = 1f - morphProgress * 0.15f
                            scaleY = 1f - morphProgress * 0.15f
                        }
                    }
            ) {
                if (!state.complete && !state.busy) {
                    SoftSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource(R.drawable.img_xiaofu_listen),
                                contentDescription = "认真倾听",
                                modifier = Modifier.size(112.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("小蝠已准备就绪", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text(
                                if (!hasKey) "请先配置 ${state.provider.displayName} API Key 即可转写"
                                else if (state.hasPartial) "已转写部分内容，点击下方继续转写"
                                else "选择语音服务商后点击下方开始转写",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                                AsrProvider.entries.forEachIndexed { index, provider ->
                                    SegmentedButton(
                                        selected = state.provider == provider,
                                        onClick = {
                                            configuredKey = null
                                            vm.setProvider(provider)
                                        },
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
                                                fontWeight = if (state.provider == provider) FontWeight.SemiBold else FontWeight.Normal,
                                                modifier = Modifier.padding(start = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Surface(
                                shape = ContinuousCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(top = 10.dp)
                            ) {
                                Text(
                                    when (state.provider) {
                                        AsrProvider.SILICON_FLOW -> "免费调用 · ≤1小时整段直传 · 极速出字"
                                        AsrProvider.MINIMAX -> "识别更准 · 需官网充值约 2.5元/小时"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                if (state.busy) {
                    val targetProgress = state.progress ?: 0f
                    val animatedProgress by animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                        label = "transcriptionProgress"
                    )
                    SoftSurface(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource(if (state.total > 3 && state.completed == 0) R.drawable.img_xiaofu_wait else R.drawable.img_xiaofu_transcribe),
                                contentDescription = "整理文字中",
                                modifier = Modifier.size(108.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(state.status.ifBlank { "${state.provider.displayName} 正在整理文字..." }, fontWeight = FontWeight.Medium)
                            val progressText = when {
                                state.progress != null -> {
                                    if (state.total > 1) "${state.completed}/${state.total} 段 · ${(animatedProgress * 100).toInt()}%"
                                    else "${(animatedProgress * 100).toInt()}%"
                                }
                                state.total > 1 -> "第 ${state.completed}/${state.total} 段"
                                else -> "请稍候..."
                            }
                            Text(progressText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                            val track = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                            if (state.progress != null) LinearProgressIndicator(
                                progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MaterialTheme.colorScheme.primary, trackColor = track,
                                strokeCap = StrokeCap.Round, drawStopIndicator = {},
                            ) else LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MaterialTheme.colorScheme.primary, trackColor = track, strokeCap = StrokeCap.Round,
                            )
                        }
                    }
                }
                if (state.complete) {
                    var showMore by remember { mutableStateOf(false) }
                    SoftSurface(
                        Modifier.fillMaxSize(),
                        accent = MaterialTheme.colorScheme.primary,
                        shape = ContinuousCornerShape(20.dp)
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.img_xiaofu_celebrate),
                                    contentDescription = "完成庆祝",
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(
                                    "转写完成",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                                Surface(
                                    shape = ContinuousCornerShape(7.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Image(
                                            painter = painterResource(state.provider.markRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            state.provider.displayName,
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = { clipboard.setText(AnnotatedString(state.text)); vm.message("已复制") },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, "复制全文", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                                }
                                Box {
                                    IconButton(onClick = { showMore = true }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Outlined.MoreVert, "更多操作", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    }
                                    DropdownMenu(
                                        expanded = showMore,
                                        onDismissRequest = { showMore = false },
                                        shape = ContinuousCornerShape(16.dp),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 8.dp,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("重新转写", fontWeight = FontWeight.Medium) },
                                            leadingIcon = { Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                                            onClick = { showMore = false; resetConfirm = true }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("另存为文本", fontWeight = FontWeight.Medium) },
                                            leadingIcon = { Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = { showMore = false; save.launch(file.nameWithoutExtension + ".txt") }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("分享文本", fontWeight = FontWeight.Medium) },
                                            leadingIcon = { Icon(Icons.Outlined.Share, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = {
                                                showMore = false
                                                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, state.text) }, "分享文字")) }.onFailure { vm.message("没有可用的分享应用") }
                                            }
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SelectionContainer(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(18.dp)
                            ) {
                                Text(
                                    state.text.ifBlank { "未识别到语音" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 26.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (consent) AlertDialog(
        onDismissRequest = { consent = false },
        shape = ContinuousCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        icon = {
            Image(
                painter = painterResource(R.drawable.img_xiaofu_listen),
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )
        },
        title = { Text("使用 ${state.provider.displayName} 转写？", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "音频将上传至 ${state.provider.displayName} 进行识别。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = ContinuousCornerShape(10.dp),
                    color = if (state.provider == AsrProvider.SILICON_FLOW) Color(0xFF1B8755).copy(alpha = 0.1f) else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
                ) {
                    Text(
                        when (state.provider) {
                            AsrProvider.SILICON_FLOW -> "资费提示：免费调用，无需充值任何费用。"
                            AsrProvider.MINIMAX -> "资费提示：需在 MiniMax 官网自充值，约为 2.5 元/小时音频。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (state.provider == AsrProvider.SILICON_FLOW) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Text(
                    "转写可在后台继续，完成后交付全文。取消无法撤回已上传内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    consent = false
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.start(file, state.provider)
                },
                shape = ContinuousCornerShape(12.dp)
            ) { Text("开始") }
        },
        dismissButton = {
            OutlinedButton(onClick = { consent = false }, shape = ContinuousCornerShape(12.dp)) { Text("取消") }
        }
    )
    if (resetConfirm) AlertDialog(
        onDismissRequest = { resetConfirm = false },
        shape = ContinuousCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text("重新转写音频？", fontWeight = FontWeight.SemiBold) },
        text = { Text("此操作将清除当前转写文本，你可以更换服务商重新转写该音频。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            Button(
                onClick = {
                    resetConfirm = false
                    vm.resetTranscript(file)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = ContinuousCornerShape(12.dp)
            ) { Text("清除并重试") }
        },
        dismissButton = {
            OutlinedButton(onClick = { resetConfirm = false }, shape = ContinuousCornerShape(12.dp)) { Text("取消") }
        }
    )
    if (keyDialog) ApiKeySettingsDialog(
        initialProvider = state.provider,
        onDismiss = { keyDialog = false },
        onKeyChanged = {
            configuredKey = it
            vm.setProvider(state.provider)
        }
    )
}
