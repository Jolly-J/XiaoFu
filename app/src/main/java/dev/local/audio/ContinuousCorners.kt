package dev.local.audio

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Apple iOS G2 Continuous Curvature Squircle Shape.
 * Replaces standard circular arcs with pure cubic Bezier curves,
 * producing authentic Apple-grade continuous corners without curvature breaks.
 * Extends CornerBasedShape so it integrates seamlessly with MaterialTheme.shapes
 * and all Jetpack Compose container components.
 */
class ContinuousCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    val cornerSmoothing: Float = 0.72f
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(all: Dp, smoothing: Float = 0.72f) : this(
        CornerSize(all), CornerSize(all), CornerSize(all), CornerSize(all), smoothing
    )

    constructor(
        topStart: Dp,
        topEnd: Dp,
        bottomEnd: Dp,
        bottomStart: Dp,
        smoothing: Float = 0.72f
    ) : this(
        CornerSize(topStart), CornerSize(topEnd), CornerSize(bottomEnd), CornerSize(bottomStart), smoothing
    )

    constructor(percent: Int, smoothing: Float = 0.72f) : this(
        CornerSize(percent), CornerSize(percent), CornerSize(percent), CornerSize(percent), smoothing
    )

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(Rect(0f, 0f, w, h))

        val rTL = if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd
        val rTR = if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart
        val rBR = if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart
        val rBL = if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd

        val path = Path()
        buildSquirclePath(path, w, h, rTL, rTR, rBR, rBL, cornerSmoothing)
        return Outline.Generic(path)
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): CornerBasedShape = ContinuousCornerShape(topStart, topEnd, bottomEnd, bottomStart, cornerSmoothing)
}

private fun buildSquirclePath(
    path: Path,
    w: Float,
    h: Float,
    rTL: Float,
    rTR: Float,
    rBR: Float,
    rBL: Float,
    smoothing: Float
) {
    val budget = min(w, h) / 2f

    fun cornerParams(radius: Float): FloatArray {
        if (radius <= 0.001f) return FloatArray(8) { 0f }
        val r = min(radius, budget)
        val maxS = if (r > 0f) budget / r - 1f else 0f
        val curS = min(smoothing, maxOf(0f, maxS))
        val p = min((1f + curS) * r, budget)
        val arcMeasure = 90f * (1f - curS)
        val radArcHalf = Math.toRadians((arcMeasure / 2f).toDouble())
        val arcLen = (sin(radArcHalf) * r.toDouble() * sqrt(2.0)).toFloat()
        val alpha = (90f - arcMeasure) / 2f
        val p3p4 = (r.toDouble() * tan(Math.toRadians((alpha / 2f).toDouble()))).toFloat()
        val beta = 45f * curS
        val radBeta = Math.toRadians(beta.toDouble())
        val c = (p3p4.toDouble() * cos(radBeta)).toFloat()
        val d = (c.toDouble() * tan(radBeta)).toFloat()
        val b = (p - arcLen - c - d) / 3f
        val a = 2f * b
        val theta = Math.toRadians(arcMeasure.toDouble())
        val k = ((4.0 / 3.0) * tan(theta / 4.0) * r.toDouble()).toFloat()
        val cosB = cos(radBeta).toFloat()
        val sinB = sin(radBeta).toFloat()
        return floatArrayOf(p, a, b, c, d, k, cosB, sinB)
    }

    val prmTL = cornerParams(rTL)
    val prmTR = cornerParams(rTR)
    val prmBR = cornerParams(rBR)
    val prmBL = cornerParams(rBL)

    val pTL = prmTL[0]
    val pTR = prmTR[0]
    val pBR = prmBR[0]
    val pBL = prmBL[0]

    path.moveTo(pTL, 0f)
    path.lineTo(w - pTR, 0f)

    // Top-Right Corner
    if (rTR > 0.001f) {
        val a = prmTR[1]; val b = prmTR[2]; val c = prmTR[3]; val d = prmTR[4]
        val k = prmTR[5]; val cosB = prmTR[6]; val sinB = prmTR[7]
        path.relativeCubicTo(a, 0f, a + b, 0f, a + b + c, d)
        val startX = w - pTR + a + b + c
        val startY = d
        val endX = w - d
        val endY = pTR - a - b - c
        path.cubicTo(
            startX + k * cosB, startY + k * sinB,
            endX - k * sinB, endY - k * cosB,
            endX, endY
        )
        path.relativeCubicTo(d, c, d, b + c, d, a + b + c)
    }

    path.lineTo(w, h - pBR)

    // Bottom-Right Corner
    if (rBR > 0.001f) {
        val a = prmBR[1]; val b = prmBR[2]; val c = prmBR[3]; val d = prmBR[4]
        val k = prmBR[5]; val cosB = prmBR[6]; val sinB = prmBR[7]
        path.relativeCubicTo(0f, a, 0f, a + b, -d, a + b + c)
        val startX = w - d
        val startY = h - pBR + a + b + c
        val endX = w - pBR + a + b + c
        val endY = h - d
        path.cubicTo(
            startX - k * sinB, startY + k * cosB,
            endX + k * cosB, endY - k * sinB,
            endX, endY
        )
        path.relativeCubicTo(-c, d, -(b + c), d, -(a + b + c), d)
    }

    path.lineTo(pBL, h)

    // Bottom-Left Corner
    if (rBL > 0.001f) {
        val a = prmBL[1]; val b = prmBL[2]; val c = prmBL[3]; val d = prmBL[4]
        val k = prmBL[5]; val cosB = prmBL[6]; val sinB = prmBL[7]
        path.relativeCubicTo(-a, 0f, -(a + b), 0f, -(a + b + c), -d)
        val startX = pBL - a - b - c
        val startY = h - d
        val endX = d
        val endY = h - pBL + a + b + c
        path.cubicTo(
            startX - k * cosB, startY - k * sinB,
            endX + k * sinB, endY + k * cosB,
            endX, endY
        )
        path.relativeCubicTo(-d, -c, -d, -(b + c), -d, -(a + b + c))
    }

    path.lineTo(0f, pTL)

    // Top-Left Corner
    if (rTL > 0.001f) {
        val a = prmTL[1]; val b = prmTL[2]; val c = prmTL[3]; val d = prmTL[4]
        val k = prmTL[5]; val cosB = prmTL[6]; val sinB = prmTL[7]
        path.relativeCubicTo(0f, -a, 0f, -(a + b), d, -(a + b + c))
        val startX = d
        val startY = pTL - a - b - c
        val endX = pTL - a - b - c
        val endY = d
        path.cubicTo(
            startX + k * sinB, startY - k * cosB,
            endX - k * cosB, endY + k * sinB,
            endX, endY
        )
        path.relativeCubicTo(c, -d, b + c, -d, a + b + c, -d)
    }

    path.close()
}
