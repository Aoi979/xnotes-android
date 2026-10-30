package com.xnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.text.MarkdownEdit
import com.xnotes.ui.icons.XnotesIcons
import kotlinx.coroutines.launch

/** A focused insertion sheet; opening it never edits the source or crowds the floating toolbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownScienceSheet(session: MarkdownSession, onDismiss: () -> Unit, onStale: () -> Unit) {
    val snapshot = remember { MarkdownEdit(session.input.text.toString(), session.input.selection.start, session.input.selection.end) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun close(edit: MarkdownEdit? = null) {
        if (closing) return
        closing = true
        scope.launch {
            state.hide()
            onDismiss()
            if (edit != null) {
                if (session.input.text.toString() == snapshot.text) session.apply(edit) else onStale()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = { close() }, sheetState = state) {
        Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.md_science), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                MdIcon(XnotesIcons.close, stringResource(R.string.cancel)) { close() }
            }
            Text(stringResource(R.string.md_science_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (display in listOf(false, true)) Surface(onClick = {
                close(MarkdownEdit.formula(snapshot.text, snapshot.start, snapshot.end, display))
            }, enabled = !closing, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                ListItem(headlineContent = { Text(stringResource(if (display) R.string.md_math_block else R.string.md_math_inline)) },
                    supportingContent = { Text(if (display) "\$\$ … \$\$" else "\$ … \$") },
                    leadingContent = { Icon(Icons.Outlined.Functions, null) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val templates = listOf(
                R.string.md_mermaid_flowchart to "flowchart TD\n    A[Start] --> B{Decision}\n    B -->|Yes| C[Done]\n    B -->|No| A",
                R.string.md_mermaid_sequence to "sequenceDiagram\n    participant A as Alice\n    participant B as Bob\n    A->>B: Hello\n    B-->>A: Hi",
            )
            templates.forEachIndexed { index, (title, source) ->
                Surface(onClick = { close(MarkdownEdit.codeBlock(snapshot.text, snapshot.start, snapshot.end, "mermaid", source)) },
                    enabled = !closing, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    ListItem(headlineContent = { Text(stringResource(title)) }, supportingContent = { Text("Mermaid") },
                        leadingContent = { Icon(if (index == 0) Icons.Outlined.AccountTree else Icons.Outlined.SwapHoriz, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                }
            }
        }
    }
}
