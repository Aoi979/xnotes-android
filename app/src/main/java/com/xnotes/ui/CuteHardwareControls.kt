package com.xnotes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.cute.*

@Composable
internal fun CuteWorkspaceControls(spec: CuteSpec, update: (CuteSpec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.cute_workspace_label), style = MaterialTheme.typography.labelLarge)
        ExpressiveChoiceGroup(
            options = CuteWorkspace.entries.map { it to stringResource(it.labelRes()) },
            selected = spec.workspace,
            onSelect = { update(spec.copy(workspace = it, showInput = false, rowStart = 0, columnStart = 0, transposeDiagram = false)) },
        )
        AnimatedContent(
            targetState = spec.workspace,
            transitionSpec = { materialFadeThrough(smallSurface = true) },
            label = "CuTe workspace controls",
        ) { workspace ->
            when (workspace) {
                CuteWorkspace.ALGEBRA -> CuteAlgebraControls(spec, update)
                CuteWorkspace.MMA -> CuteSettingsCard { CuteMmaControls(spec.hardware) { update(spec.copy(hardware = it, rowStart = 0, columnStart = 0, transposeDiagram = spec.transposeDiagram && it.operand == spec.hardware.operand)) } }
                CuteWorkspace.COPY -> CuteSettingsCard { CuteCopyControls(spec.hardware) { update(spec.copy(hardware = it, rowStart = 0, columnStart = 0)) } }
                CuteWorkspace.TMA -> CuteSettingsCard { CuteTmaControls(spec.tma) { update(spec.copy(tma = it, rowStart = 0, columnStart = 0)) } }
            }
        }
    }
}

@Composable
private fun CuteSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun CuteWorkspace.labelRes(): Int = when (this) {
    CuteWorkspace.ALGEBRA -> R.string.cute_workspace_algebra
    CuteWorkspace.MMA -> R.string.cute_workspace_mma
    CuteWorkspace.COPY -> R.string.cute_workspace_copy
    CuteWorkspace.TMA -> R.string.cute_workspace_tma
}

@Composable
internal fun CuteText(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(2048)) }, Modifier.fillMaxWidth(), label = { Text(label) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 4)
}

@Composable
internal fun CuteChoices(options: List<String>, selected: String, onChange: (String) -> Unit) {
    ExpressiveChoiceGroup(options.map { it to it }, selected, onChange)
}

@Composable
private fun CuteSelect(label: String, value: String, options: List<Pair<String,String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$value ▾", fontFamily = FontFamily.Monospace) }
        DropdownMenu(open, { open = false }, Modifier.widthIn(max = 520.dp).heightIn(max = 480.dp)) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }, onClick = { onChange(id); open = false }) }
        }
    }
}

@Composable
internal fun CuteMajorControls(value: String, onChange: (String) -> Unit) {
    val shape = runCatching { CuteParser.intTree(value.substringBefore(':')) }.getOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false,true).forEach { right ->
            OutlinedButton(enabled = shape != null, onClick = { shape?.let { runCatching { CuteLayout.compact(it,right).toString() }.getOrNull()?.let(onChange) } }) {
                Text(if (right) "LayoutRight" else "LayoutLeft", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun CuteSwizzleControls(spec: CuteSpec, update: (CuteSpec) -> Unit) {
    val shownSwizzle = rememberExitContent(spec.swizzle)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Swizzle<B,M,S>", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.cute_swizzle_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(spec.swizzle != null, { update(spec.copy(swizzle = if(it) CuteSwizzle() else null)) })
        }
        AnimatedVisibility(
            visible = spec.swizzle != null,
            enter = expandVertically(animationSpec = XnotesMotion.defaultSpatialSpec()) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec()),
            exit = shrinkVertically(animationSpec = XnotesMotion.fastSpatialSpec()) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec()),
        ) {
            shownSwizzle?.let { s ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("B" to s.bits, "M" to s.base, "S" to s.shift).forEachIndexed { i, (name, value) ->
                            CuteInteger(name, value.toLong(), Modifier.weight(1f)) { n ->
                                val bounded = n.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                                update(spec.copy(swizzle = when (i) {
                                    0 -> s.copy(bits = bounded)
                                    1 -> s.copy(base = bounded)
                                    else -> s.copy(shift = bounded)
                                }))
                            }
                        }
                    }
                    CuteInteger("Offset", s.offset) { update(spec.copy(swizzle = s.copy(offset = it))) }
                    if (runCatching { s.validate() }.isSuccess) {
                        Text("Y=0x${s.sourceMask.toString(16)} → Z=0x${s.targetMask.toString(16)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CuteInteger(label: String, value: Long, modifier: Modifier = Modifier, onChange: (Long) -> Unit) {
    // Keep incomplete/invalid input visible; do not silently reuse a previous valid setting.
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toLongOrNull() != value && !(value == -1L && text.toLongOrNull() == null)) text = value.toString() }
    OutlinedTextField(text, { text = it.take(16); onChange(it.toLongOrNull() ?: -1) }, modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true)
}

@Composable
private fun CuteMmaControls(h: CuteHardwareSpec, showOperand: Boolean = true, update: (CuteHardwareSpec) -> Unit) {
    CuteSelect("MMA Atom", h.mmaAtom, CuteAtoms.mma.map { it.id to it.cppType }) { update(h.copy(mmaAtom = it)) }
    CuteText("AtomLayoutMNK · Shape:Stride", h.mmaThreads) { update(h.copy(mmaThreads = it)) }
    CuteMajorControls(h.mmaThreads) { update(h.copy(mmaThreads = it)) }
    CuteText("PermutationMNK · (_,_,_)", h.permutation) { update(h.copy(permutation = it)) }
    Text(stringResource(R.string.cute_mma_hint), style = MaterialTheme.typography.bodySmall)
    if (showOperand) CuteChoices(listOf("A","B","C"), h.operand) { update(h.copy(operand = it)) }
}

@Composable
private fun CuteCopyControls(h: CuteHardwareSpec, update: (CuteHardwareSpec) -> Unit) {
    CuteSelect("Copy Atom", h.copyAtom, CuteAtoms.copy.map { it.id to it.cppType }) { update(h.copy(copyAtom = it)) }
    CuteSelect(stringResource(R.string.cute_copy_source), h.copySource.name, CuteCopySource.entries.map { it.name to when (it) {
        CuteCopySource.ATOM -> "Copy_Atom::ValLayout"
        CuteCopySource.TILED -> "make_tiled_copy"
        else -> "make_tiled_copy_${it.name.takeLast(1)}"
    } }) { update(h.copy(copySource = CuteCopySource.valueOf(it), copySide = if (h.copySide == "R") "D" else h.copySide)) }
    when (h.copySource) {
        CuteCopySource.ATOM -> Text(stringResource(R.string.cute_atom_elements), style = MaterialTheme.typography.bodySmall)
        CuteCopySource.TILED -> {
            CuteText("ThrLayout · Shape:Stride", h.copyThreads) { update(h.copy(copyThreads = it)) }
            CuteMajorControls(h.copyThreads) { update(h.copy(copyThreads = it)) }
            CuteText("ValLayout · Shape:Stride", h.copyValues) { update(h.copy(copyValues = it)) }
            CuteMajorControls(h.copyValues) { update(h.copy(copyValues = it)) }
            Text(stringResource(R.string.cute_copy_hint), style = MaterialTheme.typography.bodySmall)
        }
        else -> CuteMmaControls(h, showOperand = false, update)
    }
    CuteChoices(if (h.copySource == CuteCopySource.ATOM) listOf("S","D","R") else listOf("S","D"), h.copySide) { update(h.copy(copySide = it)) }
    Text(stringResource(R.string.cute_copy_sides), style = MaterialTheme.typography.bodySmall)
    if (h.copySource != CuteCopySource.ATOM) {
        CuteChoices(listOf("TV Layout","retile_S/D"), if (h.retile) "retile_S/D" else "TV Layout") { update(h.copy(retile = it == "retile_S/D")) }
        if (h.retile) {
            CuteText("Fragment (V,M,N,…) · Shape:Stride", h.fragment) { update(h.copy(fragment = it)) }
            Text(stringResource(R.string.cute_retile_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CuteTmaControls(t: CuteTmaSpec, update: (CuteTmaSpec) -> Unit) {
    Text(stringResource(R.string.cute_tma_scope), style = MaterialTheme.typography.bodySmall)
    CuteText("globalDim", t.globalShape) { update(t.copy(globalShape = it)) }
    CuteText(stringResource(R.string.cute_tma_strides), t.globalStrides) { update(t.copy(globalStrides = it)) }
    CuteText("boxDim", t.box) { update(t.copy(box = it)) }
    CuteText(stringResource(R.string.cute_tma_origin), t.origin) { update(t.copy(origin = it)) }
    CuteText("elementStrides", t.steps) { update(t.copy(steps = it)) }
    Text(stringResource(R.string.cute_tma_element), style = MaterialTheme.typography.labelLarge)
    CuteChoices(listOf("1","2","4","8"), t.elementBytes.toString()) { update(t.copy(elementBytes = it.toInt())) }
    Text("Swizzle (bytes)", style = MaterialTheme.typography.labelLarge)
    CuteChoices(listOf("0","32","64","128"), t.swizzleBytes.toString()) { update(t.copy(swizzleBytes = it.toInt())) }
    CuteInteger("GMEM base (bytes)", t.globalBase) { update(t.copy(globalBase = it)) }
    CuteInteger("SMEM base (bytes)", t.sharedBase) { update(t.copy(sharedBase = it)) }
}

@Composable
internal fun CuteHardwareOutput(e: CuteEvaluation, update: (CuteSpec) -> Unit) {
    val h = e.hardwareLayout
    val tma = e.tmaAnalysis
    val title = when (e.spec.workspace) {
        CuteWorkspace.MMA -> stringResource(R.string.cute_workspace_mma)
        CuteWorkspace.COPY -> stringResource(R.string.cute_workspace_copy)
        CuteWorkspace.TMA -> stringResource(R.string.cute_workspace_tma)
        CuteWorkspace.ALGEBRA -> return
    }
    var showDetails by remember(e.spec) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(e.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.cute_result), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            when (e.spec.workspace) {
                CuteWorkspace.MMA -> {
                    CuteOutputLine("Atom", CuteAtoms.mma(e.spec.hardware.mmaAtom).cppType)
                    CuteOutputLine("AtomLayoutMNK", e.spec.hardware.mmaThreads)
                    CuteOutputLine("PermutationMNK", e.spec.hardware.permutation)
                    CuteOutputLine("Operand", e.spec.hardware.operand)
                }
                CuteWorkspace.COPY -> {
                    CuteOutputLine("Copy_Atom", CuteAtoms.copy(e.spec.hardware.copyAtom).cppType)
                    CuteOutputLine(stringResource(R.string.cute_copy_source), e.spec.hardware.copySource.name)
                    if (e.spec.hardware.copySource != CuteCopySource.ATOM) {
                        CuteOutputLine("T / V", "${e.spec.hardware.copyThreads} / ${e.spec.hardware.copyValues}")
                    }
                    CuteOutputLine("Side", e.spec.hardware.copySide)
                    if (e.spec.hardware.retile) CuteOutputLine("Fragment", e.spec.hardware.fragment)
                }
                CuteWorkspace.TMA -> {
                    CuteOutputLine("GMEM", e.spec.tma.globalShape)
                    CuteOutputLine("Tile", e.spec.tma.box)
                    CuteOutputLine(stringResource(R.string.cute_tma_origin), e.spec.tma.origin)
                    CuteOutputLine(stringResource(R.string.cute_tma_strides), e.spec.tma.globalStrides.ifBlank { "—" })
                }
                CuteWorkspace.ALGEBRA -> Unit
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CuteOutputLine("R", e.result.toString())
            if (h?.descriptor == true) {
                Text(stringResource(R.string.cute_descriptor_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (h != null && !h.descriptor && h.tv) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val count = h.layout.mode(0).size.toInt()
                val selected = e.spec.hardware.thread
                Text(stringResource(R.string.cute_partition_title, count, h.layout.mode(1).size), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { CuteInteger("Thread (0…${count - 1})", selected.toLong()) { update(e.spec.copy(hardware = e.spec.hardware.copy(thread = it.coerceIn(-1,Int.MAX_VALUE.toLong()).toInt()))) } }
                    TextButton(onClick = { update(e.spec.copy(hardware = e.spec.hardware.copy(thread = -1))) }) { Text(stringResource(R.string.cute_all_threads)) }
                }
                if (selected in 0 until count) {
                    val values = h.partition(selected)
                    SelectionContainer {
                        Text(values.take(64).mapIndexed { v, address -> "V$v→(${address % h.rows},${address / h.rows})" }.joinToString("  ") + if(values.size > 64) " … (${values.size})" else "",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (selected != -1) Text(stringResource(R.string.cute_thread_range), color = MaterialTheme.colorScheme.error)
            }
            if (tma != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.cute_tma_analysis, tma.rowPitchBytes, tma.allocationBytes, tma.transferBytes), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(stringResource(if (showDetails) R.string.cute_hide_details else R.string.cute_show_details))
            }
            AnimatedVisibility(
                visible = showDetails,
                enter = expandVertically(animationSpec = XnotesMotion.defaultSpatialSpec()) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec()),
                exit = shrinkVertically(animationSpec = XnotesMotion.fastSpatialSpec()) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec()),
            ) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        e.sourceLines().filterNot { it.startsWith("R =") }.forEach {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CuteOutputLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(.8f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
}
