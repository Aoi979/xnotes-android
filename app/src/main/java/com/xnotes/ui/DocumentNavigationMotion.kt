package com.xnotes.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// M3 forward/back navigation: the Android reference uses a 30dp horizontal travel,
// motionDurationLong1 and motionEasingEmphasizedInterpolator. Page transitions still use
// easing/duration in the M3 specification; the app's M3E effects token supplies the quick fade.
private const val DOCUMENT_NAVIGATION_DURATION_MS = 450
private val documentNavigationEasing = PathEasing(Path().apply {
    moveTo(0f, 0f)
    cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
    cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
})

@Composable
internal fun documentNavigationDistancePx(): Int {
    val distance = with(LocalDensity.current) { 30.dp.roundToPx() }
    return if (LocalLayoutDirection.current == LayoutDirection.Ltr) distance else -distance
}

/** The editor enters from the trailing edge and returns towards it, in either orientation. */
internal fun documentEnter(distancePx: Int): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(DOCUMENT_NAVIGATION_DURATION_MS, easing = documentNavigationEasing),
        initialOffsetX = { distancePx },
    ) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec())

internal fun documentExit(distancePx: Int): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(DOCUMENT_NAVIGATION_DURATION_MS, easing = documentNavigationEasing),
        targetOffsetX = { distancePx },
    ) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec())

/** Keep Home's scroll/navigation state mounted while its whole surface leaves and re-enters. */
@Composable
internal fun Modifier.documentBackstageMotion(transition: Transition<Boolean>): Modifier {
    val distance = documentNavigationDistancePx().toFloat()
    val offset = transition.animateFloat(
        transitionSpec = { tween(DOCUMENT_NAVIGATION_DURATION_MS, easing = documentNavigationEasing) },
        label = "home document offset",
    ) { open -> if (open) -distance else 0f }
    val opacity = transition.animateFloat(
        transitionSpec = { XnotesMotion.fastEffectsSpec() },
        label = "home document opacity",
    ) { open -> if (open) 0f else 1f }
    return graphicsLayer {
        translationX = offset.value
        alpha = opacity.value
    }
}
