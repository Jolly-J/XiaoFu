package dev.local.audio

import android.content.ClipData
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var requestedFile by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedFile = intent.getStringExtra(TranscriptionService.FILE)
        enableEdgeToEdge()
        setContent { AudioApp(requestedFile = requestedFile, onIntentHandled = { requestedFile = null }) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        requestedFile = intent.getStringExtra(TranscriptionService.FILE)
    }
}
private val Light = lightColorScheme(
    primary = Color(0xFF1E40AF), onPrimary = Color.White, primaryContainer = Color(0xFFDBEAFE), onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFFD97706), onSecondary = Color.White, secondaryContainer = Color(0xFFFEF3C7), onSecondaryContainer = Color(0xFF92400E),
    tertiary = Color(0xFF0F766E), onTertiary = Color.White, tertiaryContainer = Color(0xFFCCFBF1), onTertiaryContainer = Color(0xFF115E59),
    background = Color(0xFFF8FAFC), surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFF1F5F9), surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceVariant = Color(0xFFF1F5F9), onSurfaceVariant = Color(0xFF64748B), outlineVariant = Color(0xFFE2E8F0))
private val Dark = darkColorScheme(
    primary = Color(0xFF93C5FD), onPrimary = Color(0xFF0B1938), primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFFFBBF24), onSecondary = Color(0xFF451A03), secondaryContainer = Color(0xFF78350F), onSecondaryContainer = Color(0xFFFDE68A),
    tertiary = Color(0xFF5EEAD4), onTertiary = Color(0xFF042F2E), tertiaryContainer = Color(0xFF134E4A), onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0B0F17), surface = Color(0xFF131926), onSurface = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFF131926), surfaceContainerHigh = Color(0xFF1B2334), surfaceContainerHighest = Color(0xFF242E44), surfaceContainerLow = Color(0xFF0E1420),
    surfaceVariant = Color(0xFF1E283A), onSurfaceVariant = Color(0xFF94A3B8), outlineVariant = Color(0xFF2A3447))

private val AppShapes = Shapes(
    extraSmall = ContinuousCornerShape(6.dp),
    small = ContinuousCornerShape(10.dp),
    medium = ContinuousCornerShape(14.dp),
    large = ContinuousCornerShape(18.dp),
    extraLarge = ContinuousCornerShape(24.dp)
)

@Composable
fun AudioApp(vm: AudioViewModel = viewModel(), requestedFile: String? = null, onIntentHandled: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tasks by TranscriptionTasks.states.collectAsStateWithLifecycle()
    var transcriptionFile by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var themeMode by remember { mutableIntStateOf(preferences.getInt("theme_mode", 0)) }
    val dark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val snackbar = remember { SnackbarHostState() }
    var saveFile by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteFile by remember { mutableStateOf<File?>(null) }
    var playing by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    fun stop() { player?.release(); player = null; playing = null }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    SideEffect {
        (context as? android.app.Activity)?.window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            stop(); vm.select(uri)
        }
    }
    fun choose() { picker.launch(arrayOf("video/*")) }
    val saver = rememberLauncherForActivityResult(object : ActivityResultContracts.CreateDocument("audio/mp4") {
        override fun createIntent(context: android.content.Context, input: String) = super.createIntent(context, input).setType(if (input.endsWith(".wav")) "audio/wav" else "audio/mp4")
    }) { uri -> saveFile?.let { if (uri != null) vm.save(File(it), uri) }; saveFile = null }
    fun save(file: File) { saveFile = file.absolutePath; saver.launch(file.name) }
    fun share(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = if (file.extension == "wav") "audio/wav" else "audio/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("音频", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享音频"))
        }.onFailure { vm.message("没有可用的分享应用") }
    }
    fun play(file: File) {
        if (playing == file) { stop(); return }
        stop()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath); setOnPreparedListener { it.start(); playing = file }
                setOnCompletionListener { stop() }; setOnErrorListener { _, _, _ -> stop(); vm.message("无法播放此音频"); true }; prepareAsync()
            }
        }.onFailure { stop(); vm.message("无法播放此音频") }
    }
    val audioCardBounds = remember { mutableStateMapOf<String, Rect>() }
    var activeOriginRect by remember { mutableStateOf<Rect?>(null) }
    var activeOriginPath by remember { mutableStateOf<String?>(null) }
    fun open(file: File) {
        stop()
        activeOriginRect = audioCardBounds[file.absolutePath]
        activeOriginPath = file.absolutePath
        transcriptionFile = file.absolutePath
    }
    LaunchedEffect(requestedFile) {
        requestedFile?.let { name -> TranscriptionService.safeAudio(context, name)?.let { open(it) }; onIntentHandled() }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val hasWorkspace = state.source != null || state.inspecting || state.failureMessage != null || state.result != null
                val backStack = buildList {
                    add("home")
                    if (hasWorkspace) add("extract")
                    if (history) add("history")
                    if (settings) add("settings")
                    transcriptionFile?.let { add("transcript:$it") }
                }
                SmoothBackHost(backStack, onPop = {
                    when {
                        transcriptionFile != null -> { transcriptionFile = null; activeOriginPath = null; activeOriginRect = null }
                        settings -> settings = false
                        history -> history = false
                        hasWorkspace -> { stop(); vm.reset() }
                    }
                }) { route, requestBack ->
                when {
                route == "home" -> {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("小蝠", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                        RoundIconButton(Icons.Outlined.History, "历史") { history = true }
                        Spacer(Modifier.width(10.dp))
                        RoundIconButton(Icons.Outlined.Settings, "设置") { settings = true }
                    }
                }, bottomBar = {
                    tasks.entries.firstOrNull { it.value.busy }?.let { (path, task) ->
                        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 680.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { open(File(path)) }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(task.status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                if (task.total > 0) Text("${task.completed}/${task.total}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
                            Spacer(Modifier.height(16.dp))
                            SoftSurface(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary) {
                                val primaryColor = MaterialTheme.colorScheme.primary
                                val secondaryColor = MaterialTheme.colorScheme.secondary
                                Column(
                                    Modifier
                                        .drawBehind {
                                            val w = size.width
                                            val h = size.height

                                            // 1. Subtle soft tint
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    0f to primaryColor.copy(alpha = 0.04f),
                                                    0.5f to primaryColor.copy(alpha = 0.01f),
                                                    1f to Color.Transparent
                                                )
                                            )

                                            // 2. Acoustic wave lines - gently radiating behind the mascot (top area)
                                            val wave1 = Path().apply {
                                                moveTo(0f, h * 0.22f)
                                                cubicTo(w * 0.25f, h * 0.14f, w * 0.50f, h * 0.30f, w * 0.75f, h * 0.18f)
                                                cubicTo(w * 0.88f, h * 0.12f, w * 0.95f, h * 0.24f, w, h * 0.20f)
                                            }
                                            drawPath(
                                                wave1,
                                                color = primaryColor.copy(alpha = 0.08f),
                                                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                                            )

                                            val wave2 = Path().apply {
                                                moveTo(0f, h * 0.32f)
                                                cubicTo(w * 0.22f, h * 0.40f, w * 0.52f, h * 0.24f, w * 0.76f, h * 0.36f)
                                                cubicTo(w * 0.88f, h * 0.42f, w * 0.95f, h * 0.30f, w, h * 0.32f)
                                            }
                                            drawPath(
                                                wave2,
                                                color = secondaryColor.copy(alpha = 0.07f),
                                                style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                                            )

                                            val wave3 = Path().apply {
                                                moveTo(0f, h * 0.14f)
                                                cubicTo(w * 0.35f, h * 0.26f, w * 0.65f, h * 0.10f, w, h * 0.16f)
                                            }
                                            drawPath(
                                                wave3,
                                                color = primaryColor.copy(alpha = 0.04f),
                                                style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                                            )
                                        }
                                        .padding(horizontal = 24.dp, vertical = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.img_xiaofu_greet),
                                        contentDescription = "小蝠打招呼",
                                        modifier = Modifier.size(136.dp)
                                    )
                                    Text("你好，我是小蝠", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                                    Text("把视频交给我，帮你提取清晰音频与整理文字", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp), textAlign = TextAlign.Center)
                                    Button(onClick = ::choose, shape = ContinuousCornerShape(16.dp), modifier = Modifier.heightIn(min = 50.dp).fillMaxWidth()) {
                                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(20.dp)); Text("选择视频", modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                            if (state.history.isNotEmpty()) {
                                Text("最近音频", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
                                state.history.take(3).forEach { file ->
                                    val isCardMorphing = transcriptionFile != null && activeOriginPath == file.absolutePath
                                    AudioResult(
                                        file = file,
                                        playing = playing == file,
                                        play = { play(file) },
                                        save = { save(file) },
                                        share = { share(file) },
                                        delete = { deleteFile = file },
                                        transcribe = { open(file) },
                                        modifier = if (isCardMorphing) Modifier.graphicsLayer { alpha = 0f } else Modifier,
                                        onPositioned = { rect ->
                                            val cur = audioCardBounds[file.absolutePath]
                                            if (cur == null || cur != rect) audioCardBounds[file.absolutePath] = rect
                                        }
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    }
                }
                }
                route == "extract" -> {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = requestBack)
                        Text("提取音频", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 14.dp))
                        RoundIconButton(Icons.Outlined.History, "历史") { history = true }
                        Spacer(Modifier.width(10.dp))
                        RoundIconButton(Icons.Outlined.Settings, "设置") { settings = true }
                    }
                }, bottomBar = {
                    val currentStep = when {
                        state.busy || state.inspecting -> ExtractStep.Extracting
                        state.result != null -> ExtractStep.Done
                        else -> ExtractStep.SelectFormat
                    }
                    Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 680.dp)) {
                        tasks.entries.firstOrNull { it.value.busy }?.let { (path, task) ->
                            Row(Modifier.fillMaxWidth().clickable { open(File(path)) }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(task.status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                if (task.total > 0) Text("${task.completed}/${task.total}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        when (currentStep) {
                            ExtractStep.SelectFormat -> {
                                Button(
                                    onClick = { stop(); vm.extract() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                    shape = ContinuousCornerShape(18.dp),
                                ) {
                                    Icon(Icons.Outlined.GraphicEq, null, modifier = Modifier.size(20.dp))
                                    Text("提取音频", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                                }
                            }
                            ExtractStep.Extracting -> {
                                Button(
                                    onClick = { vm.cancel() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                    shape = ContinuousCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                                ) {
                                    Icon(Icons.Outlined.Stop, null, modifier = Modifier.size(20.dp))
                                    Text("取消提取", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                                }
                            }
                            ExtractStep.Done -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = requestBack,
                                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                                        shape = ContinuousCornerShape(18.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, modifier = Modifier.size(18.dp))
                                        Text("返回首页", modifier = Modifier.padding(start = 8.dp))
                                    }
                                    Button(
                                        onClick = { state.result?.let { open(it) } },
                                        modifier = Modifier.weight(1.3f).heightIn(min = 54.dp),
                                        shape = ContinuousCornerShape(18.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.Notes, null, modifier = Modifier.size(20.dp))
                                        Text("去转文字", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                }) { padding ->
                    val currentStep = when {
                        state.busy || state.inspecting -> ExtractStep.Extracting
                        state.result != null -> ExtractStep.Done
                        else -> ExtractStep.SelectFormat
                    }
                    val standardCurve = remember { CubicBezierEasing(0.12f, 0.96f, 0.22f, 1.0f) }
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AnimatedContent(
                            targetState = currentStep,
                            transitionSpec = {
                                if (targetState.ordinal > initialState.ordinal) {
                                    (slideInHorizontally(tween(360, easing = standardCurve)) { w -> (w * 0.45f).toInt() } + fadeIn(tween(260)))
                                        .togetherWith(slideOutHorizontally(tween(320, easing = standardCurve)) { w -> (-w * 0.35f).toInt() } + fadeOut(tween(200)))
                                } else {
                                    (slideInHorizontally(tween(360, easing = standardCurve)) { w -> (-w * 0.45f).toInt() } + fadeIn(tween(260)))
                                        .togetherWith(slideOutHorizontally(tween(320, easing = standardCurve)) { w -> (w * 0.35f).toInt() } + fadeOut(tween(200)))
                                }
                            },
                            label = "extractFlow"
                        ) { step ->
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                when (step) {
                                    ExtractStep.SelectFormat -> {
                                        state.source?.let { source ->
                                            SoftSurface(Modifier.fillMaxWidth(), shape = ContinuousCornerShape(20.dp)) {
                                                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    AccentIcon(Icons.Outlined.VideoFile, MaterialTheme.colorScheme.primary)
                                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                                        Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                                        Text("${duration(source.durationUs)} · ${source.channelCount} 声道", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                                    }
                                                    IconButton(onClick = ::choose, enabled = !state.busy && !state.inspecting) { Icon(Icons.Outlined.SwapHoriz, "更换视频") }
                                                }
                                            }
                                        }

                                        Text("选择音频输出格式", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp))

                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            FormatSelectionCard(
                                                format = OutputFormat.M4A,
                                                selected = state.format == OutputFormat.M4A,
                                                onClick = { vm.format(OutputFormat.M4A) },
                                                features = listOf("体积节省约 85%", "转文字推荐格式", "适合日常分享"),
                                                modifier = Modifier.weight(1f)
                                            )
                                            FormatSelectionCard(
                                                format = OutputFormat.WAV,
                                                selected = state.format == OutputFormat.WAV,
                                                onClick = { vm.format(OutputFormat.WAV) },
                                                features = listOf("100% 原始采样", "录音室无损高保真", "适合专业剪辑存档"),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        Surface(
                                            shape = ContinuousCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                Text(
                                                    "提示：${state.format.description}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                )
                                            }
                                        }

                                        state.failureMessage?.let { failure ->
                                            SoftSurface(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.error, shape = ContinuousCornerShape(18.dp)) {
                                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Image(painter = painterResource(R.drawable.img_xiaofu_confused), contentDescription = "失败", modifier = Modifier.size(44.dp))
                                                    Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                                    IconButton(onClick = vm::dismissFailure) { Icon(Icons.Outlined.Close, "关闭错误提示") }
                                                }
                                            }
                                        }
                                    }
                                    ExtractStep.Extracting -> {
                                        if (state.inspecting) {
                                            SoftSurface(Modifier.fillMaxWidth(), shape = ContinuousCornerShape(20.dp)) {
                                                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Image(painter = painterResource(R.drawable.img_xiaofu_receive), contentDescription = "读取视频中", modifier = Modifier.size(54.dp))
                                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                                        Text("小蝠正在解析视频...", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                                        Text("正在读取音频轨道参数", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                                                    }
                                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                                }
                                            }
                                        }
                                        if (state.busy) {
                                            val animatedProgress by animateFloatAsState(
                                                targetValue = state.progress,
                                                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                                                label = "extractProgress"
                                            )
                                            SoftSurface(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary, shape = ContinuousCornerShape(22.dp)) {
                                                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Image(
                                                        painter = painterResource(R.drawable.img_xiaofu_extract),
                                                        contentDescription = "提取音频中",
                                                        modifier = Modifier.size(108.dp)
                                                    )
                                                    Text("小蝠正在提取音频 ${(animatedProgress * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                                                    Text("正在抽取音轨并封装为 ${state.format.name} 格式...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                                                    LinearProgressIndicator(
                                                        progress = { animatedProgress },
                                                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(ContinuousCornerShape(4.dp)),
                                                        strokeCap = StrokeCap.Round,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    ExtractStep.Done -> {
                                        val file = state.result
                                        if (file != null) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Image(
                                                    painter = painterResource(R.drawable.img_xiaofu_celebrate),
                                                    contentDescription = "完成庆祝",
                                                    modifier = Modifier.size(46.dp)
                                                )
                                                Column(Modifier.padding(start = 12.dp)) {
                                                    Text("音频提取完成", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                                    Text("文件已保存至本地，可直接试听或转文字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }

                                            SoftSurface(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary, shape = ContinuousCornerShape(22.dp)) {
                                                Column(Modifier.padding(18.dp)) {
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Surface(
                                                            onClick = { play(file) },
                                                            shape = ContinuousCornerShape(16.dp),
                                                            color = if (playing == file) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                            modifier = Modifier.size(54.dp)
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Icon(
                                                                    if (playing == file) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                                                    contentDescription = if (playing == file) "停止播放" else "播放音频",
                                                                    tint = if (playing == file) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.size(28.dp)
                                                                )
                                                            }
                                                        }
                                                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                Surface(shape = ContinuousCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                                                                    Text(
                                                                        file.extension.uppercase(),
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                                Text(
                                                                    String.format(Locale.ROOT, "  %.2f MB", file.length() / 1048576.0),
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Spacer(Modifier.height(14.dp))
                                                    Surface(
                                                        shape = ContinuousCornerShape(10.dp),
                                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(
                                                                if (playing == file) Icons.Outlined.GraphicEq else Icons.Outlined.Headphones,
                                                                contentDescription = null,
                                                                tint = if (playing == file) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Text(
                                                                if (playing == file) "正在试听播放中... 点击左侧按钮可停止" else "点击上方播放键试听音轨清晰度",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = if (playing == file) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(start = 8.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            Surface(
                                                onClick = { open(file) },
                                                shape = ContinuousCornerShape(22.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                                                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Image(
                                                        painter = painterResource(R.drawable.img_xiaofu_transcribe),
                                                        contentDescription = "转写",
                                                        modifier = Modifier.size(54.dp)
                                                    )
                                                    Column(Modifier.weight(1f).padding(start = 14.dp, end = 6.dp)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("AI 语音转文字", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                                            Surface(shape = ContinuousCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), modifier = Modifier.padding(start = 6.dp)) {
                                                                Text("推荐", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                                            }
                                                        }
                                                        Text("智能识别对白与演讲，提取清晰文字纪要", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                                                    }
                                                    FilledTonalButton(
                                                        onClick = { open(file) },
                                                        shape = ContinuousCornerShape(12.dp),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                        modifier = Modifier.height(36.dp)
                                                    ) {
                                                        Text("去转文字", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(14.dp).padding(start = 2.dp))
                                                    }
                                                }
                                            }

                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                OutlinedButton(
                                                    onClick = { save(file) },
                                                    modifier = Modifier.weight(1f).height(46.dp),
                                                    shape = ContinuousCornerShape(14.dp)
                                                ) {
                                                    Icon(Icons.Outlined.FileDownload, null, modifier = Modifier.size(18.dp))
                                                    Text("保存到文件", modifier = Modifier.padding(start = 6.dp), fontSize = 13.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = { share(file) },
                                                    modifier = Modifier.weight(1f).height(46.dp),
                                                    shape = ContinuousCornerShape(14.dp)
                                                ) {
                                                    Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp))
                                                    Text("分享给好友", modifier = Modifier.padding(start = 6.dp), fontSize = 13.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = { stop(); vm.extract() },
                                                    modifier = Modifier.height(46.dp),
                                                    shape = ContinuousCornerShape(14.dp)
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, "重新提取", modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                route == "history" -> {
                        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = requestBack)
                                    Text("历史记录", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp))
                                }
                            }) { insets ->
                            if (state.history.isEmpty()) {
                                Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                        Image(
                                            painter = painterResource(R.drawable.img_xiaofu_rest),
                                            contentDescription = "暂无音频",
                                            modifier = Modifier.size(136.dp)
                                        )
                                        Text("暂无音频记录", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                                        Text("小蝠正在休整，快去提取一段音频吧", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
                                    }
                                }
                            } else LazyColumn(
                                Modifier.fillMaxSize().padding(insets),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(state.history, key = { it.absolutePath }) { file ->
                                    val isCardMorphing = transcriptionFile != null && activeOriginPath == file.absolutePath
                                    Column {
                                        AudioResult(
                                            file = file,
                                            playing = playing == file,
                                            play = { play(file) },
                                            save = { save(file) },
                                            share = { share(file) },
                                            delete = { deleteFile = file },
                                            transcribe = { open(file) },
                                            modifier = if (isCardMorphing) Modifier.graphicsLayer { alpha = 0f } else Modifier,
                                            onPositioned = { rect ->
                                                val cur = audioCardBounds[file.absolutePath]
                                                if (cur == null || cur != rect) audioCardBounds[file.absolutePath] = rect
                                            }
                                        )
                                        tasks[file.canonicalPath]?.takeIf { it.busy }?.let { task ->
                                            Text(task.status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.clickable { open(file) }.padding(horizontal = 16.dp, vertical = 6.dp))
                                        }
                                    }
                                }
                            }
                        }
                }
                route == "settings" -> {
                        Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
                            Column(Modifier.fillMaxSize().padding(insets)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
                                ) {
                                    RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = requestBack); Spacer(Modifier.width(14.dp)); Text("设置", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                                }
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                                    SettingsContent(themeMode) { themeMode = it; preferences.edit().putInt("theme_mode", it).apply() }
                                }
                            }
                        }
                }
                route.startsWith("transcript:") -> {
                    val path = route.removePrefix("transcript:")
                    TranscriptionScreen(
                        file = File(path),
                        onBack = requestBack,
                        originRect = activeOriginRect,
                        audioActions = { buttonAlpha ->
                            AudioResult(
                                file = File(path),
                                playing = playing == File(path),
                                play = { play(File(path)) },
                                save = { save(File(path)) },
                                share = { share(File(path)) },
                                delete = { deleteFile = File(path) },
                                transcribe = null,
                                transcribeAlpha = buttonAlpha
                            )
                        }
                    )
                }
                }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (transcriptionFile != null || state.source != null) 90.dp else 12.dp))
            }
        deleteFile?.let { file ->
            AlertDialog(
                onDismissRequest = { deleteFile = null },
                shape = ContinuousCornerShape(22.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                icon = {
                    Image(
                        painter = painterResource(R.drawable.img_xiaofu_farewell),
                        contentDescription = "删除确认",
                        modifier = Modifier.size(72.dp)
                    )
                },
                title = { Text("删除音频？", fontWeight = FontWeight.SemiBold) },
                text = { Text("同时删除本地转写，已另存的文件不受影响。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                confirmButton = {
                    Button(
                        onClick = {
                            if (TranscriptionTasks.isBusy(file)) vm.message("请先取消转写") else {
                                if (playing == file) stop(); vm.delete(file)
                                if (transcriptionFile == file.absolutePath) transcriptionFile = null
                            }; deleteFile = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = ContinuousCornerShape(12.dp)
                    ) { Text("删除") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { deleteFile = null }, shape = ContinuousCornerShape(12.dp)) { Text("取消") }
                }
            )
        }
    }
}

@Composable
internal fun AudioResult(
    file: File,
    playing: Boolean,
    play: () -> Unit,
    save: () -> Unit,
    share: () -> Unit,
    delete: () -> Unit,
    transcribe: (() -> Unit)?,
    modifier: Modifier = Modifier,
    transcribeAlpha: Float = 1f,
    onPositioned: ((Rect) -> Unit)? = null
) {
    SoftSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .then(
                if (onPositioned != null) {
                    Modifier.onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            onPositioned(coords.boundsInRoot())
                        }
                    }
                } else Modifier
            ),
        accent = null,
        shape = ContinuousCornerShape(18.dp)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = play,
                shape = ContinuousCornerShape(12.dp),
                color = if (playing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "停止播放" else "播放音频",
                        tint = if (playing) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .then(if (transcribe != null) Modifier.clickable(onClick = transcribe) else Modifier)
            ) {
                Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    String.format(Locale.ROOT, "%s · %.2f MB", file.extension.uppercase(Locale.ROOT), file.length() / 1048576.0),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            val showButton = transcribe != null || transcribeAlpha > 0.001f
            Box(
                Modifier
                    .width(84.dp)
                    .height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showButton) {
                    val alphaVal = if (transcribe != null) 1f else transcribeAlpha
                    FilledTonalButton(
                        onClick = { transcribe?.invoke() },
                        enabled = transcribe != null && alphaVal > 0.6f,
                        shape = ContinuousCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = alphaVal
                                scaleX = 0.85f + 0.15f * alphaVal
                                scaleY = 0.85f + 0.15f * alphaVal
                            },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            disabledContentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Notes, null, modifier = Modifier.size(15.dp))
                        Text(
                            "转文字",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.MoreVert, "更多音频操作", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = more,
                    onDismissRequest = { more = false },
                    shape = ContinuousCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    DropdownMenuItem(
                        text = { Text("另存音频", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                        onClick = { more = false; save() }
                    )
                    DropdownMenuItem(
                        text = { Text("分享音频", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Outlined.Share, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary) },
                        onClick = { more = false; share() }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                        onClick = { more = false; delete() }
                    )
                }
            }
        }
    }
}
private enum class ExtractStep { SelectFormat, Extracting, Done }

@Composable
private fun FormatSelectionCard(
    format: OutputFormat,
    selected: Boolean,
    onClick: () -> Unit,
    features: List<String>,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val borderColor by animateColorAsState(
        targetValue = if (selected) primary else outlineVariant.copy(alpha = 0.45f),
        animationSpec = tween(280),
        label = "formatBorder"
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(280),
        label = "formatBg"
    )

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ContinuousCornerShape(20.dp),
        color = bgColor,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(format.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Surface(
                        shape = ContinuousCornerShape(6.dp),
                        color = if (format == OutputFormat.M4A) Color(0xFFE8F5E9) else Color(0xFFEDE7F6),
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            format.tag,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (format == OutputFormat.M4A) Color(0xFF2E7D32) else Color(0xFF5E35B1),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                if (format == OutputFormat.M4A) "AAC 编码压缩" else "PCM 原始无损",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
            features.forEach { feature ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "•",
                        fontSize = 13.sp,
                        color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(
                        feature,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun duration(us: Long): String { val sec = us / 1_000_000; return "%d:%02d".format(sec / 60, sec % 60) }
