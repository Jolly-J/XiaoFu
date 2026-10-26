package dev.local.audio

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
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

internal enum class MotionPhase { Idle, Entering, Dragging, Returning, Leaving }

internal val LocalMotionProgress = compositionLocalOf { 0f }
internal val LocalMotionPhase = compositionLocalOf { MotionPhase.Idle }

/**
 * One progress drives the outgoing page, themed mask, and actual previous page.
 * Keeps all live routes composed (and per-route saveable state) until the pop completes.
 * Supports Container Morphing for audio-to-transcript workflows, and layered horizontal slide for settings/history.
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
    val standardCurve = remember { CubicBezierEasing(0.12f, 0.96f, 0.22f, 1.0f) }
    val morphExitSpring = remember {
        spring<Float>(
            dampingRatio = 0.82f,
            stiffness = 260f,
            visibilityThreshold = 0.0005f
        )
    }
    val morphEnterSpring = remember {
        spring<Float>(
            dampingRatio = 0.65f,
            stiffness = 220f,
            visibilityThreshold = 0.0005f
        )
    }

    fun settle(end: Double, spec: AnimationSpec<Float>, finished: () -> Unit) {
        motionJob?.cancel()
        val token = ++generation
        motionJob = scope.launch {
            val value = Animatable(progress.toFloat())
            value.animateTo(end.toFloat(), spec) {
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
        val leavingMorph = displayed.lastOrNull()?.startsWith("transcript:") == true
        if (leavingMorph) {
            settle(1.0, morphExitSpring) { completePop(destination, true) }
        } else {
            settle(1.0, tween(320, easing = standardCurve)) { completePop(destination, true) }
        }
    }

    LaunchedEffect(backStack) {
        if (backStack == displayed) return@LaunchedEffect
        motionJob?.cancel()
        generation++
        val enteringMorph = backStack.lastOrNull()?.startsWith("transcript:") == true
        val leavingMorph = displayed.lastOrNull()?.startsWith("transcript:") == true
        if (backStack.size < displayed.size && displayed.take(backStack.size) == backStack) {
            phase = MotionPhase.Leaving
            if (leavingMorph) {
                settle(1.0, morphExitSpring) { completePop(currentStack.toList(), false) }
            } else {
                val baseDur = 340
                val dur = (baseDur * (1 - progress)).toInt().coerceIn(180, baseDur)
                settle(1.0, tween(dur, easing = standardCurve)) { completePop(currentStack.toList(), false) }
            }
        } else if (backStack.size > displayed.size && backStack.take(displayed.size) == displayed) {
            displayed = backStack.toList()
            progress = 1.0
            phase = MotionPhase.Entering
            if (enteringMorph) {
                settle(0.0, morphEnterSpring) { progress = 0.0; phase = MotionPhase.Idle }
            } else {
                settle(0.0, tween(340, easing = standardCurve)) { progress = 0.0; phase = MotionPhase.Idle }
            }
        } else {
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
                    val leavingMorph = displayed.lastOrNull()?.startsWith("transcript:") == true
                    if (leavingMorph) {
                        settle(1.0, morphExitSpring) { completePop(destination, true) }
                    } else {
                        val baseDur = 340
                        val duration = if (receivedGesture) (baseDur * (1 - progress)).toInt().coerceIn(180, baseDur) else baseDur
                        settle(1.0, tween(duration, easing = standardCurve)) { completePop(destination, true) }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (generation == token && scope.isActive) {
                    motionJob?.cancel()
                    phase = MotionPhase.Returning
                    val morph = displayed.lastOrNull()?.startsWith("transcript:") == true
                    if (morph) {
                        settle(0.0, morphEnterSpring) {
                            progress = 0.0
                            phase = MotionPhase.Idle
                        }
                    } else {
                        val baseDur = 320
                        settle(0.0, tween((baseDur * progress).toInt().coerceIn(180, baseDur), easing = standardCurve)) {
                            progress = 0.0
                            phase = MotionPhase.Idle
                        }
                    }
                }
            }
        }
    }

    val transitioning = phase != MotionPhase.Idle && displayed.size > 1
    val isMorphRoute = displayed.lastOrNull()?.startsWith("transcript:") == true
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().clipToBounds().background(colors.background)) {
        displayed.forEachIndexed { index, route ->
            val foreground = index == displayed.lastIndex
            val previous = index == displayed.lastIndex - 1
            if (foreground && transitioning && !isMorphRoute) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = backLayers(progress).maskAlpha }
                    .background(colors.background))
            }
            val interactive = foreground && phase == MotionPhase.Idle
            val semantics = if (interactive) Modifier else Modifier.clearAndSetSemantics { }
            key(route) {
                Box(Modifier.fillMaxSize().then(semantics).graphicsLayer {
                    if (isMorphRoute) {
                        if (foreground) {
                            alpha = 1f
                            translationX = 0f
                        } else if (previous) {
                            val p = progress.toFloat().coerceIn(0f, 1f)
                            val scale = 0.94f + 0.06f * p
                            scaleX = scale
                            scaleY = scale
                            alpha = (0.45f + 0.55f * p).coerceIn(0f, 1f)
                            translationX = 0f
                        } else {
                            alpha = 0f
                        }
                    } else {
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
                    }
                }.pointerInput(interactive) {
                    if (!interactive) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }) {
                    CompositionLocalProvider(
                        LocalMotionProgress provides progress.toFloat(),
                        LocalMotionPhase provides phase
                    ) {
                        savedPages.SaveableStateProvider(route) { content(route, ::requestBack) }
                    }
                }
            }
        }
    }
}
