package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.platform.FontCatalog
import com.xnotes.settings.MaterialStyle
import com.xnotes.settings.Preferences
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A label and its related controls are measured together, independently of the rest of the form. */
internal fun LazyListScope.preferenceItem(key: String, content: @Composable ColumnScope.() -> Unit) {
    item(key = key, contentType = "setting-group") {
        Column(Modifier.fillMaxWidth().animateItem(fadeInSpec = XnotesMotion.fastEffectsSpec(),
            placementSpec = XnotesMotion.defaultSpatialSpec(), fadeOutSpec = XnotesMotion.fastEffectsSpec()),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

internal fun LazyListScope.preferenceSection(key: String, @StringRes title: Int, first: Boolean = false) {
    item(key = "section-$key", contentType = "section") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!first) PreferenceSectionDivider()
            PreferenceSectionTitle(stringResource(title))
        }
    }
}

@Composable
internal fun ImportedFontPreference(font: FontCatalog.Choice, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    // FontFamily resolution can read/parse a TTF the first time. Only start for a nearby lazy
    // item and keep that work off the UI thread; the label remains usable while it resolves.
    // Choices are replaced on import/removal, even when a re-import keeps the same family id.
    val family by produceState<FontFamily?>(null, font) {
        value = withContext(Dispatchers.IO) { font.face.toComposeFamily() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            font.label,
            color = palette.text.toComposeColor(),
            fontSize = 13.sp,
            style = TextStyle(fontFamily = family),
        )
        if (font.mono) {
            Text(stringResource(R.string.mono_suffix), color = palette.accent.toComposeColor(), fontSize = 11.sp)
        }
        TextButton(onClick = onRemove) { Text(stringResource(R.string.remove), fontSize = 12.sp) }
    }
}

@Composable
private fun PreferenceSectionTitle(text: String) {
    Text(text, color = LocalPalette.current.text.toComposeColor(), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun PreferenceSectionDivider() {
    HorizontalDivider(
        color = LocalPalette.current.border.toComposeColor(),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
internal fun PreferenceFieldLabel(text: String) {
    Text(text, color = LocalPalette.current.textDim.toComposeColor(), style = MaterialTheme.typography.labelLarge)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreferenceMaterialControls(prefs: Preferences, update: (Preferences) -> Unit) {
    val palette = LocalPalette.current
    val styles = listOf(
        MaterialStyle.TONAL_SPOT to R.string.material_style_soft,
        MaterialStyle.VIBRANT to R.string.material_style_vivid,
        MaterialStyle.FIDELITY to R.string.material_style_original,
        MaterialStyle.EXPRESSIVE to R.string.material_style_playful,
        MaterialStyle.FRUIT_SALAD to R.string.material_style_fresh,
        MaterialStyle.RAINBOW to R.string.material_style_clean,
        MaterialStyle.NEUTRAL to R.string.material_style_muted,
        MaterialStyle.MONOCHROME to R.string.material_style_grayscale,
    )
    PreferenceFieldLabel(stringResource(R.string.material_style))
    ExpressiveChoiceGroup(
        options = styles.map { (style, label) -> style to stringResource(label) },
        selected = prefs.materialStyle,
        onSelect = { update(prefs.copy(materialStyle = it)) },
    )
    val description = when (prefs.materialStyle) {
        MaterialStyle.TONAL_SPOT -> R.string.material_style_soft_description
        MaterialStyle.VIBRANT -> R.string.material_style_vivid_description
        MaterialStyle.FIDELITY -> R.string.material_style_original_description
        MaterialStyle.EXPRESSIVE -> R.string.material_style_playful_description
        MaterialStyle.FRUIT_SALAD -> R.string.material_style_fresh_description
        MaterialStyle.RAINBOW -> R.string.material_style_clean_description
        MaterialStyle.NEUTRAL -> R.string.material_style_muted_description
        MaterialStyle.MONOCHROME -> R.string.material_style_grayscale_description
    }
    Text(stringResource(description), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
    val percent = Math.round(prefs.materialContrast * 100).toInt()
    PreferenceFieldLabel(stringResource(R.string.material_contrast, if (percent > 0) "+$percent%" else "$percent%"))
    Slider(
        value = prefs.materialContrast.toFloat(),
        onValueChange = { update(prefs.copy(materialContrast = Math.round(it * 10) / 10.0)) },
        valueRange = -1f..1f,
        steps = 19,
        modifier = Modifier.width(280.dp),
    )
}

@Composable
internal fun PreferenceCheckRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.heightIn(min = 48.dp).toggleable(
            value = checked,
            role = Role.Checkbox,
            enabled = enabled,
            onValueChange = onChange,
        ),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(2.dp))
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 14.sp)
    }
}


@Composable
internal fun PreferenceSegmentedSwitchRow(
    label: String,
    checked: Boolean,
    index: Int,
    count: Int,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onChange,
        ),
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        content = {
            Text(
                label,
                color = palette.text.toComposeColor(),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
    )
}

/**
 * One side of a custom page, in millimetres. The field keeps whatever is typed so a number can be
 * cleared and retyped; only a value that parses inside the settable range reaches the preference.
 */
@Composable
internal fun PreferenceMillimetreField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf(formatMm(value)) }
    var lastValue by rememberSaveable { mutableStateOf(value) }
    // Preserve an unfinished edit when this lazy item leaves/re-enters the viewport. Only a real
    // outside value change (including Reset while off-screen) should replace its saved text.
    LaunchedEffect(value) {
        if (lastValue != value) {
            lastValue = value
            if (text.trim().toDoubleOrNull() != value) text = formatMm(value)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            parseMm(raw)?.let(onChange)
        },
        singleLine = true,
        label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.width(136.dp),
    )
}

@Composable
internal fun PreferenceOptionDropdown(options: List<Pair<String, String>>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val label = options.firstOrNull { it.first == selectedId }?.second ?: options.first().second
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, palette.border.toComposeColor()),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = palette.text.toComposeColor(), fontSize = 14.sp)
                Icon(XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor())
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, (id, lbl) ->
                ExpressiveChoiceMenuItem(
                    selected = id == selectedId,
                    onClick = { onSelect(id); expanded = false },
                    shapeIndex = index,
                    shapeCount = options.size,
                    text = { fg -> Text(lbl, color = fg, fontSize = 14.sp) },
                )
            }
        }
    }
}

@Composable
internal fun PreferenceScopeHint(overview: Boolean = false) {
    Text(stringResource(if (overview) R.string.pref_overview_help else R.string.pref_live_help),
        color = LocalPalette.current.textDim.toComposeColor(), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp))
}
