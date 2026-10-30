package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.settings.Preferences
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

/** Toolbar-specific editing and drag lifetime; the settings host only owns navigation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolbarPreferences(editor: Editor, preferences: State<Preferences>, canvasTab: Boolean,
    onCanvasTabChange: (Boolean) -> Unit, scrollState: LazyListState, active: Boolean,
    updateHome: (Preferences) -> Unit) {
    val palette = LocalPalette.current
    val prefs by preferences
    var resetToolbarCanvas by remember { mutableStateOf<Boolean?>(null) }
    // Which bar the toolbar section is arranging. The two are separate layouts, so the drag state
    // below always belongs to whichever is on screen.
    val layout = if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout
    // Toolbar drag state, hoisted here so the floating "ghost" chip can be drawn at the pane
    // root (above and outside the scroll, so it is never clipped). Bounds are plain maps read
    // only at drag time; observable state is just the dragged item, finger, and drop slot.
    var dragItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var dragFinger by remember { mutableStateOf(Offset.Zero) }
    var dragGrab by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val chipBounds = remember { mutableMapOf<ToolbarItem, Rect>() }
    val sectionBounds = remember { mutableMapOf<Int, Rect>() }
    val sectionGrabBounds = remember { mutableMapOf<Int, Rect>() }
    // Section-drag state (the whole card, by its grip handle); shares the finger/grab with the
    // chip drag since only one gesture runs at a time. The drop slot is an insertion index.
    var dragSection by remember { mutableStateOf<Int?>(null) }
    var sectionDropTarget by remember { mutableStateOf<Int?>(null) }
    var paneTopLeft by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    fun editedLayout(): ToolbarLayout = if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout
    fun applyEdited(next: ToolbarLayout) {
        if (canvasTab) editor.applyCanvasToolbarLayout(next) else editor.applyToolbarLayout(next)
    }
    fun retarget() {
        dropTarget = toolbarDropTarget(editedLayout(), dragFinger, chipBounds, sectionBounds)
    }
    fun retargetSection() {
        sectionDropTarget = toolbarSectionDropTarget(editedLayout(), dragFinger, sectionBounds)
    }
    fun showTab(canvas: Boolean) {
        if (canvasTab == canvas) return
        onCanvasTabChange(canvas)
        // The bounds maps are keyed by item and the two bars share most items, so stale entries
        // from the other tab would aim a drop at a chip that is no longer there.
        chipBounds.clear()
        sectionBounds.clear()
        sectionGrabBounds.clear()
        dragItem = null
        dragSection = null
        dropTarget = null
        sectionDropTarget = null
    }


    Box(Modifier.fillMaxSize().onGloballyPositioned { paneTopLeft = it.boundsInRoot().topLeft }) {
        LazyColumn(modifier = Modifier.fillMaxSize().imePadding().onGloballyPositioned { viewport = it.boundsInRoot() },
            state = scrollState, contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "settings-scope", contentType = "description") { PreferenceScopeHint() }
            // Each appearance control and the drag editor are independent lazy items.
            val look = prefs.toolbarLook

            preferenceItem("toolbar-collapsing") {
                PreferenceFieldLabel(stringResource(R.string.pref_toolbar_collapsing))
                SettingsSwitchRow(stringResource(R.string.pref_toolbar_collapsing_notes), look.collapsingNotes) {
                    updateHome(prefs.copy(toolbarLook = prefs.toolbarLook.copy(collapsingNotes = it)))
                }
                SettingsSwitchRow(stringResource(R.string.pref_toolbar_collapsing_canvas), look.collapsingCanvas) {
                    updateHome(prefs.copy(toolbarLook = prefs.toolbarLook.copy(collapsingCanvas = it)))
                }
                Text(
                    stringResource(R.string.pref_toolbar_collapsing_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textDim.toComposeColor(),
                )
            }

            if (!look.collapsingNotes || !look.collapsingCanvas) {
            preferenceItem("toolbar-style") {
                PreferenceFieldLabel(stringResource(R.string.pref_toolbar_style))
                ExpressiveChoiceGroup(
                    options = listOf(
                        false to stringResource(R.string.toolbar_docked),
                        true to stringResource(R.string.toolbar_floating),
                    ),
                    selected = look.floating,
                ) { floating -> updateHome(prefs.copy(toolbarLook = prefs.toolbarLook.copy(floating = floating))) }
            }

            }
            preferenceItem("toolbar-position") {
                PreferenceFieldLabel(stringResource(R.string.pref_toolbar_position))
                ExpressiveChoiceGroup(
                    options = listOf(
                        ToolbarPosition.TOP to stringResource(R.string.edge_top),
                        ToolbarPosition.BOTTOM to stringResource(R.string.edge_bottom),
                        ToolbarPosition.LEFT to stringResource(R.string.edge_left),
                        ToolbarPosition.RIGHT to stringResource(R.string.edge_right),
                    ),
                    selected = look.position,
                ) { position -> updateHome(prefs.copy(toolbarLook = prefs.toolbarLook.copy(position = position))) }
            }

            if (!look.collapsingNotes || !look.collapsingCanvas) {
            preferenceItem("toolbar-size") {
                PreferenceFieldLabel(stringResource(R.string.pref_toolbar_size))
                ExpressiveChoiceGroup(
                    options = listOf(
                        ToolbarSize.COMPACT to stringResource(R.string.toolbar_size_compact),
                        ToolbarSize.REGULAR to stringResource(R.string.toolbar_size_regular),
                        ToolbarSize.COMFORTABLE to stringResource(R.string.toolbar_size_comfortable),
                    ),
                    selected = look.size,
                ) { size -> updateHome(prefs.copy(toolbarLook = prefs.toolbarLook.copy(size = size))) }
            }

            }
            preferenceItem("toolbar-colours") {
                PreferenceFieldLabel(stringResource(R.string.pref_toolbar_colours_n, editor.toolbarColorCount))
                Slider(
                    value = editor.toolbarColorCount.toFloat(),
                    onValueChange = { editor.applyToolbarColorCount(Math.round(it)) },
                    valueRange = 1f..InkPalette.MAX_SWATCHES.toFloat(),
                    steps = InkPalette.MAX_SWATCHES - 2,
                    modifier = Modifier.width(280.dp),
                )
            }

            preferenceItem("toolbar-tab") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExpressiveChoiceGroup(
                        options = listOf(false to stringResource(R.string.pref_toolbar_collapsing_notes), true to stringResource(R.string.pref_toolbar_collapsing_canvas)),
                        selected = canvasTab,
                        onSelect = { showTab(it) },
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { resetToolbarCanvas = canvasTab },
                    ) { Text(stringResource(R.string.reset), fontSize = 13.sp) }
                }
                Text(
                    stringResource(R.string.pref_toolbar_help),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                )
            }

            item(key = "toolbar-customizer", contentType = "toolbar-customizer") {
                val pinnable = LocalPinnableContainer.current
                val dragging = dragItem != null || dragSection != null
                // Edge auto-scroll can move this item outside the viewport mid-gesture. Keep
                // its pointer handler alive until drop/cancel, then allow normal recycling.
                DisposableEffect(pinnable, dragging) {
                    val pin = if (dragging) pinnable?.pin() else null
                    onDispose { pin?.release() }
                }
                // A disposed/recreated lazy item must never leave stale drop coordinates behind.
                DisposableEffect(Unit) {
                    onDispose {
                        chipBounds.clear()
                        sectionBounds.clear()
                        sectionGrabBounds.clear()
                        dragItem = null
                        dragSection = null
                        dropTarget = null
                        sectionDropTarget = null
                    }
                }
                ToolbarCustomizerBody(
                    layout = layout,
                    dragItem = dragItem,
                    dropTarget = dropTarget,
                    dragSection = dragSection,
                    sectionDropTarget = sectionDropTarget,
                    chipBounds = chipBounds,
                    sectionBounds = sectionBounds,
                    sectionGrabBounds = sectionGrabBounds,
                    onToggle = { s, i -> applyEdited(editedLayout().toggleVisible(s, i)) },
                    onDelete = { s -> applyEdited(editedLayout().removeSection(s)) },
                    onAdd = { applyEdited(editedLayout().addSection()) },
                    onDragStart = { item, local ->
                        dragItem = item
                        dragGrab = local
                        dragFinger = (chipBounds[item]?.topLeft ?: Offset.Zero) + local
                        retarget()
                    },
                    onDrag = { delta -> dragFinger += delta; retarget() },
                    onDrop = {
                        val target = dropTarget
                        val moving = dragItem
                        if (target != null && moving != null) {
                            val cur = editedLayout()
                            val fSec = cur.sections.indexOfFirst { s -> s.entries.any { it.item == moving } }
                            val fIdx = if (fSec >= 0) cur.sections[fSec].entries.indexOfFirst { it.item == moving } else -1
                            if (fSec >= 0 && fIdx >= 0) {
                                applyEdited(cur.moveItem(fSec, fIdx, target.first, target.second))
                            }
                        }
                        dragItem = null
                        dropTarget = null
                    },
                    onCancel = {
                        dragItem = null
                        dropTarget = null
                    },
                    onSectionDragStart = { sec, local ->
                        dragSection = sec
                        val grab = sectionGrabBounds[sec]?.topLeft ?: sectionBounds[sec]?.topLeft ?: Offset.Zero
                        val card = sectionBounds[sec]?.topLeft ?: Offset.Zero
                        dragFinger = grab + local
                        dragGrab = dragFinger - card
                        retargetSection()
                    },
                    onSectionDrag = { delta -> dragFinger += delta; retargetSection() },
                    onSectionDrop = {
                        val from = dragSection
                        val insertAt = sectionDropTarget
                        if (from != null && insertAt != null) {
                            val cur = editedLayout()
                            val to = (if (insertAt > from) insertAt - 1 else insertAt).coerceIn(0, cur.sections.lastIndex)
                            if (to != from) applyEdited(cur.moveSection(from, to))
                        }
                        dragSection = null
                        sectionDropTarget = null
                    },
                    onSectionCancel = {
                        dragSection = null
                        sectionDropTarget = null
                    },
                )
            }
        }
        // The dragged chip/section's floating copy: a pane-root sibling so the scroll never clips it.
        // Read the moving coordinates in placement, without recomposing the settings per pixel.
        fun ghostOffset() = IntOffset(
            (dragFinger.x - dragGrab.x - paneTopLeft.x).toInt(),
            (dragFinger.y - dragGrab.y - paneTopLeft.y).toInt(),
        )
        val ghostChip = dragItem
        val ghostSecIdx = dragSection
        if (ghostChip != null) {
            Box(Modifier.offset { ghostOffset() }) { ToolbarDragGhost(ghostChip) }
        } else if (ghostSecIdx != null) {
            val sectionG = (if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout)
                .sections.getOrNull(ghostSecIdx)
            val widthG = sectionBounds[ghostSecIdx]?.width
            if (sectionG != null && widthG != null) {
                Box(Modifier.offset { ghostOffset() }) { SectionCardGhost(sectionG, widthG) }
            }
        }
    }

    resetToolbarCanvas?.let { forCanvas ->
        AlertDialog(onDismissRequest = { resetToolbarCanvas = null }, title = { Text(stringResource(R.string.pref_reset_toolbar_title)) },
            text = { Text(stringResource(R.string.pref_reset_toolbar_help, stringResource(if (forCanvas) R.string.pref_toolbar_collapsing_canvas else R.string.pref_toolbar_collapsing_notes))) },
            confirmButton = { TextButton({ resetToolbarCanvas = null; applyEdited(if (forCanvas) ToolbarLayout.CANVAS_DEFAULT else ToolbarLayout.DEFAULT) }) { Text(stringResource(R.string.reset)) } },
            dismissButton = { TextButton({ resetToolbarCanvas = null }) { Text(stringResource(R.string.cancel)) } })
    }
    LaunchedEffect(active) {
        if (!active) {
            dragItem = null; dragSection = null; dropTarget = null; sectionDropTarget = null
            chipBounds.clear(); sectionBounds.clear(); sectionGrabBounds.clear()
        }
    }
    // While dragging near a vertical edge of the scroll viewport, ease the list along so items
    // off-screen can be reached without lifting the finger.
    LaunchedEffect(dragItem != null || dragSection != null) {
        while (dragItem != null || dragSection != null) {
            val band = 90f
            val y = dragFinger.y
            val delta = when {
                y < viewport.top + band -> -((viewport.top + band - y) / band) * 14f
                y > viewport.bottom - band -> ((y - (viewport.bottom - band)) / band) * 14f
                else -> 0f
            }
            if (delta != 0f) {
                scrollState.scrollBy(delta)
                if (dragItem != null) retarget() else retargetSection()
            }
            delay(16L)
        }
    }
}
