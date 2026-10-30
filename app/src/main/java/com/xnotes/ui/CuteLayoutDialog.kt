package com.xnotes.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.cute.*
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.floor
import kotlinx.coroutines.launch

/** A workspace inside Canvas; its source is kept in the inserted vector asset. */
@Composable
internal fun CuteLayoutDialog(editor: InfiniteEditor, onDismiss: () -> Unit) {
    val spec = editor.cuteDraft
    val calculationKey = spec.copy(showInput = false, rowStart = 0, columnStart = 0, hardware = spec.hardware.copy(thread = -1))
    val calculation = remember(calculationKey) { runCatching { calculationKey.evaluate() } }
    val evaluation = calculation.getOrNull()?.copy(spec = spec)
    val gridResult = remember(evaluation) { runCatching { evaluation?.grid() } }
    val grid = gridResult.getOrNull()
    val error = calculation.exceptionOrNull() ?: gridResult.exceptionOrNull()
    var saveError by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var insertingSet by remember { mutableStateOf(false) }
    val editing = editor.cuteEditTarget != null
    val supportsSet = !editing && spec.workspace in listOf(CuteWorkspace.MMA, CuteWorkspace.COPY)
    var insertSet by rememberSaveable(spec.workspace, editing) { mutableStateOf(false) }
    var previewTab by rememberSaveable { mutableStateOf(false) }
    val setResult = rememberCuteSetPreview(spec.takeIf { supportsSet && insertSet })
    val setPreview = setResult?.getOrNull()
    val activeError = if (supportsSet && insertSet) setResult?.exceptionOrNull() else error
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val palette = LocalPalette.current
    val screen = LocalConfiguration.current
    val tall = screen.screenHeightDp >= 650
    val update: (CuteSpec) -> Unit = { if (!insertingSet) { editor.cuteDraft = it; saveError = false; copied = false } }
    val output: @Composable (Int) -> Unit = { height ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (supportsSet) {
                CuteInsertionScope(spec, insertSet) { insertSet = it; saveError = false }
            } else if (editing) {
                Text(stringResource(R.string.cute_update_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedContent(
                targetState = supportsSet && insertSet,
                transitionSpec = { materialFadeThrough(smallSurface = true).using(SizeTransform { _, _ -> XnotesMotion.defaultSpatialSpec() }) },
                label = "CuTe insertion preview mode",
            ) { group ->
                if (group) {
                    val failure = setResult?.exceptionOrNull()
                    if (failure != null) {
                        CuteErrorCard(failure)
                    } else {
                        CuteSetPreviewCard(setPreview, height, spec.workspace)
                    }
                } else {
                    CuteOutput(spec, evaluation, grid, error, height, update)
                }
            }
        }
    }

    Dialog(onDismissRequest = { if (!insertingSet) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(12.dp).widthIn(max = 1160.dp).fillMaxWidth().heightIn(max = (screen.screenHeightDp * 0.94f).dp)
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large),
            shape = MaterialTheme.shapes.large,
            color = palette.menuBg.toComposeColor(),
        ) {
            BoxWithConstraints {
                val wideLayout = maxWidth - 40.dp >= 680.dp
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.cute_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.cute_subtitle), style = MaterialTheme.typography.bodySmall)
                        }
                        CutePresetMenu(enabled = !insertingSet) { update(it.copy(rowStart = 0, columnStart = 0)) }
                        Tooltip(stringResource(if (copied) R.string.cute_copied else R.string.cute_copy_cpp)) {
                            IconButton(enabled = calculation.isSuccess && !insertingSet, onClick = {
                                runCatching { spec.cpp() }.getOrNull()?.let { clipboard.setText(AnnotatedString(it)); copied = true }
                            }) {
                                Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(if (copied) R.string.cute_copied else R.string.cute_copy_cpp))
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                        if (maxWidth >= 680.dp) {
                            val controlWidth = if (maxWidth >= 900.dp) 320.dp else 260.dp
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Column(Modifier.width(controlWidth).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CuteControls(spec, update, showViewSelector = !insertSet)
                                    CuteHelp()
                                }
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    output((screen.screenHeightDp - 350).coerceIn(240, 760))
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    listOf(false to R.string.cute_parameters, true to R.string.cute_preview_tab).forEachIndexed { index, (preview, label) ->
                                        SegmentedButton(selected = previewTab == preview, onClick = { previewTab = preview },
                                            shape = SegmentedButtonDefaults.itemShape(index, 2), label = { Text(stringResource(label)) })
                                    }
                                }
                                AnimatedContent(targetState = previewTab, transitionSpec = { materialFadeThrough(smallSurface = true) }, label = "CuTe parameters/preview") { preview ->
                                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        if (preview) output(if (tall) 360 else 240) else {
                                            CuteControls(spec, update, showViewSelector = !insertSet)
                                            CuteHelp()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (saveError) Text(stringResource(R.string.cute_save_error), color = MaterialTheme.colorScheme.error)
                    if (activeError != null && !wideLayout && !previewTab) {
                        Text(cuteErrorMessage(activeError), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(enabled = !insertingSet, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        Button(
                            enabled = !insertingSet && (if (supportsSet && insertSet) setPreview != null else evaluation != null && grid != null),
                            onClick = {
                                if (supportsSet && insertSet && setPreview != null) {
                                    insertingSet = true
                                    saveError = false
                                    scope.launch {
                                        try {
                                            if (editor.placeCuteDiagramSet(spec, setPreview.placements)) onDismiss() else saveError = true
                                        } finally {
                                            insertingSet = false
                                        }
                                    }
                                } else if (evaluation != null && editor.placeCuteDiagram(evaluation)) onDismiss() else saveError = true
                            },
                        ) {
                            if (insertingSet) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(when {
                                insertingSet -> stringResource(R.string.cute_inserting)
                                editing -> stringResource(R.string.cute_update)
                                insertSet && supportsSet -> stringResource(if (spec.workspace == CuteWorkspace.MMA) R.string.cute_insert_mma_group else R.string.cute_insert_copy_group)
                                spec.workspace == CuteWorkspace.MMA -> stringResource(R.string.cute_insert_current, spec.hardware.operand)
                                spec.workspace == CuteWorkspace.COPY -> stringResource(R.string.cute_insert_current, spec.hardware.copySide)
                                else -> stringResource(R.string.cute_insert)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CutePresetMenu(enabled: Boolean = true, onSelect: (CuteSpec) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = { open = true }) { Text(stringResource(R.string.cute_examples)) }
        DropdownMenu(open, { open = false }) {
            CutePresets.all.forEach { preset ->
                DropdownMenuItem(text = { Text(preset.name) }, onClick = { onSelect(preset.spec); open = false })
            }
        }
    }
}

@Composable
private fun CuteControls(spec: CuteSpec, update: (CuteSpec) -> Unit, showViewSelector: Boolean) {
    CuteWorkspaceControls(spec, update, showViewSelector)
}

@Composable
private fun CuteInsertionScope(spec: CuteSpec, insertSet: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cute_insert_scope), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to R.string.cute_scope_current, true to R.string.cute_scope_set).forEachIndexed { index, (all, label) ->
                    SegmentedButton(selected = insertSet == all, onClick = { onChange(all) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), label = { Text(stringResource(label)) })
                }
            }
            Text(
                if (insertSet) stringResource(if (spec.workspace == CuteWorkspace.MMA) R.string.cute_mma_set_preview else R.string.cute_copy_set_preview)
                else stringResource(R.string.cute_scope_current_hint, if (spec.workspace == CuteWorkspace.MMA) spec.hardware.operand else spec.hardware.copySide),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CuteAlgebraControls(spec: CuteSpec, update: (CuteSpec) -> Unit) {
    var operationsOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.cute_inputs_title), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(spec.a, { update(spec.copy(a = it.take(2048), rowStart = 0, columnStart = 0)) },
                    Modifier.fillMaxWidth(), label = { Text("A · Shape:Stride") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), minLines = 2, maxLines = 4,
                    supportingText = { Text(stringResource(R.string.cute_syntax_hint)) })
                Text(stringResource(R.string.cute_stride_shortcuts), style = MaterialTheme.typography.labelMedium)
                CuteMajorControls(spec.a) { update(spec.copy(a = it, rowStart = 0, columnStart = 0)) }
            }
        }

        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.cute_operation_title), style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { operationsOpen = true }, Modifier.fillMaxWidth()) { Text(spec.operation.id + "  ▾", fontFamily = FontFamily.Monospace) }
                    DropdownMenu(operationsOpen, { operationsOpen = false }) {
                        CuteOperation.entries.forEach { op ->
                            DropdownMenuItem(
                                text = { Text(op.id, fontFamily = FontFamily.Monospace) },
                                onClick = {
                                    update(spec.copy(operation = op, b = if (op.arity == 1) "" else if (spec.b.isBlank()) if (op == CuteOperation.COMPLEMENT) "64" else "4:1" else spec.b, showInput = false, rowStart = 0, columnStart = 0))
                                    operationsOpen = false
                                },
                                leadingIcon = if (spec.operation == op) ({ Text("✓") }) else null,
                            )
                        }
                    }
                }
                Text(stringResource(spec.operation.helpRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedVisibility(
                    visible = spec.operation.arity == 2 || spec.operation == CuteOperation.COALESCE,
                    enter = expandVertically(animationSpec = XnotesMotion.defaultSpatialSpec()) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec()),
                    exit = shrinkVertically(animationSpec = XnotesMotion.fastSpatialSpec()) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec()),
                ) {
                    OutlinedTextField(spec.b, { update(spec.copy(b = it.take(2048), rowStart = 0, columnStart = 0)) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(when (spec.operation) {
                            CuteOperation.COMPLEMENT -> R.string.cute_cotarget
                            CuteOperation.COALESCE -> R.string.cute_profile
                            else -> R.string.cute_operand_b
                        })) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 3)
                }
            }
        }

        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.cute_view_title), style = MaterialTheme.typography.titleSmall)
                val offsetLabel = stringResource(R.string.cute_offsets)
                val tvLabel = stringResource(R.string.cute_tv_layout)
                CuteChoices(listOf(offsetLabel, tvLabel), if (spec.tv) tvLabel else offsetLabel) {
                    update(spec.copy(tv = it == tvLabel, rowStart = 0, columnStart = 0))
                }
                AnimatedVisibility(
                    visible = spec.tv,
                    enter = expandVertically(animationSpec = XnotesMotion.defaultSpatialSpec()) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec()),
                    exit = shrinkVertically(animationSpec = XnotesMotion.fastSpatialSpec()) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CuteDimension("M", spec.tileM, Modifier.weight(1f)) { update(spec.copy(tileM = it, rowStart = 0)) }
                            CuteDimension("N", spec.tileN, Modifier.weight(1f)) { update(spec.copy(tileN = it, columnStart = 0)) }
                        }
                        Text(stringResource(R.string.cute_output_decode), style = MaterialTheme.typography.labelLarge)
                        CuteChoices(listOf("m + M·n", "m·N + n"), if (spec.rowMajor) "m·N + n" else "m + M·n") { update(spec.copy(rowMajor = it == "m·N + n")) }
                        Text(stringResource(R.string.cute_tv_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CuteSwizzleControls(spec, update)
            }
        }
    }
}

@Composable
private fun CuteDimension(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    OutlinedTextField(if (value == 0) "" else value.toString(), { onChange(it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) },
        modifier, singleLine = true, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun CuteOutput(spec: CuteSpec, evaluation: CuteEvaluation?, grid: CuteGrid?, error: Throwable?, height: Int, update: (CuteSpec) -> Unit) {
    // AnimatedContent overlays its direct children. Keep the complete output in one layout node
    // so the result, preview, cell trace and editing hint each receive their own vertical space.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (evaluation != null) {
            if (spec.workspace == CuteWorkspace.ALGEBRA) {
                CuteAlgebraResultCard(evaluation, spec, update)
            } else {
                CuteHardwareOutput(evaluation, update)
            }
        }
        if (error != null) {
            CuteErrorCard(error)
        }
        if (grid != null && evaluation != null) {
            if (grid.aliases > 0 || grid.holes > 0) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(stringResource(R.string.cute_mapping_gaps, grid.aliases, grid.holes), Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (!grid.spec.tv && evaluation.shown.rank > 2) Text(stringResource(R.string.cute_grouped_axes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CuteGridPreview(grid, evaluation, height)
            if (grid.windowed) {
                Text(stringResource(R.string.cute_window, grid.rowStart, grid.rowEnd - 1, grid.columnStart, grid.columnEnd - 1), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = grid.rowStart > 0, onClick = { update(spec.copy(rowStart = maxOf(0, grid.rowStart - CuteGrid.WINDOW))) }) { Text("M −") }
                    TextButton(enabled = grid.rowEnd < grid.rows, onClick = { update(spec.copy(rowStart = grid.rowEnd)) }) { Text("M +") }
                    TextButton(enabled = grid.columnStart > 0, onClick = { update(spec.copy(columnStart = maxOf(0, grid.columnStart - CuteGrid.WINDOW))) }) { Text("N −") }
                    TextButton(enabled = grid.columnEnd < grid.columns, onClick = { update(spec.copy(columnStart = grid.columnEnd)) }) { Text("N +") }
                }
            }
            Text(stringResource(R.string.cute_reedit_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CuteErrorCard(error: Throwable) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
        Text(cuteErrorMessage(error), Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CuteAlgebraResultCard(evaluation: CuteEvaluation, spec: CuteSpec, update: (CuteSpec) -> Unit) {
    val hasOperandB = spec.b.isNotBlank() && (spec.operation.arity == 2 || spec.operation == CuteOperation.COALESCE)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cute_calculation_title), style = MaterialTheme.typography.titleMedium)
                    Text(spec.operation.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExpressiveChoiceGroup(
                    options = listOf(false to stringResource(R.string.cute_result), true to stringResource(R.string.cute_input)),
                    selected = spec.showInput,
                    onSelect = { update(spec.copy(showInput = it, rowStart = 0, columnStart = 0)) },
                )
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CuteFormulaStep("1", stringResource(R.string.cute_step_input), "A = ${evaluation.input}")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val expression = when {
                        spec.operation == CuteOperation.LAYOUT -> "R = A"
                        hasOperandB -> "R = ${spec.operation.id}(A, ${spec.b})"
                        else -> "R = ${spec.operation.id}(A)"
                    }
                    CuteFormulaStep("2", stringResource(R.string.cute_step_operation), expression)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CuteFormulaStep("3", stringResource(R.string.cute_step_result), "R = ${evaluation.result}")
                    if (spec.swizzle != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CuteFormulaStep("↳", stringResource(R.string.cute_step_offset_mapping), "${spec.swizzle} ∘ offset ∘ R")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CuteMetric(stringResource(R.string.cute_metric_size), evaluation.shown.size.toString(), Modifier.weight(1f))
                CuteMetric(stringResource(R.string.cute_metric_cosize), evaluation.shown.cosize.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CuteMetric(stringResource(R.string.cute_metric_rank), evaluation.shown.rank.toString(), Modifier.weight(1f))
                CuteMetric(stringResource(R.string.cute_metric_depth), evaluation.shown.depth.toString(), Modifier.weight(1f))
            }
            TextButton(onClick = {
                update(spec.copy(a = evaluation.result.toString(), operation = CuteOperation.LAYOUT, b = "", showInput = false, rowStart = 0, columnStart = 0))
            }) { Text(stringResource(if (spec.swizzle == null) R.string.cute_use_result else R.string.cute_use_base)) }
        }
    }
}

@Composable
private fun CuteFormulaStep(number: String, label: String, formula: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Text(number, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formula, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun CuteMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun CuteGridPreview(grid: CuteGrid, evaluation: CuteEvaluation, height: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CuteGridPreviewContent(grid, evaluation, height)
    }
}

@Composable
private fun ColumnScope.CuteGridPreviewContent(grid: CuteGrid, evaluation: CuteEvaluation, height: Int) {
    var selected by remember(grid) { mutableStateOf<CuteCell?>(null) }
    val shownCell = key(grid) { rememberExitContent(selected) }
    var zoom by remember(grid) { mutableFloatStateOf(1f) }
    var pan by remember(grid) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER } }
    val columns = grid.columnEnd - grid.columnStart
    val rows = grid.rowEnd - grid.rowStart
    val contentW = (columns + 1.5f) * 48f
    val contentH = (rows + 1.5f) * 48f
    val fit = minOf(viewport.width / contentW, viewport.height / contentH).coerceAtLeast(.001f)
    val origin = Offset((viewport.width - contentW * fit) / 2, (viewport.height - contentH * fit) / 2)
    val description = stringResource(R.string.cute_grid_accessibility, grid.rows, grid.columns)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.cute_gestures), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text(stringResource(R.string.cute_fit)) }
    }
    Canvas(Modifier.fillMaxWidth().height(height.dp).clip(MaterialTheme.shapes.medium).background(Color.White)
        .border(1.dp, LocalPalette.current.border.toComposeColor(), MaterialTheme.shapes.medium)
        .onSizeChanged { viewport = it }.semantics { contentDescription = description }
        .pointerInput(grid, fit, origin) {
            detectTransformGestures { centroid, delta, scale, _ ->
                val next = (zoom * scale).coerceIn(1f, 8f)
                pan = (pan + origin - centroid) * (next / zoom) + centroid - origin + delta
                zoom = next
                pan = Offset(pan.x.coerceIn(-contentW * fit * zoom, viewport.width.toFloat()), pan.y.coerceIn(-contentH * fit * zoom, viewport.height.toFloat()))
            }
        }.pointerInput(grid, fit, origin) {
            detectTapGestures { point ->
                val local = (point - origin - pan) / (fit * zoom)
                val m = floor(local.y / 48 - 1).toInt() + grid.rowStart
                val n = floor(local.x / 48 - 1).toInt() + grid.columnStart
                selected = if (m in grid.rowStart until grid.rowEnd && n in grid.columnStart until grid.columnEnd) grid.cell(m, n) else null
            }
        }) {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save(); canvas.translate(origin.x + pan.x, origin.y + pan.y); canvas.scale(fit * zoom, fit * zoom)
        paint.textSize = 13f
        fun label(text: String, x: Float, y: Float, textSize: Float = 13f) {
            paint.style = Paint.Style.FILL; paint.color = android.graphics.Color.BLACK; paint.textSize = textSize
            canvas.drawText(text, x, y, paint)
        }
        for (m in grid.rowStart until grid.rowEnd) {
            val y = (m - grid.rowStart + 1) * 48f
            label(m.toString(), 26f, y + 29f)
            for (n in grid.columnStart until grid.columnEnd) {
                val x = (n - grid.columnStart + 1) * 48f
                val cell = grid.cell(m, n)
                val focus = selected?.owners?.firstOrNull()?.thread ?: evaluation.spec.hardware.thread.takeIf { evaluation.hardwareLayout?.tv == true && !evaluation.hardwareLayout.descriptor && it >= 0 }
                val hasFocus = selected != null || focus != null
                val highlighted = if (grid.spec.tv) cell.owners.any { it.thread == focus } else selected != null && cell.offset == selected?.offset
                paint.style = Paint.Style.FILL; paint.color = android.graphics.Color.parseColor(grid.color(cell))
                paint.alpha = if (!hasFocus || highlighted) 255 else 90
                canvas.drawRect(x, y, x + 48, y + 48, paint); paint.alpha = 255
                paint.style = Paint.Style.STROKE; paint.color = android.graphics.Color.BLACK; paint.strokeWidth = 0.8f
                canvas.drawRect(x, y, x + 48, y + 48, paint)
                val labels = grid.labels(cell)
                labels.forEachIndexed { i, s -> label(s, x + 24, y + 29 + i * 16 - if (labels.size == 2) 8 else 0, minOf(13f, 42f / (s.length * 0.62f))) }
                if (selected?.row == m && selected?.column == n) {
                    paint.style = Paint.Style.STROKE; paint.color = android.graphics.Color.rgb(25, 65, 155); paint.strokeWidth = 3f
                    canvas.drawRect(x + 1, y + 1, x + 47, y + 47, paint)
                }
            }
        }
        for (n in grid.columnStart until grid.columnEnd) label(n.toString(), (n - grid.columnStart + 1) * 48f + 24, 32f)
        canvas.restore()
    }
    AnimatedVisibility(
        visible = selected != null,
        enter = expandVertically(animationSpec = XnotesMotion.defaultSpatialSpec()) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec()),
        exit = shrinkVertically(animationSpec = XnotesMotion.fastSpatialSpec()) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec()),
    ) {
      shownCell?.let { selectedCell ->
        AnimatedContent(
            targetState = selectedCell,
            transitionSpec = { materialFadeThrough(smallSurface = true) },
            label = "CuTe cell trace",
        ) { cell ->
          Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
            SelectionContainer {
              Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.cute_cell_trace_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("(m,n) = (${cell.row},${cell.column})   offset = ${cell.offset}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (grid.spec.tv) {
                    Text(if (cell.owners.isEmpty()) stringResource(R.string.cute_unmapped) else cell.owners.take(32).joinToString(" · ") { "T${it.thread}/V${it.value}" } + if (cell.owners.size > 32) " … (${cell.owners.size})" else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    cell.owners.firstOrNull()?.let { owner ->
                        Text("T${owner.thread} → ${grid.layout.mode(0).coordinate(owner.thread.toLong())}; V${owner.value} → ${grid.layout.mode(1).coordinate(owner.value.toLong())}\nShape coord = ${grid.layout.tvCoordinate(owner.thread,owner.value)} (LayoutLeft)", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                cell.index?.let { i ->
                    if (evaluation.tmaAnalysis != null) {
                        val entry = evaluation.tmaAnalysis.element(i)
                        Text("GMEM (${entry.coordinate.joinToString()}) → ${entry.globalByte?.let { "$it B" } ?: "OOB"}\nSMEM ${entry.sharedByte} B · bank ${entry.bank}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("${grid.layout.coordinate(i)} → ${grid.layout.index(i)}${if (cell.offset != grid.layout.index(i)) " → ${cell.offset}" else ""}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.cute_domain_index, i), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    if (evaluation.spec.workspace == CuteWorkspace.ALGEBRA && !evaluation.spec.showInput && evaluation.spec.operation == CuteOperation.COMPOSITION) {
                        val b = runCatching { when (val arg = CuteParser.operand(evaluation.spec.b)) {
                            is CuteOperand.Layout -> arg.value
                            is CuteOperand.Integer -> CuteLayout.scalar(arg.value)
                            else -> null
                        } }.getOrNull()
                        if (b != null) Text("R($i) = A(B($i)) = A(${b.index(i)}) = ${evaluation.input.index(b.index(i))}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
              }
            }
          }
        }
      }
    }
}

@Composable
private fun CuteHelp() {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.cute_help)) }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = XnotesMotion.defaultSpatialSpec()) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec()),
                exit = shrinkVertically(animationSpec = XnotesMotion.fastSpatialSpec()) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec()),
            ) {
                Text(stringResource(R.string.cute_help_body), Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun CuteOperation.helpRes(): Int = when (this) {
    CuteOperation.LAYOUT -> R.string.cute_op_layout
    CuteOperation.COALESCE -> R.string.cute_op_coalesce
    CuteOperation.FILTER -> R.string.cute_op_filter
    CuteOperation.COMPOSITION -> R.string.cute_op_composition
    CuteOperation.COMPLEMENT -> R.string.cute_op_complement
    CuteOperation.RIGHT_INVERSE -> R.string.cute_op_right_inverse
    CuteOperation.LEFT_INVERSE -> R.string.cute_op_left_inverse
    CuteOperation.BLOCKED_PRODUCT -> R.string.cute_op_blocked
    CuteOperation.RAKED_PRODUCT -> R.string.cute_op_raked
    else -> when {
        id.startsWith("zipped") -> R.string.cute_op_zipped
        id.startsWith("tiled") -> R.string.cute_op_tiled
        id.startsWith("flat") -> R.string.cute_op_flat
        id.endsWith("divide") -> R.string.cute_op_divide
        else -> R.string.cute_op_product
    }
}

@Composable
private fun cuteErrorMessage(error: Throwable): String {
    val e = error as? CuteException
    val resource = when (e?.code) {
        "syntax", "integer_tuple", "layout_expected" -> R.string.cute_error_syntax
        "congruence" -> R.string.cute_error_congruence
        "shape_positive", "stride_range" -> R.string.cute_error_integer
        "size_limit", "overflow", "input_limit", "rank", "depth", "target" -> R.string.cute_error_limit
        "tiler_rank", "zip_rank", "retile_rank" -> R.string.cute_error_tiler
        "non_injective_complement" -> R.string.cute_error_complement
        "stride_divisibility", "shape_divisibility", "inverse_divisibility", "divisibility" -> R.string.cute_error_divisibility
        "tv_rank" -> R.string.cute_error_tv_rank
        "tile_size" -> R.string.cute_error_tile_size
        "tv_outside" -> R.string.cute_error_tv_outside
        "swizzle" -> R.string.cute_error_swizzle
        "mnk", "mma_tile", "dense_layout" -> R.string.cute_error_hardware_layout
        "copy_atom_size" -> R.string.cute_error_copy_atom
        "descriptor_copy" -> R.string.cute_descriptor_hint
        "tma_rank", "tma_shape", "tma_origin", "tma_box", "tma_type" -> R.string.cute_error_tma_shape
        "tma_alignment" -> R.string.cute_error_tma_alignment
        "tma_inner", "tma_swizzle" -> R.string.cute_error_tma_swizzle
        "tma_strides" -> R.string.cute_error_tma_strides
        else -> R.string.cute_error_generic
    }
    return stringResource(resource) + if (e?.detail?.isNotEmpty() == true) " (${e.detail})" else ""
}
