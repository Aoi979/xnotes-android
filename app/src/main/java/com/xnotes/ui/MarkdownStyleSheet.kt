package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.platform.MarkdownPage
import com.xnotes.settings.MarkdownStyle
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalMarkdownStyle = staticCompositionLocalOf { MarkdownStyle.DEFAULT }

/** A small overview and one focused section at a time; settings never compete with the editor. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MarkdownStyleSheet(initial: MarkdownStyle, onDismiss: () -> Unit, onApply: (MarkdownStyle) -> Unit) {
    var draft by remember { mutableStateOf(initial.clamped()) }
    var section by remember { mutableIntStateOf(-1) }
    var heading by remember { mutableIntStateOf(0) }
    var reset by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val names = listOf(R.string.md_style_type, R.string.md_style_headings, R.string.md_style_colors,
        R.string.md_style_blocks, R.string.md_style_media, R.string.md_science)
    val icons = listOf(Icons.Outlined.TextFields, Icons.Outlined.Title, Icons.Outlined.Palette,
        Icons.Outlined.Code, Icons.Outlined.TableChart, Icons.Outlined.Functions)
    fun close(apply: Boolean) {
        if (closing) return
        closing = true
        scope.launch { state.hide(); if (apply) onApply(draft.clamped()); onDismiss() }
    }
    ModalBottomSheet(sheetState = state, onDismissRequest = onDismiss) {
        BackHandler(enabled = section >= 0) { section = -1 }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (section >= 0) MdIcon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.md_style_back)) { section = -1 }
                Text(stringResource(if (section < 0) R.string.md_style_title else names[section]), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                Box {
                    MdIcon(Icons.Outlined.Restore, stringResource(R.string.reset)) { reset = true }
                    DropdownMenu(reset, { reset = false }) {
                        if (section >= 0) DropdownMenuItem(text = { Text(stringResource(R.string.md_style_reset_section)) }, onClick = { draft = resetMarkdownSection(draft, section); reset = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.md_style_reset_all)) }, onClick = { draft = MarkdownStyle.DEFAULT; reset = false })
                    }
                }
                MdIcon(XnotesIcons.close, stringResource(R.string.cancel)) { close(false) }
            }
            AnimatedContent(section, modifier = Modifier.weight(1f).fillMaxWidth(), transitionSpec = { materialFadeThrough(smallSurface = true).using(null) }, label = "Markdown style section") { page ->
                if (page < 0) LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { MarkdownStyleSample(draft) }
                    item { Text(stringResource(R.string.md_style_hint), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(names) { i, title ->
                        Surface(onClick = { section = i }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            ListItem(headlineContent = { Text(stringResource(title)) }, leadingContent = { Icon(icons[i], null) },
                                supportingContent = { Text(when (i) {
                                    0 -> "${draft.fontSize} sp · ${draft.lineHeight} × · ${draft.contentWidth} dp"
                                    1 -> "H1–H6 · ${draft.headingSizes.joinToString(" / ")}"
                                    2 -> stringResource(R.string.md_style_color_summary)
                                    3 -> stringResource(R.string.md_style_block_summary)
                                    4 -> stringResource(R.string.md_style_media_summary)
                                    5 -> stringResource(R.string.md_style_science_summary)
                                    else -> ""
                                }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        }
                    }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        0 -> {
                            ExpressiveChoiceGroup(listOf("sans-serif" to stringResource(R.string.md_style_sans), "serif" to stringResource(R.string.md_style_serif), "monospace" to stringResource(R.string.md_style_mono)), draft.font) { draft = draft.copy(font = it) }
                            StyleSlider(R.string.md_style_font_size, draft.fontSize.toFloat(), 12f..28f, "sp") { draft = draft.copy(fontSize = it.roundToInt()) }
                            StyleSlider(R.string.md_style_line_height, draft.lineHeight.toFloat(), 1.2f..2.4f, "×", true) { draft = draft.copy(lineHeight = (it * 100).roundToInt() / 100.0) }
                            StyleSlider(R.string.md_style_paragraph_gap, draft.paragraphGap.toFloat(), 4f..32f, "dp") { draft = draft.copy(paragraphGap = it.roundToInt()) }
                            StyleSlider(R.string.md_style_width, draft.contentWidth.toFloat(), 400f..1200f, "dp") { draft = draft.copy(contentWidth = (it / 10).roundToInt() * 10) }
                            StyleSlider(R.string.md_style_padding, draft.padding.toFloat(), 8f..64f, "dp") { draft = draft.copy(padding = it.roundToInt()) }
                            StyleSwitch(R.string.md_style_justify, draft.justified) { draft = draft.copy(justified = it) }
                        }
                        1 -> {
                            ExpressiveChoiceGroup((0..5).map { it to "H${it + 1}" }, heading) { heading = it }
                            StyleSlider(R.string.md_style_heading_size, draft.headingSizes[heading].toFloat(), 14f..56f, "sp") { value -> draft = draft.copy(headingSizes = draft.headingSizes.mapIndexed { i, old -> if (i == heading) value.roundToInt() else old }) }
                            StyleSlider(R.string.md_style_heading_gap, draft.headingGap.toFloat(), 8f..48f, "dp") { draft = draft.copy(headingGap = it.roundToInt()) }
                            StyleSwitch(R.string.md_style_heading_bold, draft.headingBold) { draft = draft.copy(headingBold = it) }
                            StyleSwitch(R.string.md_style_heading_rules, draft.headingRules) { draft = draft.copy(headingRules = it) }
                        }
                        2 -> {
                            ExpressiveChoiceGroup(listOf("system" to stringResource(R.string.md_style_follow), "light" to stringResource(R.string.md_style_light), "sepia" to stringResource(R.string.md_style_sepia), "dark" to stringResource(R.string.md_style_dark)), draft.theme) { draft = draft.copy(theme = it) }
                            StyleColor(R.string.md_style_text_color, draft.foreground) { draft = draft.copy(foreground = it) }
                            StyleColor(R.string.md_style_paper_color, draft.background) { draft = draft.copy(background = it) }
                            StyleColor(R.string.md_style_heading_color, draft.headingColor) { draft = draft.copy(headingColor = it) }
                            StyleColor(R.string.md_style_accent_color, draft.accent) { draft = draft.copy(accent = it) }
                            StyleColor(R.string.md_style_code_color, draft.codeBackground) { draft = draft.copy(codeBackground = it) }
                            StyleColor(R.string.md_style_quote_color, draft.quoteBackground) { draft = draft.copy(quoteBackground = it) }
                            StyleColor(R.string.md_style_border_color, draft.borderColor) { draft = draft.copy(borderColor = it) }
                            StyleSwitch(R.string.md_style_link_underline, draft.linkUnderline) { draft = draft.copy(linkUnderline = it) }
                        }
                        3 -> {
                            StyleSlider(R.string.md_style_code_size, draft.codeSize.toFloat(), 10f..24f, "sp") { draft = draft.copy(codeSize = it.roundToInt()) }
                            StyleSlider(R.string.md_style_code_padding, draft.codePadding.toFloat(), 4f..32f, "dp") { draft = draft.copy(codePadding = it.roundToInt()) }
                            StyleSlider(R.string.md_style_code_radius, draft.codeRadius.toFloat(), 0f..24f, "dp") { draft = draft.copy(codeRadius = it.roundToInt()) }
                            StyleSwitch(R.string.md_style_code_wrap, draft.codeWrap) { draft = draft.copy(codeWrap = it) }
                            HorizontalDivider()
                            StyleSlider(R.string.md_style_quote_border, draft.quoteBorder.toFloat(), 0f..8f, "dp") { draft = draft.copy(quoteBorder = it.roundToInt()) }
                        }
                        4 -> {
                            StyleSlider(R.string.md_style_table_padding, draft.tablePadding.toFloat(), 4f..20f, "dp") { draft = draft.copy(tablePadding = it.roundToInt()) }
                            StyleSwitch(R.string.md_style_table_borders, draft.tableBorders) { draft = draft.copy(tableBorders = it) }
                            StyleSwitch(R.string.md_style_table_stripes, draft.tableStripes) { draft = draft.copy(tableStripes = it) }
                            HorizontalDivider()
                            StyleSlider(R.string.md_style_image_width, draft.imageWidth.toFloat(), 25f..100f, "%") { draft = draft.copy(imageWidth = it.roundToInt()) }
                            StyleSlider(R.string.md_style_image_radius, draft.imageRadius.toFloat(), 0f..32f, "dp") { draft = draft.copy(imageRadius = it.roundToInt()) }
                        }
                        5 -> {
                            StyleSlider(R.string.md_style_math_scale, draft.mathScale.toFloat(), 80f..180f, "%") { draft = draft.copy(mathScale = it.roundToInt()) }
                            StyleSwitch(R.string.md_style_diagram_fit, draft.diagramFit) { draft = draft.copy(diagramFit = it) }
                            Text(stringResource(R.string.md_style_science_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ close(false) }, enabled = !closing) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button({ close(true) }, enabled = !closing) { Text(stringResource(R.string.md_style_apply)) }
            }
        }
    }
}

@Composable
private fun StyleSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, fractional: Boolean = false, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text((if (fractional) "%.2f".format(value) else value.roundToInt().toString()) + " $unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun StyleSwitch(label: Int, value: Boolean, onChange: (Boolean) -> Unit) =
    SettingsSwitchRow(stringResource(label), value, onChange = onChange)

@Composable
private fun StyleColor(label: Int, value: String?, onChange: (String?) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    Box {
        ListItem(headlineContent = { Text(stringResource(label)) }, supportingContent = { Text(value ?: stringResource(R.string.md_style_auto)) },
            leadingContent = { Surface(Modifier.size(28.dp), shape = MaterialTheme.shapes.small, color = Rgba.fromHex(value)?.toComposeColor() ?: MaterialTheme.colorScheme.primary) {} },
            trailingContent = { if (value != null) TextButton({ onChange(null) }) { Text(stringResource(R.string.md_style_auto)) } }, modifier = Modifier.clickable { pick = true })
        if (pick) ColorPickerPopup(Rgba.fromHex(value), emptyList(), onDismiss = { pick = false }) { color ->
            onChange("#%02x%02x%02x".format(color.r, color.g, color.b))
        }
    }
}

@Composable
private fun MarkdownStyleSample(style: MarkdownStyle) {
    val colors = MarkdownPage.colors(style, LocalPalette.current)
    fun color(value: String): Color = if (value.startsWith('#')) Color(android.graphics.Color.parseColor(value)) else {
        val c = value.removePrefix("rgb(").removeSuffix(")").split(',').map { it.toInt() }
        Color(c[0], c[1], c[2])
    }
    val font = when (style.font) { "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; else -> FontFamily.SansSerif }
    Surface(shape = MaterialTheme.shapes.large, color = color(colors.paper), contentColor = color(colors.text), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.md_style_sample_title), fontSize = style.headingSizes[0].sp, fontFamily = font,
                fontWeight = if (style.headingBold) FontWeight.Bold else FontWeight.Medium,
                color = Rgba.fromHex(style.headingColor)?.toComposeColor() ?: color(colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.md_style_sample_body), fontSize = style.fontSize.sp, lineHeight = (style.fontSize * style.lineHeight).sp, fontFamily = font, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun resetMarkdownSection(s: MarkdownStyle, page: Int): MarkdownStyle {
    val d = MarkdownStyle.DEFAULT
    return when (page) {
        0 -> s.copy(font = d.font, fontSize = d.fontSize, lineHeight = d.lineHeight, paragraphGap = d.paragraphGap, contentWidth = d.contentWidth, padding = d.padding, justified = d.justified)
        1 -> s.copy(headingSizes = d.headingSizes, headingGap = d.headingGap, headingBold = d.headingBold, headingRules = d.headingRules)
        2 -> s.copy(theme = d.theme, foreground = null, background = null, headingColor = null, accent = null, codeBackground = null, quoteBackground = null, borderColor = null, linkUnderline = d.linkUnderline)
        3 -> s.copy(codeSize = d.codeSize, codeWrap = d.codeWrap, codeRadius = d.codeRadius, codePadding = d.codePadding, quoteBorder = d.quoteBorder)
        4 -> s.copy(tablePadding = d.tablePadding, tableBorders = d.tableBorders, tableStripes = d.tableStripes, imageWidth = d.imageWidth, imageRadius = d.imageRadius)
        5 -> s.copy(mathScale = d.mathScale, diagramFit = d.diagramFit)
        else -> d
    }
}
