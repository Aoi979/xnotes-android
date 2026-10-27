package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Saved views. An infinite canvas has no pages to navigate by, so a named viewport is what stands
 * in for a bookmark: save where you are, jump back to it later.
 */
@Composable
fun CanvasWaypointsPopup(editor: InfiniteEditor, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    var name by remember { mutableStateOf("") }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(268.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_waypoints))

            if (editor.waypoints.isEmpty()) {
                Text(
                    stringResource(R.string.no_waypoints),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            for (waypoint in editor.waypoints) {
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                waypoint.name,
                                color = palette.text.toComposeColor(),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${Math.round(waypoint.zoom * 100)}%",
                                color = palette.textDim.toComposeColor(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = { editor.jumpTo(waypoint); onDismiss() },
                    trailingIcon = {
                        Tooltip(stringResource(R.string.remove)) {
                            IconButton(onClick = { editor.removeWaypoint(waypoint) }) {
                                Icon(
                                    XnotesIcons.close,
                                    contentDescription = stringResource(R.string.remove),
                                    tint = palette.textDim.toComposeColor(),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.size(8.dp))
            StyleCaption(stringResource(R.string.caption_save_view))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                    if (name.isNotBlank()) {
                        editor.saveWaypoint(name)
                        name = ""
                    }
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.save)) }
            }

            Spacer(Modifier.size(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.toolbar_minimap),
                        color = palette.text.toComposeColor(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = editor.minimapVisible, onCheckedChange = { editor.toggleMinimap() })
                }
                TextButton(onClick = { editor.zoomToFit(); onDismiss() }) { Text(stringResource(R.string.fit_all)) }
            }
        }
    }
}
