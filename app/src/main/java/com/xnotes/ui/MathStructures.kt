package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Insert ready-to-edit linear algebra notation into the current flow-text caret. */
@Composable
internal fun MathStructuresButton(editor: Editor) {
    val palette = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    var customMatrix by remember { mutableStateOf(false) }
    val label = stringResource(R.string.math_structures)

    Box {
        Tooltip(label) {
            androidx.compose.material3.IconButton(
                onClick = { expanded = true },
                enabled = editor.flowEditingActive,
                modifier = Modifier.size(44.dp).semantics { contentDescription = label },
            ) {
                Text(
                    "Aᵀ",
                    color = if (editor.flowEditingActive) palette.textDim.toComposeColor() else palette.disabled.toComposeColor(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        fun insert(latex: String) {
            editor.flowInsertMath(latex)
            expanded = false
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.width(260.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
                PopupTitle(label)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_matrix_2x2)) },
                    onClick = { insert("\\begin{bmatrix}a_{11}&a_{12}\\\\a_{21}&a_{22}\\end{bmatrix}") },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_custom_matrix)) },
                    onClick = { expanded = false; customMatrix = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_column_vector)) },
                    onClick = { insert("\\begin{bmatrix}x_1\\\\\\vdots\\\\x_n\\end{bmatrix}") },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_row_vector)) },
                    onClick = { insert("\\begin{bmatrix}x_1&\\cdots&x_n\\end{bmatrix}") },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_augmented_matrix)) },
                    onClick = {
                        insert("\\left[\\begin{array}{cc|c}a_{11}&a_{12}&b_1\\\\a_{21}&a_{22}&b_2\\end{array}\\right]")
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_determinant)) },
                    onClick = { insert("\\det(A)") },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_inverse)) },
                    onClick = { insert("A^{-1}") },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_transpose)) },
                    onClick = { insert("A^{\\mathsf T}") },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_rank)) },
                    onClick = { insert("\\operatorname{rank}(A)") },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.math_matrix_vector_equation)) },
                    onClick = { insert("A\\mathbf{x}=\\mathbf{b}") },
                )
            }
        }
    }

    if (customMatrix) {
        MatrixStructureDialog(editor) { customMatrix = false }
    }
}

private enum class MatrixFence(val environment: String, val preview: String) {
    SQUARE("bmatrix", "[ ]"),
    ROUND("pmatrix", "( )"),
    DETERMINANT("vmatrix", "| |");
}

@Composable
private fun MatrixStructureDialog(editor: Editor, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf(2) }
    var columns by remember { mutableStateOf(2) }
    var fence by remember { mutableStateOf(MatrixFence.SQUARE) }
    val rowLabel = stringResource(R.string.table_rows)
    val columnLabel = stringResource(R.string.table_columns)

    AlertDialog(
        onDismissRequest = {
            editor.flowReshowIme()
            onDismiss()
        },
        title = { Text(stringResource(R.string.math_custom_matrix_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MatrixDimensionStepper(
                    label = rowLabel,
                    value = rows,
                    decreaseLabel = stringResource(R.string.decrease_value, rowLabel),
                    increaseLabel = stringResource(R.string.increase_value, rowLabel),
                    onDecrease = { rows = (rows - 1).coerceAtLeast(1) },
                    onIncrease = { rows = (rows + 1).coerceAtMost(8) },
                )
                MatrixDimensionStepper(
                    label = columnLabel,
                    value = columns,
                    decreaseLabel = stringResource(R.string.decrease_value, columnLabel),
                    increaseLabel = stringResource(R.string.increase_value, columnLabel),
                    onDecrease = { columns = (columns - 1).coerceAtLeast(1) },
                    onIncrease = { columns = (columns + 1).coerceAtMost(8) },
                )
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.math_brackets), color = LocalPalette.current.textDim.toComposeColor(), fontSize = 13.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MatrixFence.entries.forEach { choice ->
                        FenceChoice(
                            choice = choice,
                            selected = fence == choice,
                            description = stringResource(
                                when (choice) {
                                    MatrixFence.SQUARE -> R.string.math_fence_square
                                    MatrixFence.ROUND -> R.string.math_fence_round
                                    MatrixFence.DETERMINANT -> R.string.math_fence_determinant
                                },
                            ),
                            onClick = { fence = choice },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editor.flowInsertMath(matrixLatex(rows, columns, fence))
                editor.flowReshowIme()
                onDismiss()
            }) { Text(stringResource(R.string.insert)) }
        },
        dismissButton = {
            TextButton(onClick = {
                editor.flowReshowIme()
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun MatrixDimensionStepper(
    label: String,
    value: Int,
    decreaseLabel: String,
    increaseLabel: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDecrease, modifier = Modifier.size(40.dp).semantics { contentDescription = decreaseLabel }) {
            Icon(Icons.Filled.Remove, contentDescription = null, tint = LocalPalette.current.textDim.toComposeColor())
        }
        Text(value.toString(), color = LocalPalette.current.text.toComposeColor(), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp))
        IconButton(onClick = onIncrease, modifier = Modifier.size(40.dp).semantics { contentDescription = increaseLabel }) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = LocalPalette.current.textDim.toComposeColor())
        }
    }
}

@Composable
private fun FenceChoice(choice: MatrixFence, selected: Boolean, description: String, onClick: () -> Unit) {
    Tooltip(description) {
        ModeChoiceButton(
            label = choice.preview,
            selected = selected,
            accessibilityLabel = description,
            onClick = onClick,
        )
    }
}

private fun matrixLatex(rows: Int, columns: Int, fence: MatrixFence): String {
    val body = (1..rows).joinToString("\\\\") { row ->
        (1..columns).joinToString("&") { column -> "a_{${row}${column}}" }
    }
    return "\\begin{${fence.environment}}$body\\end{${fence.environment}}"
}
