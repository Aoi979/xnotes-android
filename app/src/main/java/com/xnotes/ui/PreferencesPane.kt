package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.settings.Preferences
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Settings navigation and panel lifetime. Category forms and toolbar dragging own their details. */
@Composable
fun PreferencesPane(
    editor: Editor,
    compact: Boolean,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    onBackToHome: () -> Unit,
    onImportCodeTheme: () -> Unit = {},
    onImportFont: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val focusManager = LocalFocusManager.current
    val preferenceState = remember { mutableStateOf(editor.preferences) }
    var prefs by preferenceState
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    val category = PreferenceCategory.entries.firstOrNull { it.id == categoryId }
    val overviewScroll = rememberLazyListState()
    val toolbarScroll = rememberLazyListState()
    var canvasTab by remember { mutableStateOf(false) }
    var resetMenu by remember { mutableStateOf(false) }
    var resetPreferences by remember { mutableStateOf(false) }
    var markdownReading by remember { mutableStateOf(false) }
    var markdownPdf by remember { mutableStateOf(false) }
    var namingColor by remember { mutableStateOf<Rgba?>(null) }

    fun update(value: Preferences) { prefs = value; editor.applyPreferences(value) }
    fun updateHome(value: Preferences) { prefs = value; editor.applyHomePreferences(value) }
    fun backToCategories() { focusManager.clearFocus(); categoryId = null }
    BackHandler(enabled = category != null, onBack = ::backToCategories)
    LaunchedEffect(editor.prefsVersion) { prefs = editor.preferences }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    category != null -> {
                        MdIcon(XnotesIcons.prev, stringResource(R.string.pref_back_categories), onClick = ::backToCategories)
                        Spacer(Modifier.width(4.dp))
                    }
                    compact -> {
                        Tooltip(stringResource(R.string.back_to_home)) {
                            IconButton(onClick = onBackToHome) {
                                Icon(XnotesIcons.prev, stringResource(R.string.back_to_home), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    !sidebarOpen -> {
                        Tooltip(stringResource(R.string.show_sidebar)) {
                            IconButton(onClick = onShowSidebar) {
                                Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Text(stringResource(category?.title ?: R.string.preferences), color = palette.text.toComposeColor(),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    MdIcon(XnotesIcons.more, stringResource(R.string.more)) { resetMenu = true }
                    DropdownMenu(resetMenu, { resetMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pref_reset_all)) }, onClick = {
                            resetMenu = false; resetPreferences = true
                        })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(category, modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { materialFadeThrough().using(null) }, label = "Settings category") { shown ->
                if (shown == PreferenceCategory.TOOLBAR) {
                    ToolbarPreferences(editor, preferenceState, canvasTab, { canvasTab = it }, toolbarScroll,
                        active = category == shown, updateHome = ::updateHome)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().imePadding(),
                        state = if (shown == null) overviewScroll else rememberLazyListState(),
                        contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "settings-scope", contentType = "description") { PreferenceScopeHint(overview = shown == null) }
                        when (shown) {
                            null -> PreferenceCategory.entries.forEach { destination ->
                                item(key = "category-${destination.id}", contentType = "category") {
                                    PreferenceLink(destination.title, destination.summary, destination.icon) {
                                        focusManager.clearFocus(); categoryId = destination.id
                                    }
                                }
                            }
                            PreferenceCategory.APPEARANCE -> appearancePreferences(editor, preferenceState, palette, ::update, ::updateHome)
                            PreferenceCategory.INPUT -> inputPreferences(preferenceState, palette, ::update)
                            PreferenceCategory.DOCUMENTS -> documentPreferences(editor, preferenceState, palette, ::update) { markdownReading = true }
                            PreferenceCategory.EXPLORER -> explorerPreferences(editor, preferenceState, palette, ::updateHome) { namingColor = it }
                            PreferenceCategory.EXPORT -> exportPreferences(preferenceState, palette, ::updateHome) { markdownPdf = true }
                            PreferenceCategory.PERFORMANCE -> performancePreferences(preferenceState, palette, ::update)
                            PreferenceCategory.TEXT -> textPreferences(editor, preferenceState, palette, ::update, onImportCodeTheme, onImportFont)
                            PreferenceCategory.TOOLBAR -> Unit // Owns a separate lazy list and drag lifetime above.
                        }
                    }
                }
            }
        }
    }
    namingColor?.let { color -> ColorNameDialog(editor, color) { namingColor = null } }
    if (markdownReading) MarkdownStyleSheet(editor.preferences.markdownStyle,
        onDismiss = { markdownReading = false }, onApply = { editor.setMarkdownStyle(it); prefs = editor.preferences })
    if (markdownPdf) MarkdownPdfSheet(editor.preferences.markdownPdfStyle, editor.preferences.markdownStyle,
        onDismiss = { markdownPdf = false }, allowExport = false,
        onConfirm = { style, _ -> editor.setMarkdownPdfStyle(style); prefs = editor.preferences })
    if (resetPreferences) AlertDialog(onDismissRequest = { resetPreferences = false },
        title = { Text(stringResource(R.string.pref_reset_all)) }, text = { Text(stringResource(R.string.pref_reset_confirmation)) },
        confirmButton = { TextButton({ resetPreferences = false; update(Preferences()) }) { Text(stringResource(R.string.reset)) } },
        dismissButton = { TextButton({ resetPreferences = false }) { Text(stringResource(R.string.cancel)) } })
}
