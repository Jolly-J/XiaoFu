package dev.local.audio

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsContent(themeMode: Int, onThemeChange: (Int) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showKey by remember { mutableStateOf(false) }
    var keyProvider by remember { mutableStateOf(AsrProvider.SILICON_FLOW) }
    var info by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var hasSiliconKey by remember { mutableStateOf(false) }
    var hasMiniMaxKey by remember { mutableStateOf(false) }
    var preferredProvider by remember { mutableStateOf(AsrProvider.SILICON_FLOW) }
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    fun refreshKeys() {
        hasSiliconKey = runCatching { !ApiKeyStore(context, AsrProvider.SILICON_FLOW).read().isNullOrBlank() }.getOrDefault(false)
        hasMiniMaxKey = runCatching { !ApiKeyStore(context, AsrProvider.MINIMAX).read().isNullOrBlank() }.getOrDefault(false)
        preferredProvider = ApiKeyStore.getPreferredProvider(context)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { refreshKeys() }
    }
    val coral = if (MaterialTheme.colorScheme.background.luminance() > .5f) Color(0xFFC24E6B) else Color(0xFFFFA7BC)
    val blue = if (MaterialTheme.colorScheme.background.luminance() > .5f) Color(0xFF286AC3) else Color(0xFF9CC6FF)
    Column(Modifier.fillMaxWidth()) {
        GroupLabel("外观")
        SoftSurface {
            Column(Modifier.selectableGroup()) {
                listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEachIndexed { index, (mode, label) ->
                    if (index > 0) GroupDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(themeMode == mode, role = Role.RadioButton, onClick = { onThemeChange(mode) }).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        AccentIcon(when (mode) { 1 -> Icons.Outlined.LightMode; 2 -> Icons.Outlined.DarkMode; else -> Icons.Outlined.BrightnessAuto },
                            when (mode) { 1 -> MaterialTheme.colorScheme.tertiary; 2 -> MaterialTheme.colorScheme.primary; else -> blue })
                        Text(label, modifier = Modifier.weight(1f).padding(start = 16.dp))
                        if (themeMode == mode) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        GroupLabel("转写服务商")
        SoftSurface {
            Column {
                SettingsRowWithRes(
                    title = AsrProvider.SILICON_FLOW.displayName,
                    featureTag = AsrProvider.SILICON_FLOW.featureTag,
                    isPreferred = preferredProvider == AsrProvider.SILICON_FLOW,
                    value = if (hasSiliconKey) "已配置" else "配置 Key",
                    iconRes = AsrProvider.SILICON_FLOW.markRes,
                    accent = MaterialTheme.colorScheme.primary
                ) {
                    keyProvider = AsrProvider.SILICON_FLOW
                    showKey = true
                }
                GroupDivider()
                SettingsRowWithRes(
                    title = AsrProvider.MINIMAX.displayName,
                    featureTag = AsrProvider.MINIMAX.featureTag,
                    isPreferred = preferredProvider == AsrProvider.MINIMAX,
                    value = if (hasMiniMaxKey) "已配置" else "配置 Key",
                    iconRes = AsrProvider.MINIMAX.markRes,
                    accent = MaterialTheme.colorScheme.secondary
                ) {
                    keyProvider = AsrProvider.MINIMAX
                    showKey = true
                }
            }
        }
        GroupLabel("系统与后台")
        SoftSurface {
            Column {
                SettingsRow("通知与实时活动", null, Icons.Outlined.NotificationsNone, coral) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                        .onFailure { message = "无法打开系统通知设置" }
                }
                GroupDivider()
                SettingsRow("后台运行", null, Icons.Outlined.BatteryChargingFull, MaterialTheme.colorScheme.secondary) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }
                        .onFailure { message = "无法打开应用设置" }
                }
            }
        }
        GroupLabel("其他")
        SoftSurface {
            Column {
                SettingsRow("诊断", null, Icons.Outlined.Tune, blue) { diagnostic = true }
                GroupDivider()
                SettingsRow("关于", appVersion, Icons.Outlined.Info, MaterialTheme.colorScheme.tertiary) { info = true }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
    if (showKey) ApiKeySettingsDialog(
        initialProvider = keyProvider,
        onDismiss = {
            showKey = false
            refreshKeys()
        },
        onKeyChanged = {
            refreshKeys()
        }
    )
    if (diagnostic) AlertDialog(
        onDismissRequest = { diagnostic = false },
        shape = ContinuousCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text("本地诊断", fontWeight = FontWeight.SemiBold) },
        text = { Text("仅含设备、格式与处理阶段信息，不含文件内容和 Key。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            Button(
                onClick = { clipboard.setText(AnnotatedString(Diagnostics.copySummary())); diagnostic = false; message = "已复制诊断" },
                shape = ContinuousCornerShape(12.dp)
            ) { Text("复制") }
        },
        dismissButton = {
            OutlinedButton(onClick = { Diagnostics.clear(); diagnostic = false; message = "已清除诊断" }, shape = ContinuousCornerShape(12.dp)) { Text("清除") }
        }
    )
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        shape = ContinuousCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.img_xiaofu_logo),
                    contentDescription = "小蝠 Logo",
                    modifier = Modifier.size(72.dp).clip(ContinuousCornerShape(18.dp))
                )
                Spacer(Modifier.height(12.dp))
                Text("小蝠", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("v$appVersion · 智能音频整理助手", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(
                    "• 视频转音频：设备本地高效处理，保护隐私。\n• 智能转写：支持硅基流动 (SenseVoiceSmall) 与 MiniMax 语音大模型，用户可自选服务商。\n• 本地存储：音频和文字均保存在此设备，卸载应用会删除，请另存重要文件。\n• 实时活动的展示由系统决定。识别结果请核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = { info = false }, shape = ContinuousCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text("知道了")
            }
        }
    )
    message?.let { value ->
        AlertDialog(
            onDismissRequest = { message = null },
            shape = ContinuousCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            text = { Text(value, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = { message = null }, shape = ContinuousCornerShape(12.dp)) { Text("好") }
            }
        )
    }
}
@Composable
private fun SettingsRow(title: String, value: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = action).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AccentIcon(icon, accent)
        Text(title, modifier = Modifier.weight(1f).padding(start = 16.dp))
        value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).padding(start = 4.dp))
    }
}

@Composable
private fun SettingsRowWithRes(
    title: String,
    featureTag: String,
    isPreferred: Boolean,
    value: String?,
    @androidx.annotation.DrawableRes iconRes: Int,
    accent: Color,
    action: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = action)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = ContinuousCornerShape(12.dp),
            color = accent.copy(alpha = 0.12f),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Row(
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            val isFree = featureTag == "免费"
            Surface(
                shape = ContinuousCornerShape(6.dp),
                color = if (isFree) Color(0xFF1B8755).copy(alpha = 0.12f) else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    featureTag,
                    color = if (isFree) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (isPreferred) {
                Surface(
                    shape = ContinuousCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        "优先",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).padding(start = 4.dp))
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 10.dp))
}
@Composable
private fun GroupDivider() { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp) }
