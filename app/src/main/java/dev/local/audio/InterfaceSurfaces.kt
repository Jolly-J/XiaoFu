package dev.local.audio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*

/** Grouped tool panels: visible thin boundary, restrained fill, no blanket drop shadow. */
@Composable
internal fun SoftSurface(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable () -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(28.dp), color = accent?.copy(alpha = .035f)?.compositeOver(MaterialTheme.colorScheme.surface) ?: MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent?.copy(alpha = .25f)?.compositeOver(MaterialTheme.colorScheme.surface) ?: MaterialTheme.colorScheme.outlineVariant), content = content)
}

@Composable
internal fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, description) }
    }
}

@Composable
internal fun AccentIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, size: Dp = 36.dp, iconSize: Dp = 23.dp) {
    Surface(shape = RoundedCornerShape(size * .3f), color = color.copy(alpha = .12f), modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(iconSize)) }
    }
}

