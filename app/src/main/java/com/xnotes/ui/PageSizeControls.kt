package com.xnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.PageSize
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The page size menu: every named size and Custom, after an [unset] choice (picking null) when one is named. */
@Composable
internal fun SizeDropdown(size: PageSize?, unset: String? = null, onSelect: (PageSize?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, palette.border.toComposeColor()),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(size?.let { pageSizeLabel(it) } ?: unset.orEmpty(), color = palette.text.toComposeColor(), fontSize = 14.sp)
                Icon(XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor())
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = buildList<Pair<PageSize?, String>> {
                if (unset != null) add(null to unset)
                PageSize.entries.forEach { add(it to pageSizeLabel(it)) }
            }
            options.forEachIndexed { index, (value, label) ->
                ExpressiveChoiceMenuItem(
                    selected = size == value,
                    onClick = { onSelect(value); expanded = false },
                    shapeIndex = index,
                    shapeCount = options.size,
                    text = { color -> Text(label, color = color, fontSize = 14.sp) },
                )
            }
        }
    }
}

/** A typed side of a custom page in millimetres, or null unless it parses inside the settable range. */
internal fun parseMm(raw: String): Double? =
    raw.trim().toDoubleOrNull()?.takeIf { it in PageSize.CUSTOM_MIN_MM..PageSize.CUSTOM_MAX_MM }

/** Drop a whole number's ".0" so the field reads "210", not "210.0". */
internal fun formatMm(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toInt().toString() else v.toString()
