package com.xnotes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.math.CanvasMathTemplates
import com.xnotes.core.math.LinearInk
import com.xnotes.core.math.Matrix2
import com.xnotes.core.math.MatrixBracket
import com.xnotes.core.math.MatrixFrameSpec
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.deepCopyAll
import com.xnotes.platform.AndroidRenderer
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs

/** Canvas-only tools: the preview and the inserted result use exactly the same native items. */
@Composable
internal fun CanvasMathDialog(editor: InfiniteEditor, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf(3) }
    var columns by remember { mutableStateOf(3) }
    var bracket by remember { mutableStateOf(MatrixBracket.SQUARE) }
    var augmented by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf(true) }
    var planeOnly by remember { mutableStateOf(false) }
    var showSum by remember { mutableStateOf(true) }
    var grid by remember { mutableStateOf(true) }
    var vectors by remember { mutableStateOf(listOf("2", "1", "1", "2")) }
    var coefficients by remember { mutableStateOf(matrixValues(Matrix2.SHEAR)) }
    val paper = editor.document.background.paperColor ?: LocalPalette.current.paper
    val ink = CanvasMathTemplates.readableInk(editor.inkColor, paper)
    val matrix = parseMatrix(coefficients)
    val vectorNumbers = vectors.map(::parseMathNumber)
    val valid = when (tab) { 1 -> planeOnly || vectorNumbers.all { it != null }; 2 -> matrix != null; else -> true }
    val diagram = remember(tab, rows, columns, bracket, augmented, guides, planeOnly, showSum, grid, vectors, matrix, ink) {
        when (tab) {
            1 -> if (valid) CanvasMathTemplates.vectors(ink, Pt(vectorNumbers[0] ?: 0.0, vectorNumbers[1] ?: 0.0), Pt(vectorNumbers[2] ?: 0.0, vectorNumbers[3] ?: 0.0), !planeOnly, showSum, grid) else null
            2 -> matrix?.let { CanvasMathTemplates.linearMap(it, ink, grid) }
            else -> CanvasMathTemplates.matrix(MatrixFrameSpec(rows, columns, bracket, augmented && columns > 1, guides), ink)
        }
    }
    val measurer = remember { AndroidTextMeasurer() }
    val preview = remember(diagram) { diagram?.materialize(measurer).orEmpty() }
    val contentHeight = (LocalConfiguration.current.screenHeightDp * 0.68f).dp.coerceAtMost(760.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_math_tools)) },
        text = {
            Column(
                Modifier.widthIn(max = 540.dp).heightIn(max = contentHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.canvas_math_intro), style = MaterialTheme.typography.bodyMedium)
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf(R.string.canvas_math_frame, R.string.canvas_math_vectors, R.string.canvas_math_map).forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
                    }
                }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.canvas_math_preview), style = MaterialTheme.typography.labelLarge)
                        MathPreview(preview, paper)
                    }
                }
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { materialFadeThrough(smallSurface = true) },
                    label = "Canvas math tool mode",
                ) { destination ->
                  OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                      when (destination) {
                        0 -> {
                            MathCountRow(stringResource(R.string.table_rows), rows) { rows = it }
                            MathCountRow(stringResource(R.string.table_columns), columns) { columns = it }
                            Text(stringResource(R.string.math_brackets), style = MaterialTheme.typography.labelLarge)
                            ExpressiveChoiceGroup(
                                options = MatrixBracket.entries.map { choice ->
                                    choice to stringResource(when (choice) {
                                        MatrixBracket.SQUARE -> R.string.math_fence_square
                                        MatrixBracket.ROUND -> R.string.math_fence_round
                                        MatrixBracket.BARS -> R.string.math_fence_determinant
                                    })
                                },
                                selected = bracket,
                                onSelect = { bracket = it },
                            )
                            MathSwitch(stringResource(R.string.canvas_math_guides), guides) { guides = it }
                            if (columns > 1) MathSwitch(stringResource(R.string.canvas_math_augmented), augmented) { augmented = it }
                        }
                        1 -> {
                            MathSwitch(stringResource(R.string.canvas_math_plane_only), planeOnly) { planeOnly = it }
                            TextButton(onClick = { editor.armMathVector(); onDismiss() }) { Text(stringResource(R.string.canvas_math_draw_vector)) }
                            if (!planeOnly) {
                                ScalarPair(stringResource(R.string.canvas_math_vector_u), vectors.take(2)) { index, value -> vectors = vectors.toMutableList().also { it[index] = value } }
                                ScalarPair(stringResource(R.string.canvas_math_vector_v), vectors.drop(2)) { index, value -> vectors = vectors.toMutableList().also { it[index + 2] = value } }
                                MathSwitch(stringResource(R.string.canvas_math_sum), showSum) { showSum = it }
                            }
                            MathSwitch(stringResource(R.string.canvas_math_grid), grid) { grid = it }
                        }
                        2 -> {
                            MatrixFields(coefficients) { coefficients = it }
                            MatrixPresets(matrix) { coefficients = matrixValues(it) }
                            MatrixDeterminant(matrix)
                            MathSwitch(stringResource(R.string.canvas_math_grid), grid) { grid = it }
                            Text(stringResource(R.string.canvas_math_select_hint), style = MaterialTheme.typography.bodySmall)
                        }
                      }
                    }
                  }
                }
                if (!valid) Text(stringResource(R.string.canvas_math_input_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.canvas_math_native_hint), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.textDim.toComposeColor())
            }
        },
        confirmButton = {
            TextButton(enabled = valid && diagram != null, onClick = { diagram?.let { editor.insertMathDiagram(it) }; onDismiss() }) { Text(stringResource(R.string.insert)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Uses detached preview copies: cancelling or merely typing cannot modify the document. */
@Composable
internal fun CanvasLinearTransformDialog(editor: InfiniteEditor, onDismiss: () -> Unit) {
    val originals = remember(editor) { editor.selection.items.toList() }
    val bounds = remember(originals) { originals.map { it.bounds() }.reduceOrNull { a, b -> a.union(b) } ?: Rect(0.0, 0.0, 1.0, 1.0) }
    var values by remember { mutableStateOf(matrixValues(Matrix2.SHEAR)) }
    var bottomLeft by remember { mutableStateOf(true) }
    var keepOriginal by remember { mutableStateOf(true) }
    val matrix = parseMatrix(values)
    val origin = if (bottomLeft) Pt(bounds.left, bounds.bottom) else bounds.center
    val measurer = remember { AndroidTextMeasurer() }
    val transformed = remember(originals, matrix, origin) {
        if (matrix == null || !LinearInk.supports(originals)) emptyList()
        else originals.deepCopyAll(measurer).also { LinearInk.mapDetached(it, matrix, origin) }
    }
    val paper = editor.document.background.paperColor ?: LocalPalette.current.paper
    val contentHeight = (LocalConfiguration.current.screenHeightDp * 0.68f).dp.coerceAtMost(760.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_linear_transform)) },
        text = {
            Column(Modifier.widthIn(max = 540.dp).heightIn(max = contentHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.canvas_math_transform_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.canvas_math_preview), style = MaterialTheme.typography.labelLarge)
                        MathPreview(transformed, paper, originals, origin)
                        Text(stringResource(R.string.canvas_math_transform_legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("A", style = MaterialTheme.typography.titleSmall)
                        MatrixFields(values) { values = it }
                        MatrixPresets(matrix) { values = matrixValues(it) }
                        MatrixDeterminant(matrix)
                    }
                }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.canvas_math_origin), style = MaterialTheme.typography.titleSmall)
                        ExpressiveChoiceGroup(
                            options = listOf(
                                true to stringResource(R.string.canvas_math_bottom_left),
                                false to stringResource(R.string.canvas_math_center),
                            ),
                            selected = bottomLeft,
                            onSelect = { bottomLeft = it },
                        )
                        MathSwitch(stringResource(R.string.canvas_math_keep_original), keepOriginal) { keepOriginal = it }
                    }
                }
                AnimatedVisibility(
                    visible = matrix == null,
                    enter = expandVertically(animationSpec = XnotesMotion.defaultSpatialSpec()) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec()),
                    exit = androidx.compose.animation.shrinkVertically(animationSpec = XnotesMotion.fastSpatialSpec()) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec()),
                ) {
                    Text(stringResource(R.string.canvas_math_input_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = matrix != null && transformed.isNotEmpty(), onClick = { matrix?.let { editor.transformSelectedInk(it, bottomLeft, keepOriginal) }; onDismiss() }) { Text(stringResource(R.string.canvas_math_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MathPreview(items: List<CanvasItem>, paper: Rgba, originals: List<CanvasItem> = emptyList(), origin: Pt? = null) {
    val bounds = itemBounds(items + originals).outset(20.0)
    val description = stringResource(R.string.canvas_math_preview)
    val border = LocalPalette.current.border.toComposeColor()
    Canvas(Modifier.fillMaxWidth().height(210.dp).clip(MaterialTheme.shapes.medium).background(paper.toComposeColor()).border(1.dp, border, MaterialTheme.shapes.medium).semantics { contentDescription = description }) {
        val k = minOf(size.width / bounds.w.coerceAtLeast(1.0), size.height / bounds.h.coerceAtLeast(1.0)).toDouble()
        drawIntoCanvas { canvas ->
            val renderer = AndroidRenderer(canvas.nativeCanvas)
            renderer.save()
            renderer.translate(size.width / 2.0, size.height / 2.0)
            renderer.scale(k, k)
            renderer.translate(-bounds.centerX, -bounds.centerY)
            if (originals.isNotEmpty()) {
                renderer.saveLayerAlpha(bounds, 0.22)
                originals.forEach { it.paint(renderer) }
                renderer.restore()
            }
            items.forEach { it.paint(renderer) }
            if (origin != null) {
                val pen = com.xnotes.core.pal.Pen(Rgba(230, 120, 40), 1.5)
                renderer.strokePolyline(listOf(Pt(origin.x - 5.0 / k, origin.y), Pt(origin.x + 5.0 / k, origin.y)), pen)
                renderer.strokePolyline(listOf(Pt(origin.x, origin.y - 5.0 / k), Pt(origin.x, origin.y + 5.0 / k)), pen)
            }
            renderer.restore()
        }
    }
}

@Composable
private fun MathCountRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange(value - 1) }, enabled = value > 1) { Icon(Icons.Default.Remove, stringResource(R.string.decrease_value, label)) }
        Text(value.toString(), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < 8) { Icon(Icons.Default.Add, stringResource(R.string.increase_value, label)) }
    }
}

@Composable
private fun MathSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun ScalarPair(label: String, values: List<String>, onChange: (Int, String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        values.forEachIndexed { index, value ->
            OutlinedTextField(value, { onChange(index, it) }, Modifier.weight(1f), label = { Text("$label · ${if (index == 0) "x" else "y"}") }, singleLine = true, isError = parseMathNumber(value) == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
}

@Composable
private fun MatrixFields(values: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0..1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (column in 0..1) {
                val index = row * 2 + column
                OutlinedTextField(values[index], { new -> onChange(values.toMutableList().also { it[index] = new }) }, Modifier.weight(1f), label = { Text("a${if (row == 0) "₁" else "₂"}${if (column == 0) "₁" else "₂"}") }, singleLine = true, isError = parseMathNumber(values[index]) == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
    }
}

@Composable
private fun MatrixPresets(current: Matrix2?, onPick: (Matrix2) -> Unit) {
    ExpressiveChoiceGroup(
        options = listOf(
            Matrix2.IDENTITY to stringResource(R.string.canvas_math_identity),
            Matrix2.ROTATE to stringResource(R.string.canvas_math_rotate),
            Matrix2.SHEAR to stringResource(R.string.canvas_math_shear),
            Matrix2.REFLECT to stringResource(R.string.canvas_math_reflect),
            Matrix2.PROJECT to stringResource(R.string.canvas_math_project),
        ),
        selected = current,
        onSelect = onPick,
    )
}

@Composable
private fun MatrixDeterminant(matrix: Matrix2?) {
    if (matrix != null) {
        Text("det(A) = ${CanvasMathTemplates.number(matrix.determinant)}", style = MaterialTheme.typography.titleSmall)
        AnimatedVisibility(
            visible = matrix.determinant == 0.0,
            enter = expandVertically(animationSpec = XnotesMotion.defaultSpatialSpec()) + fadeIn(animationSpec = XnotesMotion.fastEffectsSpec()),
            exit = androidx.compose.animation.shrinkVertically(animationSpec = XnotesMotion.fastSpatialSpec()) + fadeOut(animationSpec = XnotesMotion.fastEffectsSpec()),
        ) {
            Text(stringResource(R.string.canvas_math_singular), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun parseMathNumber(raw: String): Double? = raw.trim().replace('−', '-').toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) <= Matrix2.MAX_COEFFICIENT }
private fun matrixValues(matrix: Matrix2) = listOf(matrix.a, matrix.b, matrix.c, matrix.d).map(CanvasMathTemplates::number)
private fun parseMatrix(values: List<String>): Matrix2? {
    val parsed = values.map { parseMathNumber(it) ?: return null }
    return Matrix2(parsed[0], parsed[1], parsed[2], parsed[3])
}
private fun itemBounds(items: List<CanvasItem>): Rect = items.map { it.paintBounds() }.reduceOrNull { a, b -> a.union(b) } ?: Rect(-1.0, -1.0, 2.0, 2.0)
