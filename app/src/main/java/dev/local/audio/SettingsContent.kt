package dev.local.audio

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsContent(themeMode: Int, onThemeChange: (Int) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showKey by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasKey = withContext(Dispatchers.IO) { runCatching { !ApiKeyStore(context).read().isNullOrBlank() }.getOrDefault(false) } }
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
        GroupLabel("转写与后台")
        SoftSurface {
            Column {
                SettingsRow("MiniMax", if (hasKey) "已配置" else "配置 Key", Icons.Outlined.Key, MaterialTheme.colorScheme.primary) { showKey = true }
                GroupDivider()
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
                SettingsRow("关于", "1.1.5", Icons.Outlined.Info, MaterialTheme.colorScheme.tertiary) { info = true }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
    if (showKey) ApiKeySettingsDialog(onDismiss = { showKey = false }, onKeyChanged = { hasKey = it })
    if (diagnostic) AlertDialog(onDismissRequest = { diagnostic = false }, title = { Text("本地诊断") }, text = { Text("仅含设备、格式与处理阶段信息，不含文件内容和 Key。") }, confirmButton = {
        TextButton(onClick = { clipboard.setText(AnnotatedString(Diagnostics.copySummary())); diagnostic = false; message = "已复制诊断" }) { Text("复制") }
    }, dismissButton = { TextButton(onClick = { Diagnostics.clear(); diagnostic = false; message = "已清除诊断" }) { Text("清除") } })
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text("声屿") }, text = { Text("视频转音频，本地完成。转文字使用 MiniMax。\n\n音频和文字保存在此设备，卸载应用会删除，请另存重要文件。\n\n实时活动的展示由系统决定。识别结果请核对。") }, confirmButton = { TextButton(onClick = { info = false }) { Text("知道了") } })
    message?.let { value -> AlertDialog(onDismissRequest = { message = null }, text = { Text(value) }, confirmButton = { TextButton(onClick = { message = null }) { Text("好") } }) }
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
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 10.dp))
}
@Composable
private fun GroupDivider() { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp) }
