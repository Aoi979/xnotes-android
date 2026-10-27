package com.xnotes.ui

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A compact M3 Expressive group for mutually exclusive, side-by-side choices. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> ExpressiveChoiceGroup(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    ButtonGroup(
        overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
        modifier = Modifier.selectableGroup(),
    ) {
        options.forEach { (value, label) ->
            toggleableItem(
                checked = value == selected,
                label = label,
                onCheckedChange = { if (it) onSelect(value) },
            )
        }
    }
}
