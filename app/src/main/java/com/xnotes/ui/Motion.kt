package com.xnotes.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset

/** One official M3 Expressive motion scheme shared by Material components and app transitions. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val xnotesMotionScheme = MotionScheme.expressive()

/** M3 Expressive tokens for components, supporting UI transitions and page opacity. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object XnotesMotion {
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = xnotesMotionScheme.fastSpatialSpec()

    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = xnotesMotionScheme.defaultSpatialSpec()

    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = xnotesMotionScheme.slowSpatialSpec()

    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = xnotesMotionScheme.fastEffectsSpec()

    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = xnotesMotionScheme.defaultEffectsSpec()

    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = xnotesMotionScheme.slowEffectsSpec()
}

internal val materialItemFadeSpec = XnotesMotion.fastEffectsSpec<Float>()
internal val materialItemPlacementSpec = XnotesMotion.defaultSpatialSpec<IntOffset>()

/** Keep nullable content mounted while its AnimatedVisibility runs the exit transition. */
@Composable
internal fun <T : Any> rememberExitContent(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    SideEffect { if (value != null) last.value = value }
    return value ?: last.value
}

/**
 * Peer destinations have no shared spatial anchor. Fade through while making a restrained scale
 * shift, so a switch reads as a change of context rather than a surface travelling in from nowhere.
 */
internal fun <T> AnimatedContentTransitionScope<T>.materialFadeThrough(
    smallSurface: Boolean = false,
): ContentTransform {
    val spatial: FiniteAnimationSpec<Float> = if (smallSurface) {
        XnotesMotion.fastSpatialSpec()
    } else {
        XnotesMotion.defaultSpatialSpec()
    }
    // Fade through is deliberately sequential: the old content clears before the new template or
    // tab appears. Keeping these alpha phases separate prevents two dense grids from ghosting over
    // one another; the incoming scale still uses the M3 spatial spring.
    val fadeOutMs = if (smallSurface) 60 else 90
    val fadeInMs = if (smallSurface) 140 else 180
    return (fadeIn(
        animationSpec = tween(
            durationMillis = fadeInMs,
            delayMillis = fadeOutMs,
            easing = LinearOutSlowInEasing,
        ),
    ) + scaleIn(
        initialScale = if (smallSurface) 0.97f else 0.96f,
        animationSpec = spatial,
    )) togetherWith fadeOut(
        animationSpec = tween(durationMillis = fadeOutMs, easing = FastOutLinearInEasing),
    )
}

/**
 * Shared-axis motion connects parent/child destinations. A short horizontal path establishes
 * direction while the effects spring keeps the crossfade quiet and immediately interruptible.
 */
internal fun materialSharedAxisX(forward: Boolean): ContentTransform =
    materialSharedAxisXEnter(forward) togetherWith materialSharedAxisXExit(forward)

internal fun materialSharedAxisXEnter(
    forward: Boolean,
    slow: Boolean = false,
): EnterTransition {
    val direction = if (forward) 1 else -1
    val spatial: FiniteAnimationSpec<IntOffset> = if (slow) XnotesMotion.slowSpatialSpec() else XnotesMotion.defaultSpatialSpec()
    val slide = slideInHorizontally(
        animationSpec = spatial,
        initialOffsetX = { width -> (width * SHARED_AXIS_TRAVEL_FRACTION * direction).toInt() },
    )
    return slide + fadeIn(animationSpec = XnotesMotion.defaultEffectsSpec())
}

internal fun materialSharedAxisXExit(
    forward: Boolean,
    slow: Boolean = false,
): ExitTransition {
    val direction = if (forward) -1 else 1
    val spatial: FiniteAnimationSpec<IntOffset> = if (slow) XnotesMotion.slowSpatialSpec() else XnotesMotion.defaultSpatialSpec()
    val slide = slideOutHorizontally(
        animationSpec = spatial,
        targetOffsetX = { width -> (width * SHARED_AXIS_TRAVEL_FRACTION * direction).toInt() },
    )
    return slide + fadeOut(animationSpec = XnotesMotion.defaultEffectsSpec())
}

/** Shared-axis counterpart for destinations arranged along the vertical split axis. */
internal fun materialSharedAxisYEnter(
    forward: Boolean,
    slow: Boolean = false,
): EnterTransition {
    val direction = if (forward) 1 else -1
    val spatial: FiniteAnimationSpec<IntOffset> = if (slow) XnotesMotion.slowSpatialSpec() else XnotesMotion.defaultSpatialSpec()
    val slide = slideInVertically(
        animationSpec = spatial,
        initialOffsetY = { height -> (height * SHARED_AXIS_TRAVEL_FRACTION * direction).toInt() },
    )
    return slide + fadeIn(animationSpec = XnotesMotion.defaultEffectsSpec())
}

internal fun materialSharedAxisYExit(
    forward: Boolean,
    slow: Boolean = false,
): ExitTransition {
    val direction = if (forward) -1 else 1
    val spatial: FiniteAnimationSpec<IntOffset> = if (slow) XnotesMotion.slowSpatialSpec() else XnotesMotion.defaultSpatialSpec()
    val slide = slideOutVertically(
        animationSpec = spatial,
        targetOffsetY = { height -> (height * SHARED_AXIS_TRAVEL_FRACTION * direction).toInt() },
    )
    return slide + fadeOut(animationSpec = XnotesMotion.defaultEffectsSpec())
}

/** A full-screen creation surface rises from the workspace on M3's slower full-screen spring. */
internal fun materialModalEnter(): EnterTransition =
    slideInVertically(
        animationSpec = XnotesMotion.slowSpatialSpec(),
        initialOffsetY = { height -> (height * MODAL_TRAVEL_FRACTION).toInt() },
    ) + fadeIn(animationSpec = XnotesMotion.slowEffectsSpec())

internal fun materialModalExit(): ExitTransition =
    slideOutVertically(
        animationSpec = XnotesMotion.slowSpatialSpec(),
        targetOffsetY = { height -> (height * MODAL_TRAVEL_FRACTION).toInt() },
    ) + fadeOut(animationSpec = XnotesMotion.slowEffectsSpec())

/** A small, short-lived hint arrives from its anchor and settles without demanding attention. */
internal fun materialHintEnter(): EnterTransition =
    slideInVertically(
        animationSpec = XnotesMotion.fastSpatialSpec(),
        initialOffsetY = { height -> -height / 3 },
    ) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec())

internal fun materialHintExit(): ExitTransition =
    slideOutVertically(
        animationSpec = XnotesMotion.fastSpatialSpec(),
        targetOffsetY = { height -> -height / 4 },
    ) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec())

/** A tab's supporting content opens from its top edge instead of appearing as a static block. */
internal fun materialExpandEnter(): EnterTransition =
    expandVertically(
        animationSpec = XnotesMotion.defaultSpatialSpec(),
        expandFrom = androidx.compose.ui.Alignment.Top,
    ) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec())

internal fun materialExpandExit(): ExitTransition =
    shrinkVertically(
        animationSpec = XnotesMotion.fastSpatialSpec(),
        shrinkTowards = androidx.compose.ui.Alignment.Top,
    ) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec())

private const val SHARED_AXIS_TRAVEL_FRACTION = 0.18f
private const val MODAL_TRAVEL_FRACTION = 0.08f
