package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.NameTemplate
import com.xnotes.settings.CornerStyle
import com.xnotes.settings.ExplorerLayout
import com.xnotes.settings.MaterialColourMode
import com.xnotes.settings.Preferences
import com.xnotes.settings.SwatchRole
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.toComposeColor

/** One-tap filename templates offered beside the free-text field. */
private val NAME_TEMPLATE_PRESETS = listOf(
    NameTemplate.DEFAULT,
    "note_YYYY-MM-DD",
    "note_YYYY-MM-DD_HH-mm",
)
private val penButtonOptions = listOf("eraser" to R.string.tool_eraser, "pan" to R.string.tool_pan, "select" to R.string.tool_select, "none" to R.string.none)
private val tapGestureOptions = listOf(
    "none" to R.string.none,
    "undo" to R.string.undo,
    "redo" to R.string.redo,
    "toggle_pan" to R.string.toggle_pan,
    "toggle_eraser" to R.string.toggle_eraser,
    "toggle_previous" to R.string.toggle_previous,
)


@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.appearancePreferences(editor: Editor, preferences: State<Preferences>, palette: Palette, update: (Preferences) -> Unit, updateHome: (Preferences) -> Unit) {
    val prefs by preferences
    preferenceItem("appearance") {
        PreferenceFieldLabel(stringResource(R.string.pref_ui_theme))
        ExpressiveChoiceGroup(
            options = listOf(
                "system" to stringResource(R.string.theme_system),
                "dark" to stringResource(R.string.theme_dark),
                "light" to stringResource(R.string.theme_light),
                "oled" to stringResource(R.string.theme_oled),
            ),
            selected = prefs.uiAppearance,
        ) { appearance -> update(prefs.copy(uiAppearance = appearance)) }
    }

    preferenceItem("material-mode") {
        PreferenceFieldLabel(stringResource(R.string.material_tone))
        ExpressiveChoiceGroup(
            options = listOf(
                MaterialColourMode.SYSTEM to stringResource(R.string.material_system_colours),
                MaterialColourMode.SINGLE to stringResource(R.string.material_single_tone),
                MaterialColourMode.DUAL to stringResource(R.string.material_dual_tone),
            ),
            selected = prefs.materialMode,
        ) { mode -> update(prefs.copy(materialMode = mode)) }
    }

    if (prefs.materialMode != MaterialColourMode.SYSTEM) {
        item(key = "material-colour-${prefs.materialMode}", contentType = "material-colour") {
            MaterialColourPicker(prefs, update)
        }
        preferenceItem("material-style") { PreferenceMaterialControls(prefs, update) }
    } else if (Build.VERSION.SDK_INT < 31) {
        item(key = "material-fallback", contentType = "description") {
            Text(stringResource(R.string.material_system_fallback), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        }
    }

    preferenceItem("corners") {
        PreferenceFieldLabel(stringResource(R.string.pref_corners))
        ExpressiveChoiceGroup(
            options = listOf(
                CornerStyle.SHARP to stringResource(R.string.corners_sharp),
                CornerStyle.ROUNDED to stringResource(R.string.corners_rounded),
                CornerStyle.SOFT to stringResource(R.string.corners_soft),
            ),
            selected = prefs.cornerStyle,
        ) { style -> updateHome(prefs.copy(cornerStyle = style)) }
    }

    item(key = "fullscreen", contentType = "setting") {
        SettingsSwitchRow(stringResource(R.string.pref_start_fullscreen), editor.fullscreen) { update(prefs.copy(startFullscreen = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.inputPreferences(preferences: State<Preferences>, palette: Palette, update: (Preferences) -> Unit) {
    val prefs by preferences
    preferenceItem("text-blank-tap") {
        SettingsSwitchRow(stringResource(R.string.pref_tap_below_text), prefs.tapBelowTextAddsLines) {
            update(prefs.copy(tapBelowTextAddsLines = it))
        }
        Text(
            stringResource(R.string.pref_tap_below_text_help),
            color = palette.textDim.toComposeColor(),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    item(key = "finger-draws", contentType = "setting") {
        SettingsSwitchRow(stringResource(R.string.pref_finger_draws), prefs.fingerDraws) { update(prefs.copy(fingerDraws = it)) }
    }

    preferenceItem("zoom-lock-pan") {
        PreferenceFieldLabel(stringResource(R.string.pref_zoom_lock_pan))
        ExpressiveChoiceGroup(
            options = listOf(
                "single" to stringResource(R.string.pan_single),
                "double" to stringResource(R.string.pan_two),
                "none" to stringResource(R.string.pan_none),
            ),
            selected = prefs.zoomLockPan,
        ) { pan -> update(prefs.copy(zoomLockPan = pan)) }
    }

    item(key = "detect-shapes", contentType = "setting") {
        SettingsSwitchRow(stringResource(R.string.pref_detect_shapes), prefs.detectShapes) { update(prefs.copy(detectShapes = it)) }
    }

    preferenceItem("pen-button") {
        PreferenceFieldLabel(stringResource(R.string.pref_pen_button_hold))
        ExpressiveChoiceGroup(
            options = penButtonOptions.map { (id, label) -> id to stringResource(label) },
            selected = prefs.penButtonTool,
        ) { tool -> update(prefs.copy(penButtonTool = tool)) }
    }

    if (prefs.penButtonTool == "eraser" || prefs.penButtonTool == "pan") {
        item(key = "pen-button-hover", contentType = "setting") {
            SettingsSwitchRow(stringResource(R.string.pref_pen_button_hover), prefs.penButtonHover) {
                update(prefs.copy(penButtonHover = it))
            }
        }
    }

    preferenceItem("two-finger-tap") {
        PreferenceFieldLabel(stringResource(R.string.pref_two_finger_tap))
        PreferenceOptionDropdown(tapGestureOptions.map { (id, res) -> id to stringResource(res) }, prefs.twoFingerTap) {
            update(prefs.copy(twoFingerTap = it))
        }
    }

    preferenceItem("three-finger-tap") {
        PreferenceFieldLabel(stringResource(R.string.pref_three_finger_tap))
        PreferenceOptionDropdown(tapGestureOptions.map { (id, res) -> id to stringResource(res) }, prefs.threeFingerTap) {
            update(prefs.copy(threeFingerTap = it))
        }
    }

    preferenceItem("stylus-double-tap") {
        PreferenceFieldLabel(stringResource(R.string.pref_stylus_double_tap))
        PreferenceOptionDropdown(tapGestureOptions.map { (id, res) -> id to stringResource(res) }, prefs.stylusDoubleTap) {
            update(prefs.copy(stylusDoubleTap = it))
        }
    }

    preferenceItem("stylus-button-tap") {
        PreferenceFieldLabel(stringResource(R.string.pref_stylus_button_tap))
        PreferenceOptionDropdown(tapGestureOptions.map { (id, res) -> id to stringResource(res) }, prefs.stylusButtonTap) {
            update(prefs.copy(stylusButtonTap = it))
        }
    }

    preferenceItem("redmi-buttons") {
        val tapOptions = tapGestureOptions.map { (id, res) -> id to stringResource(res) }
        PreferenceFieldLabel(stringResource(R.string.pref_redmi_buttons))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.pen_button_1), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                PreferenceOptionDropdown(tapOptions, prefs.stylusButton1Tap) { update(prefs.copy(stylusButton1Tap = it)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.pen_button_2), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                PreferenceOptionDropdown(tapOptions, prefs.stylusButton2Tap) { update(prefs.copy(stylusButton2Tap = it)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.documentPreferences(editor: Editor, preferences: State<Preferences>, palette: Palette, update: (Preferences) -> Unit, onReadingStyle: () -> Unit) {
    val prefs by preferences
    preferenceSection("new-notes", R.string.pref_new_notes, first = true)

    preferenceItem("filename-template") {
        PreferenceFieldLabel(stringResource(R.string.pref_filename_template))
        Text(
            stringResource(R.string.pref_filename_template_help),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = prefs.newNoteNameTemplate,
            onValueChange = { update(prefs.copy(newNoteNameTemplate = it)) },
            singleLine = true,
            placeholder = { Text(NameTemplate.DEFAULT) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.width(280.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NAME_TEMPLATE_PRESETS.forEach { t ->
                SuggestionChip(
                    onClick = { update(prefs.copy(newNoteNameTemplate = t)) },
                    label = { Text(t, maxLines = 1) },
                )
            }
        }
        Text(
            stringResource(R.string.pref_next_note_named, "${editor.newNoteStem(emptySet())}.xnote"),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
    }

    preferenceSection("page", R.string.pref_page)

    preferenceItem("page-size") {
        PreferenceFieldLabel(stringResource(R.string.pref_default_page_size))
        SizeDropdown(prefs.defaultPageSize) { it?.let { size -> update(prefs.copy(defaultPageSize = size)) } }
    }

    preferenceItem("page-orientation") {
        if (prefs.defaultPageSize == PageSize.CUSTOM) {
            // A custom page is taken as typed, so the orientation chips have nothing to say
            // about it and are left out rather than shown doing nothing.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreferenceMillimetreField(stringResource(R.string.width_mm), prefs.customPageWidthMm) {
                    update(prefs.copy(customPageWidthMm = it))
                }
                PreferenceMillimetreField(stringResource(R.string.height_mm), prefs.customPageHeightMm) {
                    update(prefs.copy(customPageHeightMm = it))
                }
            }
        } else {
            PreferenceFieldLabel(stringResource(R.string.pref_orientation))
            ExpressiveChoiceGroup(
                options = listOf(
                    Orientation.PORTRAIT to stringResource(R.string.orientation_portrait),
                    Orientation.LANDSCAPE to stringResource(R.string.orientation_landscape),
                ),
                selected = prefs.defaultPageOrientation,
            ) { orientation -> update(prefs.copy(defaultPageOrientation = orientation)) }
        }
    }

    item(key = "page-borders", contentType = "setting") {
        SettingsSwitchRow(stringResource(R.string.pref_show_page_borders), !prefs.hidePageBorders) {
            update(prefs.copy(hidePageBorders = !it))
        }
    }

    preferenceItem("side-margin") {
        PreferenceFieldLabel(stringResource(R.string.pref_side_margin_px, prefs.sideMargin.toInt()))
        Slider(
            value = prefs.sideMargin.toFloat(),
            onValueChange = { update(prefs.copy(sideMargin = it.toDouble())) },
            valueRange = 0f..64f,
            modifier = Modifier.width(280.dp),
        )
    }

    preferenceItem("page-colour") {
        PreferenceFieldLabel(stringResource(R.string.pref_page_colour))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchCircles(editor, SwatchRole.PAPER, prefs.pageColor) { update(prefs.copy(pageColor = it)) }
        }
    }

    item(key = "page-colour-theme", contentType = "setting") {
        SettingsSwitchRow(stringResource(R.string.pref_page_colour_follows_theme), prefs.pageColor == null) {
            update(prefs.copy(pageColor = if (it) null else editor.swatches(SwatchRole.PAPER).first()))
        }
    }


    preferenceItem("markdown-reading") {
        PreferenceLink(R.string.pref_markdown_reading, R.string.pref_markdown_reading_help, Icons.Outlined.Tune) { onReadingStyle() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.explorerPreferences(editor: Editor, preferences: State<Preferences>, palette: Palette, updateHome: (Preferences) -> Unit, onNameColor: (Rgba) -> Unit) {
    val prefs by preferences
    item(key = "explorer-help", contentType = "setting") {
        Text(
            stringResource(R.string.pref_home_explorer_help),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
    }

    preferenceItem("explorer-layout") {
        PreferenceFieldLabel(stringResource(R.string.pref_default_layout))
        ExpressiveChoiceGroup(
            options = ExplorerLayout.entries.map { it to stringResource(it.labelRes) },
            selected = editor.explorerView.layout,
            onSelect = editor::setDefaultLayout,
        )
    }

    preferenceItem("explorer-switcher") {
        PreferenceFieldLabel(stringResource(R.string.pref_switcher_layouts))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExplorerLayout.entries.forEach { l ->
                val on = l in prefs.switcherLayouts
                PreferenceCheckRow(
                    stringResource(l.labelRes),
                    on,
                    modifier = Modifier.fillMaxWidth(0.48f),
                    enabled = !on || prefs.switcherLayouts.size > 1,
                ) {
                    val next = if (on) prefs.switcherLayouts - l else prefs.switcherLayouts + l
                    if (next.isNotEmpty()) updateHome(prefs.copy(switcherLayouts = ExplorerLayout.entries.filter { it in next }))
                }
            }
        }
    }

    preferenceItem("sidebar-sections") {
        PreferenceFieldLabel(stringResource(R.string.pref_sidebar_sections))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun section(label: String, on: Boolean, flip: () -> Preferences) =
                PreferenceCheckRow(label, on, modifier = Modifier.fillMaxWidth(0.48f)) { updateHome(flip()) }
            section(stringResource(R.string.recent), prefs.sidebarRecent) { prefs.copy(sidebarRecent = !prefs.sidebarRecent) }
            section(stringResource(R.string.pinned), prefs.sidebarPinned) { prefs.copy(sidebarPinned = !prefs.sidebarPinned) }
            section(stringResource(R.string.toolbar_colours), prefs.sidebarColours) { prefs.copy(sidebarColours = !prefs.sidebarColours) }
            if (prefs.trashDays != 0) section(stringResource(R.string.trash), prefs.sidebarTrash) { prefs.copy(sidebarTrash = !prefs.sidebarTrash) }
        }
    }

    preferenceItem("home-opens-to") {
        PreferenceFieldLabel(stringResource(R.string.pref_home_opens_to))
        ExpressiveChoiceGroup(
            options = listOf(
                "top" to stringResource(R.string.top_folder),
                "last" to stringResource(R.string.last_folder),
                "shelves" to stringResource(R.string.recent_and_pinned),
            ),
            selected = prefs.homeOpensTo,
            onSelect = { updateHome(prefs.copy(homeOpensTo = it)) },
        )
    }

    preferenceItem("date-style") {
        val words = rememberExplorerWords()
        PreferenceFieldLabel(stringResource(R.string.pref_dates))
        ExpressiveChoiceGroup(
            options = listOf(
                "relative" to words.daysAgo(2),
                "day" to "${words.shortWeekday(java.time.DayOfWeek.WEDNESDAY)} ${clockText(words, java.time.LocalTime.of(17, 30), true)}",
                "date" to words.dayMonthYear(16, java.time.Month.SEPTEMBER, 2026),
            ),
            selected = prefs.dateStyle,
            onSelect = { updateHome(prefs.copy(dateStyle = it)) },
        )
    }

    preferenceItem("tapping-file") {
        PreferenceFieldLabel(stringResource(R.string.pref_tapping_file))
        ExpressiveChoiceGroup(
            options = listOf(
                false to stringResource(R.string.tap_opens),
                true to stringResource(R.string.tap_previews),
            ),
            selected = prefs.tapPreviews,
            onSelect = { updateHome(prefs.copy(tapPreviews = it)) },
        )
    }

    preferenceItem("keep-deleted") {
        PreferenceFieldLabel(stringResource(R.string.pref_keep_deleted))
        ExpressiveChoiceGroup(
            options = listOf(
                0 to stringResource(R.string.off),
                7 to pluralStringResource(R.plurals.days_count, 7, 7),
                30 to pluralStringResource(R.plurals.days_count, 30, 30),
                Preferences.TRASH_FOREVER to stringResource(R.string.until_emptied),
            ),
            selected = prefs.trashDays,
            onSelect = { updateHome(prefs.copy(trashDays = it)) },
        )
    }

    if (editor.browseRoot != null) {
        preferenceItem("colour-names") {
            PreferenceFieldLabel(stringResource(R.string.pref_colour_names))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                editor.colorNames.entries.sortedBy { it.value.lowercase() }.forEach { (c, name) ->
                    Row(
                        Modifier.clip(MaterialTheme.shapes.small).background(palette.surface.toComposeColor())
                            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                            .clickable { onNameColor(c) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(codeTint(c, palette)))
                        Text(name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1)
                    }
                }
                var picking by remember { mutableStateOf(false) }
                Box {
                    AssistChip(
                        onClick = { picking = true },
                        label = { Text(stringResource(R.string.name_a_colour)) },
                        leadingIcon = { Icon(XnotesIcons.plus, null, modifier = Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        ColorCodeMenuContent { c -> picking = false; if (c != null) onNameColor(c) }
                    }
                }
            }
        }
    }

    item(key = "folder-options", contentType = "setting") {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            PreferenceSegmentedSwitchRow(
                stringResource(R.string.pref_per_folder_views), prefs.perFolderViews, index = 0, count = 3,
            ) { updateHome(prefs.copy(perFolderViews = it)) }
            PreferenceSegmentedSwitchRow(
                stringResource(R.string.pref_show_folder_counts), prefs.showFolderCounts, index = 1, count = 3,
            ) { updateHome(prefs.copy(showFolderCounts = it)) }
            PreferenceSegmentedSwitchRow(
                stringResource(R.string.pref_show_extensions), prefs.showExtensions, index = 2, count = 3,
            ) { updateHome(prefs.copy(showExtensions = it)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.exportPreferences(preferences: State<Preferences>, palette: Palette, updateHome: (Preferences) -> Unit, onPdfSettings: () -> Unit) {
    val prefs by preferences
    item(key = "pdf-bookmarks", contentType = "setting") {
        SettingsSwitchRow(stringResource(R.string.pref_pdf_heading_bookmarks), prefs.pdfHeadingBookmarks) {
            updateHome(prefs.copy(pdfHeadingBookmarks = it))
        }
    }


    preferenceItem("pdf-bookmarks-scope") {
        Text(stringResource(R.string.pref_pdf_bookmarks_scope), color = palette.textDim.toComposeColor(), style = MaterialTheme.typography.bodySmall)
    }
    preferenceItem("markdown-pdf-layout") {
        PreferenceLink(R.string.pref_markdown_pdf, R.string.pref_markdown_pdf_help, Icons.Outlined.PictureAsPdf) { onPdfSettings() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.performancePreferences(preferences: State<Preferences>, palette: Palette, update: (Preferences) -> Unit) {
    val prefs by preferences
    preferenceItem("cache-resolution") {
        PreferenceFieldLabel(stringResource(R.string.pref_max_cache_px, prefs.maxCacheResolution))
        Slider(
            value = prefs.maxCacheResolution.toFloat(),
            onValueChange = { update(prefs.copy(maxCacheResolution = Math.round(it))) },
            valueRange = 1024f..4096f,
            steps = 5,
            modifier = Modifier.width(280.dp),
        )
        Text(
            stringResource(R.string.pref_cache_help),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
    }

    preferenceItem("front-buffering") {
        SettingsSwitchRow(stringResource(R.string.pref_front_buffering_enabled), !prefs.disableFrontBuffering) {
            update(prefs.copy(disableFrontBuffering = !it))
        }
        Text(
            stringResource(R.string.pref_front_buffering_help),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.textPreferences(editor: Editor, preferences: State<Preferences>, palette: Palette, update: (Preferences) -> Unit, onImportCodeTheme: () -> Unit, onImportFont: () -> Unit) {
    val prefs by preferences
    if (editor.treeSitterAvailable) {
        preferenceSection("code-highlighting", R.string.pref_code_highlighting, first = true)

        preferenceItem("code-language") {
            val langOptions = editor.scmLanguages().map { it to it }
            PreferenceFieldLabel(stringResource(R.string.pref_default_code_language))
            Text(
                stringResource(R.string.pref_code_language_help),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            PreferenceOptionDropdown(listOf("plain" to stringResource(R.string.code_plain)) + langOptions, prefs.defaultCodeLanguage) {
                update(prefs.copy(defaultCodeLanguage = it))
            }
        }

        preferenceItem("code-theme") {
            PreferenceFieldLabel(stringResource(R.string.pref_code_theme))
            Text(
                stringResource(R.string.pref_code_theme_help),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            if (editor.hasCustomCodeTheme) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        prefs.codeThemeName ?: stringResource(R.string.custom_theme),
                        color = palette.text.toComposeColor(),
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = { editor.resetCodeTheme() }) { Text(stringResource(R.string.reset), fontSize = 13.sp) }
                }
            }
            TextButton(onClick = { onImportCodeTheme() }) { Text(stringResource(R.string.import_theme), fontSize = 13.sp) }
        }
    }

    preferenceSection("fonts", R.string.pref_fonts, first = !editor.treeSitterAvailable)

    preferenceItem("font-import") {
        Text(
            stringResource(R.string.pref_fonts_help),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        TextButton(onClick = { onImportFont() }) { Text(stringResource(R.string.import_font), fontSize = 13.sp) }
    }

    items(editor.customFonts, key = { "font-${it.face.id}" }, contentType = { "font" }) { font ->
        ImportedFontPreference(font) { editor.removeCustomFont(font.face) }
    }
}
