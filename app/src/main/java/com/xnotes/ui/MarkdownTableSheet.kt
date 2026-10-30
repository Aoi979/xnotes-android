package com.xnotes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.text.*
import com.xnotes.ui.icons.XnotesIcons
import kotlinx.coroutines.launch

internal data class MarkdownTableRequest(val source: String, val start: Int, val end: Int, val region: MarkdownTable.Region?)

/** Table text is a local draft until Apply; one commit becomes one source undo operation. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MarkdownTableSheet(request: MarkdownTableRequest, onDismiss: () -> Unit, onApply: (MarkdownTable) -> Boolean) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var sizing by remember { mutableStateOf(request.region == null) }
    var rows by remember { mutableStateOf(TableDefaults.DEFAULT_ROWS.toString()) }
    var columns by remember { mutableStateOf(TableDefaults.DEFAULT_COLS.toString()) }
    var table by remember { mutableStateOf(request.region?.table ?: MarkdownTable.empty(3, 3)) }
    var selectedRow by remember { mutableIntStateOf(0) }
    var selectedCol by remember { mutableIntStateOf(0) }
    var rowMenu by remember { mutableStateOf(false) }
    var colMenu by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val validRows = rows.toIntOrNull()?.takeIf { it in 1..TableDefaults.MAX_ROWS }
    val validCols = columns.toIntOrNull()?.takeIf { it in 1..TableDefaults.MAX_COLS }
    val list = rememberLazyListState()
    val horizontal = rememberScrollState()
    val requesters = remember { HashMap<Pair<Int, Int>, FocusRequester>() }
    fun close(apply: Boolean) {
        if (closing) return
        focus.clearFocus()
        if (apply && !onApply(table)) return
        closing = true
        scope.launch { sheet.hide(); onDismiss() }
    }
    fun next(row: Int, col: Int) {
        val r = row + if (col == table.columns - 1) 1 else 0
        val c = if (col == table.columns - 1) 0 else col + 1
        if (r == table.rows.size) {
            if (table.rows.size >= TableDefaults.MAX_ROWS) { focus.clearFocus(); return }
            table = table.insertRow(r)
        }
        scope.launch {
            list.animateScrollToItem(r)
            withFrameNanos { }
            requesters[r to c]?.requestFocus()
        }
    }
    ModalBottomSheet(sheetState = sheet, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (request.region == null) R.string.insert_table else R.string.md_edit_table), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.md_table_size, if (sizing) validRows ?: 0 else table.rows.size,
                        if (sizing) validCols ?: 0 else table.columns), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MdIcon(XnotesIcons.close, stringResource(R.string.cancel), !closing) { close(false) }
            }
            AnimatedContent(sizing, modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { materialFadeThrough(smallSurface = true).using(null) }, label = "Markdown table size and cells") { chooseSize ->
                if (chooseSize) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        for (r in 1..5) Row {
                            for (c in 1..5) {
                                val label = stringResource(R.string.md_table_size, r, c)
                                Surface(onClick = { rows = r.toString(); columns = c.toString() }, shape = MaterialTheme.shapes.extraSmall,
                                    color = if (r <= (validRows ?: 0) && c <= (validCols ?: 0)) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.size(48.dp).padding(2.dp).semantics { contentDescription = label }) {
                                    Box(contentAlignment = Alignment.Center) { if (r == validRows && c == validCols) Icon(XnotesIcons.check, null, Modifier.size(20.dp)) }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(rows, { if (it.length <= 2 && it.all(Char::isDigit)) rows = it }, Modifier.weight(1f), singleLine = true,
                            isError = validRows == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.table_rows)) },
                            supportingText = { Text("1–${TableDefaults.MAX_ROWS}") })
                        OutlinedTextField(columns, { if (it.length <= 2 && it.all(Char::isDigit)) columns = it }, Modifier.weight(1f), singleLine = true,
                            isError = validCols == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.table_columns)) },
                            supportingText = { Text("1–${TableDefaults.MAX_COLS}") })
                    }
                    Text(stringResource(R.string.md_table_hint), style = MaterialTheme.typography.bodyMedium)
                } else Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton({ rowMenu = true }) { Text(stringResource(if (selectedRow == 0) R.string.md_table_header else R.string.md_table_row, selectedRow)); Icon(Icons.Outlined.ArrowDropDown, null) }
                            DropdownMenu(rowMenu, { rowMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.md_insert_before)) }, enabled = selectedRow > 0 && table.rows.size < TableDefaults.MAX_ROWS, onClick = {
                                    rowMenu = false; table = table.insertRow(selectedRow)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.md_insert_after)) }, enabled = table.rows.size < TableDefaults.MAX_ROWS, onClick = {
                                    rowMenu = false; table = table.insertRow(selectedRow + 1); selectedRow++
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.md_delete_row)) }, enabled = selectedRow > 0, onClick = {
                                    rowMenu = false; table = table.removeRow(selectedRow); selectedRow = selectedRow.coerceAtMost(table.rows.lastIndex)
                                })
                            }
                        }
                        Box {
                            TextButton({ colMenu = true }) { Text(stringResource(R.string.md_table_column, selectedCol + 1)); Icon(Icons.Outlined.ArrowDropDown, null) }
                            DropdownMenu(colMenu, { colMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.md_insert_before)) }, enabled = table.columns < TableDefaults.MAX_COLS, onClick = { colMenu = false; table = table.insertColumn(selectedCol) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.md_insert_after)) }, enabled = table.columns < TableDefaults.MAX_COLS, onClick = { colMenu = false; table = table.insertColumn(selectedCol + 1); selectedCol++ })
                                DropdownMenuItem(text = { Text(stringResource(R.string.md_delete_column)) }, enabled = table.columns > 1, onClick = {
                                    colMenu = false; table = table.removeColumn(selectedCol); selectedCol = selectedCol.coerceAtMost(table.columns - 1)
                                })
                            }
                        }
                        val alignments = listOf(ParaAlign.LEFT to Icons.AutoMirrored.Outlined.FormatAlignLeft,
                            ParaAlign.CENTER to Icons.Outlined.FormatAlignCenter, ParaAlign.RIGHT to Icons.AutoMirrored.Outlined.FormatAlignRight)
                        for ((alignment, icon) in alignments) Tooltip(stringResource(when (alignment) {
                            ParaAlign.LEFT -> R.string.md_align_left; ParaAlign.CENTER -> R.string.md_align_center; else -> R.string.md_align_right
                        })) {
                            IconToggleButton(checked = table.alignments[selectedCol] == alignment, onCheckedChange = { table = table.align(selectedCol, if (it) alignment else null) },
                                shapes = IconButtonDefaults.toggleableShapes(), modifier = Modifier.size(48.dp)) {
                                Icon(icon, stringResource(when (alignment) { ParaAlign.LEFT -> R.string.md_align_left; ParaAlign.CENTER -> R.string.md_align_center; else -> R.string.md_align_right }))
                            }
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().horizontalScroll(horizontal).width((48 + table.columns * 164).dp),
                        contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(table.rows) { r, cells ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (r == 0) "#" else "$r", Modifier.width(48.dp).padding(start = 12.dp), style = MaterialTheme.typography.labelMedium)
                                cells.forEachIndexed { c, value ->
                                    val requester = remember(r, c) { FocusRequester() }
                                    SideEffect { requesters[r to c] = requester }
                                    OutlinedTextField(value, { table = table.cell(r, c, it) }, modifier = Modifier.width(164.dp).padding(end = 8.dp)
                                        .focusRequester(requester).onFocusChanged { if (it.isFocused) { selectedRow = r; selectedCol = c } },
                                        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { next(r, c) }),
                                        label = { Text(stringResource(if (r == 0) R.string.md_header_cell else R.string.md_body_cell, r, c + 1)) })
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ close(false) }, enabled = !closing) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    focus.clearFocus()
                    if (sizing) { table = MarkdownTable.empty(validRows!!, validCols!!); sizing = false } else close(true)
                }, enabled = !closing && (!sizing || validRows != null && validCols != null)) {
                    Text(stringResource(if (sizing) R.string.md_edit_cells else R.string.done))
                }
            }
        }
    }
}
