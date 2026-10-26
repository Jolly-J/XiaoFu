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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    primary = Color(0xFF5D4DDB), onPrimary = Color.White, primaryContainer = Color(0xFFECE8FF), onPrimaryContainer = Color(0xFF30246B),
    secondary = Color(0xFF087F78), onSecondary = Color.White, secondaryContainer = Color(0xFFDDF5ED), onSecondaryContainer = Color(0xFF14564D),
    tertiary = Color(0xFFA65F12), onTertiary = Color.White, tertiaryContainer = Color(0xFFFFEED7), onTertiaryContainer = Color(0xFF75400B),
    background = Color(0xFFF8F9FD), surface = Color.White, onSurface = Color(0xFF25283B),
    surfaceVariant = Color(0xFFF0EFF8), onSurfaceVariant = Color(0xFF6D7085), outlineVariant = Color(0xFFDFE1ED))
private val Dark = darkColorScheme(
    primary = Color(0xFFC0B5FF), onPrimary = Color(0xFF302369), primaryContainer = Color(0xFF3F3465), onPrimaryContainer = Color(0xFFE8E1FF),
    secondary = Color(0xFF7EDBC4), onSecondary = Color(0xFF073D35), secondaryContainer = Color(0xFF24483F), onSecondaryContainer = Color(0xFFC1F5E5),
    tertiary = Color(0xFFF3C078), onTertiary = Color(0xFF49300E), tertiaryContainer = Color(0xFF4C3B25), onTertiaryContainer = Color(0xFFFFE4BB),
    background = Color(0xFF191C27), surface = Color(0xFF242837), onSurface = Color(0xFFEDEEF7),
    surfaceVariant = Color(0xFF313648), onSurfaceVariant = Color(0xFFAFB4CB), outlineVariant = Color(0xFF42485E))

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
    fun open(file: File) { stop(); transcriptionFile = file.absolutePath }
    LaunchedEffect(requestedFile) {
        requestedFile?.let { name -> TranscriptionService.safeAudio(context, name)?.let { open(it) }; onIntentHandled() }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val backStack = buildList {
                    add("home")
                    if (history) add("history")
                    if (settings) add("settings")
                    transcriptionFile?.let { add("transcript:$it") }
                }
                SmoothBackHost(backStack, onPop = {
                    when {
                        transcriptionFile != null -> transcriptionFile = null
                        settings -> settings = false
                        history -> history = false
                    }
                }) { route, requestBack ->
                when {
                route == "home" -> {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("声屿", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.weight(1f).padding(start = 16.dp))
                        RoundIconButton(Icons.Outlined.History, "历史") { history = true }
                        Spacer(Modifier.width(12.dp))
                        RoundIconButton(Icons.Outlined.Settings, "设置") { settings = true }

                    }
                }, bottomBar = {
                    Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 680.dp)) {
                        tasks.entries.firstOrNull { it.value.busy }?.let { (path, task) ->
                            Row(Modifier.fillMaxWidth().clickable { open(File(path)) }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(task.status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                if (task.total > 0) Text("${task.completed}/${task.total}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (state.source != null || state.inspecting) {
                            Button(
                                onClick = { if (state.busy || state.inspecting) vm.cancel() else { stop(); vm.extract() } },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Icon(if (state.busy || state.inspecting) Icons.Outlined.Stop else Icons.Outlined.GraphicEq, null, modifier = Modifier.size(20.dp))
                                Text(if (state.busy || state.inspecting) "取消" else if (state.result != null) "重新提取" else "提取音频", modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (state.source == null && !state.inspecting && state.failureMessage == null) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                                Spacer(Modifier.height(36.dp))
                                SoftSurface(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary) {
                                    Column(Modifier.background(Brush.linearGradient(listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                                        MaterialTheme.colorScheme.secondary.copy(alpha = .06f),
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = .06f),
                                    ))).padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                            AccentIcon(Icons.Outlined.VideoFile, MaterialTheme.colorScheme.primary, size = 64.dp, iconSize = 34.dp)
                                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                            AccentIcon(Icons.Outlined.GraphicEq, MaterialTheme.colorScheme.secondary, size = 64.dp, iconSize = 34.dp)
                                        }
                                        Text("视频转音频", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 24.dp, bottom = 28.dp))
                                        Button(onClick = ::choose, shape = RoundedCornerShape(16.dp), modifier = Modifier.heightIn(min = 50.dp).fillMaxWidth()) {
                                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(20.dp)); Text("选择视频", modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                }
                                if (state.history.isNotEmpty()) {
                                    Text("最近音频", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 36.dp, bottom = 16.dp))
                                    state.history.take(3).forEach { file ->
                                        AudioResult(file, playing == file, { play(file) }, { save(file) }, { share(file) }, { deleteFile = file }, { open(file) })
                                        Spacer(Modifier.height(20.dp))
                                    }
                                }
                            }
                        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            if (state.inspecting) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                            state.source?.let { source ->
                                SoftSurface(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AccentIcon(Icons.Outlined.VideoFile, MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                        Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text("${duration(source.durationUs)} · ${source.channelCount} 声道", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                    }
                                    IconButton(onClick = ::choose, enabled = !state.busy && !state.inspecting) { Icon(Icons.Outlined.SwapHoriz, "更换视频") }
                                }
                                }
                            }
                            if (state.source != null && !state.busy) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutputFormat.entries.forEach { format ->
                                        FilterChip(selected = state.format == format, onClick = { vm.format(format) }, label = { Text(format.name) }, shape = RoundedCornerShape(16.dp),
                                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface, labelColor = MaterialTheme.colorScheme.onSurface,
                                                selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                                    }
                                }
                            }
                            if (state.busy) {
                                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                                Text("提取中 ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            }
                            state.failureMessage?.let { failure ->
                                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error); Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(start = 12.dp)); IconButton(onClick = vm::dismissFailure) { Icon(Icons.Outlined.Close, "关闭错误提示") } }
                            }
                            state.result?.let { file ->
                                AudioResult(file, playing == file, { play(file) }, { save(file) }, { share(file) }, { deleteFile = file }, { open(file) })
                            }
                        }
                    }
                }
                }
                route == "history" -> {
                        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = requestBack)
                                    Text("历史", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp))
                                }
                            }) { insets ->
                            if (state.history.isEmpty()) {
                                Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        AccentIcon(Icons.Outlined.History, MaterialTheme.colorScheme.secondary, size = 64.dp, iconSize = 32.dp)
                                        Text("暂无音频", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                                    }
                                }
                            } else LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(state.history, key = { it.absolutePath }) { file ->
                                    Column {
                                        AudioResult(file, playing == file, { play(file) }, { save(file) }, { share(file) }, { deleteFile = file }, { open(file) })
                                        tasks[file.canonicalPath]?.takeIf { it.busy }?.let { task ->
                                            Text(task.status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.clickable { open(file) }.padding(horizontal = 16.dp, vertical = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
                }
                route == "settings" -> {
                        Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
                            Column(Modifier.fillMaxSize().padding(insets)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                    RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = requestBack); Spacer(Modifier.width(16.dp)); Text("设置", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                                }
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                                    SettingsContent(themeMode) { themeMode = it; preferences.edit().putInt("theme_mode", it).apply() }
                                }
                            }
                        }
                }
                route.startsWith("transcript:") -> {
                    val path = route.removePrefix("transcript:")
                        TranscriptionScreen(File(path), onBack = requestBack, audioActions = {
                            AudioResult(File(path), playing == File(path), { play(File(path)) }, { save(File(path)) }, { share(File(path)) }, { deleteFile = File(path) }, null)
                        })
                }
                }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (transcriptionFile != null || state.source != null) 90.dp else 12.dp))
            }
        deleteFile?.let { file ->
            AlertDialog(onDismissRequest = { deleteFile = null }, title = { Text("删除音频？") }, text = { Text("同时删除本地转写，已另存的文件不受影响。") }, confirmButton = {
                TextButton(onClick = {
                    if (TranscriptionTasks.isBusy(file)) vm.message("请先取消转写") else {
                        if (playing == file) stop(); vm.delete(file)
                        if (transcriptionFile == file.absolutePath) transcriptionFile = null
                    }; deleteFile = null
                }) { Text("删除") }
            }, dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("取消") } })
        }
    }
}

@Composable
internal fun AudioResult(file: File, playing: Boolean, play: () -> Unit, save: () -> Unit, share: () -> Unit, delete: () -> Unit, transcribe: (() -> Unit)?) {
    SoftSurface(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.secondary) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = play, shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)) { Icon(if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, if (playing) "停止播放" else "播放音频") }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(String.format(Locale.ROOT, "%s · %.2f MB", file.extension.uppercase(Locale.ROOT), file.length() / 1048576.0), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreHoriz, "更多音频操作") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { more = false; delete() })
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = save) { Icon(Icons.Outlined.SaveAlt, "另存音频", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = share) { Icon(Icons.Outlined.Share, "分享音频", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp)) }
            if (transcribe != null) TextButton(onClick = transcribe) { Icon(Icons.Outlined.Notes, null, modifier = Modifier.size(18.dp)); Text("转文字", modifier = Modifier.padding(start = 8.dp)) }
        }
    }
    }
}
private fun duration(us: Long): String { val sec = us / 1_000_000; return "%d:%02d".format(sec / 60, sec % 60) }
