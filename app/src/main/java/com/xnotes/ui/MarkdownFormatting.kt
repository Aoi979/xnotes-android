package com.xnotes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.text.MarkdownBlocks
import com.xnotes.core.text.MarkdownEdit
import com.xnotes.core.text.MarkdownParser
import com.xnotes.ui.icons.XnotesIcons

/** Structure first; the remaining labelled commands are reachable without scrolling the bar. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MarkdownFormattingToolbar(session: MarkdownSession, onTable: () -> Unit, onImage: () -> Unit, onUnsupported: () -> Unit) {
    var headings by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<MarkdownEdit?>(null) }
    var science by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun wrap(left: String, right: String = left) = session.apply(MarkdownEdit.wrap(
        session.input.text.toString(), session.input.selection.start, session.input.selection.end, left, right))
    fun prefix(value: String) = session.apply(MarkdownEdit.lines(
        session.input.text.toString(), session.input.selection.start, session.input.selection.end, value))
    fun indent(outdent: Boolean) = session.apply(MarkdownEdit.indent(
        session.input.text.toString(), session.input.selection.start, session.input.selection.end, outdent))
    val moreActions: @Composable () -> Unit = {
        Box {
            MdIcon(XnotesIcons.more, stringResource(R.string.md_more_format)) { focus.clearFocus(); more = true }
            DropdownMenu(more, { more = false }) {
                fun run(action: () -> Unit) { more = false; action() }
                DropdownMenuItem(text = { Text(stringResource(R.string.md_quote)) }, leadingIcon = { Icon(Icons.Outlined.FormatQuote, null) }, onClick = { run { prefix("> ") } })
                DropdownMenuItem(text = { Text(stringResource(R.string.md_list)) }, leadingIcon = { Icon(XnotesIcons.listBullet, null) }, onClick = { run { prefix("- ") } })
                DropdownMenuItem(text = { Text(stringResource(R.string.md_numbered_list)) }, leadingIcon = { Icon(XnotesIcons.listOrdered, null) }, onClick = { run { prefix("1. ") } })
                DropdownMenuItem(text = { Text(stringResource(R.string.md_task)) }, leadingIcon = { Icon(Icons.Outlined.CheckBox, null) }, onClick = { run { prefix("- [ ] ") } })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.indent)) }, onClick = { run { indent(false) } })
                DropdownMenuItem(text = { Text(stringResource(R.string.outdent)) }, onClick = { run { indent(true) } })
                DropdownMenuItem(text = { Text(stringResource(R.string.md_highlight)) }, onClick = { run { wrap("==") } })
                DropdownMenuItem(text = { Text(stringResource(R.string.md_strike)) }, onClick = { run { wrap("~~") } })
                DropdownMenuItem(text = { Text(stringResource(R.string.md_code_block)) }, onClick = { run {
                    val value = session.input.text.toString()
                    val selection = session.input.selection
                    session.apply(MarkdownEdit.codeBlock(value, selection.start, selection.end))
                } })
                DropdownMenuItem(text = { Text(stringResource(R.string.md_link)) }, leadingIcon = { Icon(XnotesIcons.link, null) }, onClick = { run {
                    link = MarkdownEdit(session.input.text.toString(), session.input.selection.start, session.input.selection.end)
                } })
                DropdownMenuItem(text = { Text(stringResource(R.string.md_image)) }, leadingIcon = { Icon(XnotesIcons.image, null) }, onClick = { run(onImage) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.md_science)) }, leadingIcon = { Icon(Icons.Outlined.Functions, null) }, onClick = { run { science = true } })
            }
        }
    }
    HorizontalFloatingToolbar(expanded = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            MdIcon(XnotesIcons.undo, stringResource(R.string.undo), session.input.undoState.canUndo) { session.input.undoState.undo() }
            MdIcon(XnotesIcons.redo, stringResource(R.string.redo), session.input.undoState.canRedo) { session.input.undoState.redo() }
            Box {
                MdIcon(Icons.Outlined.Title, stringResource(R.string.md_heading)) { focus.clearFocus(); headings = true }
                DropdownMenu(headings, { headings = false }) {
                    val source = session.input.text.toString()
                    val range = session.input.selection
                    val level = remember(source, range) { MarkdownBlocks.headingLevel(source, range.start) }
                    for (n in 0..6) ChoiceMenuItem(selected = level == n, shapeIndex = n, shapeCount = 7, onClick = {
                        headings = false
                        MarkdownBlocks.heading(session.input.text.toString(), session.input.selection.start, session.input.selection.end, n)
                            ?.let(session::apply) ?: onUnsupported()
                    }, text = { color ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (n == 0) "¶" else "H$n", Modifier.width(32.dp), color = color, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (n == 0) R.string.md_paragraph else R.string.heading_n, n), color = color)
                        }
                    })
                }
            }
            MdIcon(Icons.Outlined.TableChart, stringResource(R.string.md_table), onClick = onTable)
            MdIcon(XnotesIcons.bold, stringResource(R.string.md_bold)) { wrap("**") }
            MdIcon(XnotesIcons.italic, stringResource(R.string.md_italic)) { wrap("*") }
            MdIcon(Icons.Outlined.Code, stringResource(R.string.md_code)) { wrap("`") }
        }
        moreActions()
    }
    if (science) MarkdownScienceSheet(session, { science = false }, onUnsupported)
    link?.let { snapshot ->
        var label by remember(snapshot) { mutableStateOf(snapshot.text.substring(minOf(snapshot.start, snapshot.end), maxOf(snapshot.start, snapshot.end))) }
        var url by remember(snapshot) { mutableStateOf("") }
        val target = MarkdownParser.linkTarget(url.trim())
        AlertDialog(onDismissRequest = { link = null }, title = { Text(stringResource(R.string.md_link)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(stringResource(R.string.md_link_text)) })
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.md_link_url)) },
                    placeholder = { Text("https://example.com") }, isError = url.isNotBlank() && target == null)
            }
        }, confirmButton = {
            TextButton(enabled = target != null, onClick = {
                if (target == null || session.input.text.toString() != snapshot.text) onUnsupported() else {
                    val title = label.ifBlank { target }.replace("[", "\\[").replace("]", "\\]")
                    val destination = target.replace("<", "%3C").replace(">", "%3E")
                    val value = "[$title](<$destination>)"
                    val at = minOf(snapshot.start, snapshot.end)
                    session.apply(MarkdownEdit(snapshot.text.replaceRange(at, maxOf(snapshot.start, snapshot.end), value), at + value.length))
                }
                link = null
            }) { Text(stringResource(R.string.insert)) }
        }, dismissButton = { TextButton({ link = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
