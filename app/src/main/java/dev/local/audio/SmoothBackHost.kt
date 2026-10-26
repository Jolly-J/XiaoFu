package dev.local.audio

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class MotionPhase { Idle, Entering, Dragging, Returning, Leaving }

/**
 * One progress drives the outgoing page, themed mask, and actual previous page.
 * Keeps all live routes composed (and per-route saveable state) until the pop completes.
 * Geometry deliberately contains only horizontal translation and opacity: scale is always 1.
 */
@Composable
internal fun SmoothBackHost(
    backStack: List<String>,
    onPop: () -> Unit,
    content: @Composable (route: String, requestBack: () -> Unit) -> Unit,
) {
    require(backStack.isNotEmpty())
    var displayed by remember { mutableStateOf(backStack.toList()) }
    var phase by remember { mutableStateOf(MotionPhase.Idle) }
    var progress by remember { mutableDoubleStateOf(0.0) }
    var target by remember { mutableDoubleStateOf(0.0) }
    var generation by remember { mutableIntStateOf(0) }
    var motionJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val savedPages = rememberSaveableStateHolder()
    val currentPop by rememberUpdatedState(onPop)
    val currentStack by rememberUpdatedState(backStack)
    val settleCurve = remember { CubicBezierEasing(.18f, 1f, .04f, 1f) }

    fun settle(end: Double, duration: Int, finished: () -> Unit) {
        motionJob?.cancel()
        val token = ++generation
        motionJob = scope.launch {
            val value = Animatable(progress.toFloat())
            value.animateTo(end.toFloat(), tween(duration.coerceAtLeast(1), easing = settleCurve)) {
                if (generation == token) progress = this.value.toDouble()
            }
            if (generation == token) finished()
        }
    }

    fun completePop(destination: List<String>, notify: Boolean) {
        displayed = destination
        progress = 0.0
        phase = MotionPhase.Idle
        if (notify) currentPop()
    }

    fun requestBack() {
        if (displayed.size < 2 || phase != MotionPhase.Idle) return
        val destination = displayed.dropLast(1)
        phase = MotionPhase.Leaving
        // Programmatic maybePop-like return follows the same three layers.
        settle(1.0, 300) { completePop(destination, true) }
    }

    LaunchedEffect(backStack) {
        if (backStack == displayed) return@LaunchedEffect
        motionJob?.cancel()
        generation++
        if (backStack.size < displayed.size && displayed.take(backStack.size) == backStack) {
            phase = MotionPhase.Leaving
            settle(1.0, (800 * (1 - progress)).toInt()) { completePop(currentStack.toList(), false) }
        } else if (backStack.size > displayed.size && backStack.take(displayed.size) == displayed) {
            displayed = backStack.toList()
            progress = 1.0
            phase = MotionPhase.Entering
            settle(0.0, 300) { progress = 0.0; phase = MotionPhase.Idle }
        } else {
            // Replacement/deep link: stale gestures must never pop the new target.
            displayed = backStack.toList()
            progress = 0.0
            phase = MotionPhase.Idle
        }
    }

    PredictiveBackHandler(enabled = displayed.size > 1) { events ->
        if (phase == MotionPhase.Entering || phase == MotionPhase.Leaving) {
            events.collect { } // Swallow rapid duplicate Back while a route is settling.
        } else {
            motionJob?.cancel()
            val token = ++generation
            val destination = displayed.dropLast(1)
            val spring = BackProgressSpring(progress)
            target = 0.0
            phase = MotionPhase.Dragging
            motionJob = scope.launch {
                var previous: Long? = null
                while (isActive && generation == token && phase == MotionPhase.Dragging) {
                    withFrameNanos { now ->
                        previous?.let { then -> progress = spring.step(target, (now - then) / 1_000_000_000.0) }
                        previous = now
                    }
                }
            }
            var receivedGesture = false
            try {
                events.collect { event ->
                    receivedGesture = true
                    if (generation == token) target = dampedBackTarget(event.progress.toDouble())
                }
                if (generation == token) {
                    motionJob?.cancel()
                    phase = MotionPhase.Leaving
                    // Same continuation as Flutter TransitionRoute: from current p, never reset.
                    val duration = if (receivedGesture) (800 * (1 - progress)).toInt() else 300
                    settle(1.0, duration) { completePop(destination, true) }
                }
            } catch (cancelled: CancellationException) {
                // AndroidX cancels the gesture coroutine on onBackCancelled. Resume in
                // the still-live host scope; lifecycle disposal cancels that scope too.
                if (generation == token && scope.isActive) {
                    motionJob?.cancel()
                    phase = MotionPhase.Returning
                    settle(0.0, (800 * progress).toInt().coerceAtMost(300)) {
                        progress = 0.0
                        phase = MotionPhase.Idle
                    }
                }
            }
        }
    }

    val transitioning = phase != MotionPhase.Idle && displayed.size > 1
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().clipToBounds().background(colors.background)) {
        displayed.forEachIndexed { index, route ->
            val foreground = index == displayed.lastIndex
            val previous = index == displayed.lastIndex - 1
            if (foreground && transitioning) {
                // Source uses both incoming opacity and a separate mask. Do not replace with
                // an ordinary two-page crossfade: that exposes overlapping text too early.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = backLayers(progress).maskAlpha }
                    .background(colors.background))
            }
            val interactive = foreground && phase == MotionPhase.Idle
            val semantics = if (interactive) Modifier else Modifier.clearAndSetSemantics { }
            key(route) {
                Box(Modifier.fillMaxSize().then(semantics).graphicsLayer {
                    val layers = backLayers(progress)
                    alpha = when {
                        !transitioning && foreground -> 1f
                        transitioning && foreground -> layers.foregroundAlpha
                        transitioning && previous -> layers.backgroundAlpha
                        else -> 0f
                    }
                    translationX = if (!transitioning) 0f else when {
                        foreground -> size.width * layers.foregroundXFraction
                        previous -> size.width * layers.backgroundXFraction
                        else -> 0f
                    }
                    // No scale, corner animation, vertical drag, or page shadow.
                }.pointerInput(interactive) {
                    if (!interactive) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }) {
                    savedPages.SaveableStateProvider(route) { content(route, ::requestBack) }
                }
            }
        }
    }
}
