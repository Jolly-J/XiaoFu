package dev.local.audio

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File

class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var audio: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopReason = "已取消，可继续"
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "转写进度", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); enableVibration(false); enableLights(false)
            }
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (work == null) stopSelf() else work?.cancel()
            return START_NOT_STICKY
        }
        if (work != null) return START_NOT_STICKY
        val file = intent?.getStringExtra(FILE)?.let { safeAudio(this, it) }
        if (file == null) { stopSelf(); return START_NOT_STICKY }
        audio = file
        val providerId = intent?.getStringExtra(PROVIDER)
        val provider = if (!providerId.isNullOrBlank()) AsrProvider.fromId(providerId) else {
            TranscriptionTasks.states.value[file.canonicalPath]?.provider ?: ApiKeyStore.getPreferredProvider(this)
        }
        try {
            getSystemService(NotificationManager::class.java).cancel(ID + 1)
            val notification = notification(file, TranscriptionUiState(busy = true, status = "准备音频", provider = provider))
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaofu:transcription").apply {
                acquire(MAX_RUNTIME)
            }
        } catch (e: Exception) {
            TranscriptionTasks.update(this, file) { it.copy(busy = false, status = "后台任务未启动", message = "无法启动后台转写，请重新打开应用后重试", provider = provider) }
            stopSelf(); return START_NOT_STICKY
        }
        work = scope.launch {
            try {
                withTimeout(MAX_RUNTIME - 60_000) {
                    withContext(Dispatchers.IO) {
                        val key = ApiKeyStore(this@TranscriptionService, provider).read() ?: error("请先配置 ${provider.displayName} API Key")
                        TranscriptionTasks.update(this@TranscriptionService, file) { it.copy(busy = true, provider = provider) }
                        var lastStage = ""
                        var lastCount = -1
                        var lastUpdate = 0L
                        val result = engineFactory(this@TranscriptionService).transcribe(file, key, provider) { p ->
                            val now = SystemClock.elapsedRealtime()
                            if (p.stage != lastStage || p.completed != lastCount || now - lastUpdate >= 1000) {
                                lastStage = p.stage; lastCount = p.completed; lastUpdate = now
                                TranscriptionTasks.update(this@TranscriptionService, file) { it.copy(
                                    status = p.stage, completed = p.completed, total = p.total,
                                    progress = p.uploadProgress,
                                    hasPartial = p.completed > 0,
                                    provider = provider,
                                ) }
                                val state = TranscriptionTasks.states.value[file.canonicalPath]!!
                                getSystemService(NotificationManager::class.java).notify(ID, notification(file, state))
                            }
                        }
                        TranscriptionTasks.update(this@TranscriptionService, file) { it.copy(busy = false, complete = true, hasPartial = false, text = result.text, status = "转写完成", progress = 1f, provider = provider) }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                finishState(file, "后台时限已到，可继续", provider = provider)
            } catch (_: CancellationException) {
                finishState(file, stopReason, provider = provider)
            } catch (e: Exception) {
                finishState(file, "转写未完成", e.localizedMessage ?: "请检查网络后重试", provider = provider)
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                val state = TranscriptionTasks.states.value[file.canonicalPath] ?: TranscriptionUiState(provider = provider)
                runCatching { getSystemService(NotificationManager::class.java).notify(ID + 1, notification(file, state, finished = true)) }
                work = null; audio = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun finishState(file: File, status: String, message: String? = null, provider: AsrProvider? = null) = withContext(NonCancellable + Dispatchers.IO) {
        TranscriptionTasks.update(this@TranscriptionService, file) { it.copy(busy = false, progress = null, status = status, message = message, provider = provider ?: it.provider) }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopReason = "后台时限已到，可继续"
        work?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
    private fun notification(file: File, state: TranscriptionUiState, finished: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(FILE, file.name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, TranscriptionService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = if (state.total > 1 && !finished) "${state.provider.displayName} · ${state.completed}/${state.total} 段" else if (finished && !state.complete) "点击继续" else "${state.provider.displayName} · 小蝠"
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_transcription).setContentTitle(state.status.ifBlank { "正在转写" })
            .setContentText(detail).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(!finished).setAutoCancel(finished).setShowWhen(false)
            .setCategory(if (finished) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setPriority(NotificationCompat.PRIORITY_LOW)
        if (!finished) {
            builder.setProgress(100, ((state.progress ?: 0f) * 100).toInt(), state.progress == null)
                .addAction(0, "取消", cancel)
            // Public platform extra (API 36.1), ignored on older versions. Standard notification
            // style is eligible; no overlay, private OEM extras or custom RemoteViews.
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        }
        return builder.build()
    }
    companion object {
        const val FILE = "transcription_file"
        const val PROVIDER = "transcription_provider"
        internal const val CHANNEL = "transcription_live_activity"
        internal const val ID = 2101
        private const val CANCEL = "dev.local.audio.CANCEL_TRANSCRIPTION"
        private const val MAX_RUNTIME = 6 * 60 * 60 * 1000L
        internal var engineFactory: (Context) -> TranscriptionEngine = { TranscriptionEngine(it) }
        internal fun safeAudio(context: Context, name: String): File? {
            val directory = File(context.filesDir, "exports").canonicalFile
            val file = File(directory, name).canonicalFile
            return file.takeIf { it.parentFile == directory && it.isFile && it.extension in setOf("wav", "m4a") }
        }
        fun start(context: Context, file: File, provider: AsrProvider = ApiKeyStore.getPreferredProvider(context)) {
            check(safeAudio(context, file.name) == file.canonicalFile) { "音频不存在" }
            check(TranscriptionTasks.begin(file, provider)) { "已有转写任务正在进行" }
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TranscriptionService::class.java)
                        .putExtra(FILE, file.name)
                        .putExtra(PROVIDER, provider.id)
                )
            } catch (e: Exception) {
                TranscriptionTasks.update(context, file) { it.copy(busy = false, status = "后台任务未启动", provider = provider) }
                throw e
            }
        }
        fun start(context: Context, file: File) = start(context, file, ApiKeyStore.getPreferredProvider(context))
        fun cancel(context: Context) { context.startService(Intent(context, TranscriptionService::class.java).setAction(CANCEL)) }
    }
}
