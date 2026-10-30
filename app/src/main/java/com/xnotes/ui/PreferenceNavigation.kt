package com.xnotes.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xnotes.R

/** Navigation over existing preferences; these categories add no stored settings. */
internal enum class PreferenceCategory(val id: String, @StringRes val title: Int, @StringRes val summary: Int) {
    APPEARANCE("general", R.string.pref_general, R.string.pref_summary_appearance),
    INPUT("input", R.string.pref_input, R.string.pref_summary_input),
    DOCUMENTS("documents", R.string.pref_documents, R.string.pref_summary_documents),
    EXPLORER("explorer", R.string.pref_home_explorer, R.string.pref_summary_explorer),
    EXPORT("export", R.string.pref_pdf_export, R.string.pref_summary_export),
    TEXT("text", R.string.pref_text, R.string.pref_summary_text),
    TOOLBAR("toolbar", R.string.pref_toolbar, R.string.pref_summary_toolbar),
    PERFORMANCE("performance", R.string.pref_performance, R.string.pref_summary_performance);

    val icon: ImageVector get() = when (this) {
        APPEARANCE -> Icons.Outlined.Palette; INPUT -> Icons.Outlined.Gesture; DOCUMENTS -> Icons.Outlined.Description
        EXPLORER -> Icons.Outlined.Folder; EXPORT -> Icons.Outlined.PictureAsPdf; TEXT -> Icons.Outlined.TextFields
        TOOLBAR -> Icons.Outlined.Tune; PERFORMANCE -> Icons.Outlined.Speed
    }
}

@Composable
internal fun PreferenceLink(@StringRes title: Int, @StringRes summary: Int, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        ListItem(headlineContent = { Text(stringResource(title)) }, supportingContent = { Text(stringResource(summary)) },
            leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

/** A single hit target and a single accessibility node, including the label. */
@Composable
internal fun SettingsSwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
        Switch(checked, null, enabled = enabled)
    }
}

@Composable
internal fun SettingsCheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(label, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
