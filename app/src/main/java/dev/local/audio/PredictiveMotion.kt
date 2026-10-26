package dev.local.audio

import kotlin.math.ln

/** Port of 咔捕 lib/app_theme.dart's progress mapping and layered slide/fade (no scaling). */
internal fun dampedBackTarget(raw: Double): Double =
    (ln(1.0 + 2.4 * raw.coerceIn(0.0, 1.0)) / ln(3.4)).coerceIn(0.0, .96)

internal fun dampedDisplacement(progress: Double): Double =
    ln(1.0 + 4.5 * progress.coerceIn(0.0, 1.0)) / ln(5.5)

internal class BackProgressSpring(initial: Double = 0.0) {
    var position: Double = initial
        private set
    var velocity: Double = 0.0
        private set
    fun step(target: Double, elapsedSeconds: Double): Double {
        val dt = elapsedSeconds.coerceIn(.001, .033)
        velocity += (115.0 * (target - position) - 19.0 * velocity) * dt
        position = (position + velocity * dt).coerceIn(0.0, .98)
        // Flutter passes 1 - position to the route, clamped to [.01, .999].
        return position.coerceIn(.001, .99)
    }
}

/** Flutter Cubic semantics: invert x before evaluating y, not a polynomial approximation. */
internal fun cubicProgress(value: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
    val x = value.coerceIn(0.0, 1.0)
    if (x == 0.0 || x == 1.0) return x
    fun component(t: Double, a: Double, b: Double): Double {
        val inv = 1.0 - t
        return 3 * a * inv * inv * t + 3 * b * inv * t * t + t * t * t
    }
    var low = 0.0
    var high = 1.0
    repeat(25) {
        val mid = (low + high) / 2
        if (component(mid, x1, x2) < x) low = mid else high = mid
    }
    return component((low + high) / 2, y1, y2)
}

internal data class BackLayers(
    val foregroundAlpha: Float,
    val foregroundXFraction: Float,
    val backgroundAlpha: Float,
    val backgroundXFraction: Float,
    val maskAlpha: Float,
)

internal fun backLayers(progress: Double): BackLayers {
    val p = progress.coerceIn(0.0, 1.0)
    val backgroundStage = ((p - .36) / .64).coerceIn(0.0, 1.0)
    val backgroundAlpha = cubicProgress(backgroundStage, .645, .045, .355, 1.0)
    val foregroundAlpha = cubicProgress((1.0 - p / .46).coerceIn(0.0, 1.0), .215, .61, .355, 1.0)
    return BackLayers(
        foregroundAlpha.toFloat(), (.13 * dampedDisplacement(p)).toFloat(),
        backgroundAlpha.toFloat(), (-.08 * (1.0 - dampedDisplacement(backgroundStage))).toFloat(),
        (1.0 - backgroundAlpha).toFloat(),
    )
}
