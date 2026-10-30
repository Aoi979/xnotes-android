package com.xnotes.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons

/** Material owns the shape, colours, elevation and spatial transitions; only navigation is custom. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AdaptiveFloatingToolbar(
    motion: ToolbarMotion,
    compactContent: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val touchExploration = rememberTouchExploration()
    val expanded = motion.expanded || touchExploration
    val vertical = LocalBar.current.vertical
    val scroll = rememberScrollState()
    val stateLabel = stringResource(if (expanded) R.string.toolbar_expanded else R.string.toolbar_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.toolbar_collapse else R.string.toolbar_expand)
    DisposableEffect(motion) {
        motion.enabled = true
        onDispose { motion.enabled = false; motion.reset() }
    }
    SideEffect {
        motion.thresholdDp = FloatingToolbarDefaults.ScrollDistanceThreshold.value.toDouble()
        motion.allowAutoCollapse = !touchExploration
        if (touchExploration) motion.reset()
    }
    val controls: @Composable () -> Unit = {
        compactContent?.invoke()
        Tooltip(actionLabel) {
            IconButton(
                onClick = motion::toggle,
                shapes = IconButtonDefaults.shapes(),
                enabled = !touchExploration,
                modifier = Modifier.size(48.dp).semantics { stateDescription = stateLabel },
            ) {
                AnimatedContent(expanded, transitionSpec = { materialFadeThrough(smallSurface = true) }, label = "toolbarToggle") { open ->
                    Icon(
                        if (!open) XnotesIcons.menu else if (vertical) XnotesIcons.chevronDown else XnotesIcons.next,
                        contentDescription = actionLabel,
                    )
                }
            }
        }
        trailing?.invoke()
    }
    BoxWithConstraints {
        // The persistent actions get their space first, even in a narrow split pane. The user's
        // complete tool layout scrolls inside the expanding slot instead of covering those actions.
        val pinned = 48.dp + (if (compactContent != null) 48.dp else 0.dp) + (if (trailing != null) 60.dp else 0.dp)
        val available = ((if (vertical) maxHeight else maxWidth) - pinned - 32.dp).coerceAtLeast(0.dp)
        if (vertical) {
            VerticalFloatingToolbar(
                expanded = expanded,
                modifier = Modifier.semantics { stateDescription = stateLabel },
                leadingContent = {
                    Column(
                        Modifier.heightIn(max = available).verticalScroll(scroll),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { content() }
                },
            ) { controls() }
        } else {
            HorizontalFloatingToolbar(
                expanded = expanded,
                modifier = Modifier.semantics { stateDescription = stateLabel },
                leadingContent = {
                    Row(
                        Modifier.widthIn(max = available).horizontalScroll(scroll),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { content() }
                },
            ) { controls() }
        }
    }
}

@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        enabled = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
