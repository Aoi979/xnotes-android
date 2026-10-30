package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.platform.MarkdownPage
import com.xnotes.settings.MarkdownPdfStyle
import com.xnotes.settings.MarkdownStyle
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The same preflight for a live editor and a file in the library. Changes stay local until confirmed. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MarkdownPdfSheet(initial: MarkdownPdfStyle, reading: MarkdownStyle, onDismiss: () -> Unit,
                              onConfirm: (MarkdownPdfStyle, Boolean) -> Unit, allowExport: Boolean = true) {
    var draft by remember { mutableStateOf(initial.clamped()) }
    var section by remember { mutableIntStateOf(-1) }
    var heading by remember { mutableIntStateOf(0) }
    var reset by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var linkEdges by remember { mutableStateOf(initial.let { it.topMm == it.leftMm && it.topMm == it.bottomMm && it.topMm == it.rightMm }) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val names = listOf(R.string.md_pdf_page, R.string.md_pdf_body, R.string.md_pdf_headings, R.string.md_pdf_code_table,
        R.string.md_pdf_blocks, R.string.md_pdf_paging)
    val icons = listOf(Icons.Outlined.Description, Icons.Outlined.TextFields, Icons.Outlined.Title,
        Icons.Outlined.TableChart, Icons.Outlined.Image, Icons.Outlined.AutoStories)
    fun update(value: MarkdownPdfStyle) {
        draft = value.clamped()
        if (linkEdges && (draft.topMm != draft.leftMm || draft.topMm != draft.rightMm || draft.topMm != draft.bottomMm)) linkEdges = false
    }
    fun close(export: Boolean? = null) {
        if (closing) return
        closing = true
        val confirmed = draft.clamped()
        scope.launch { state.hide(); onDismiss(); if (export != null) onConfirm(confirmed, export) }
    }
    ModalBottomSheet(sheetState = state, onDismissRequest = { close() }) {
        BackHandler(enabled = section >= 0) { section = -1 }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (section >= 0) MdIcon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.md_style_back)) { section = -1 }
                Text(stringResource(if (section < 0) R.string.md_pdf_title else names[section]), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                Box {
                    MdIcon(Icons.Outlined.Restore, stringResource(R.string.reset)) { reset = true }
                    DropdownMenu(reset, { reset = false }) {
                        if (section >= 0) DropdownMenuItem(text = { Text(stringResource(R.string.md_style_reset_section)) }, onClick = {
                            update(resetPdfSection(draft, section)); reset = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.md_pdf_restore)) }, onClick = { draft = MarkdownPdfStyle.DEFAULT; linkEdges = true; reset = false })
                    }
                }
                MdIcon(XnotesIcons.close, stringResource(R.string.cancel)) { close() }
            }
            AnimatedContent(section, Modifier.weight(1f).fillMaxWidth(), transitionSpec = { materialFadeThrough(smallSurface = true).using(null) }, label = "PDF settings section") { page ->
                if (page < 0) LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { PdfPageGuide(draft, reading) }
                    item { Text(stringResource(R.string.md_pdf_presets), style = MaterialTheme.typography.titleMedium) }
                    item {
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MarkdownPdfStyle.PRESETS.entries.toList().chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((id, preset) in row) {
                                        val selected = draft.presetId() == id
                                        val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            XnotesMotion.defaultEffectsSpec(), label = "PDF preset color")
                                        Surface(onClick = { draft = preset; linkEdges = true }, modifier = Modifier.weight(1f).semantics { this.selected = selected; role = Role.RadioButton }, shape = MaterialTheme.shapes.large, color = color) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(stringResource(pdfPresetName(id)), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                                    if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                                                }
                                                Text("${pdfNumber(preset.bodyPt)} pt · ${pdfNumber(preset.lineHeight)} ×", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Text(stringResource(if (allowExport) R.string.md_pdf_independent else R.string.md_pdf_defaults_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(names) { index, title ->
                        Surface(onClick = { section = index }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            ListItem(headlineContent = { Text(stringResource(title)) }, leadingContent = { Icon(icons[index], null) },
                                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        }
                    }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        0 -> {
                            PdfChoice(R.string.md_pdf_paper, listOf("a4" to "A4", "a5" to "A5", "letter" to "Letter", "legal" to "Legal", "custom" to stringResource(R.string.md_pdf_custom)), draft.paper) { update(draft.copy(paper = it)) }
                            ExpressiveChoiceGroup(listOf(false to stringResource(R.string.md_pdf_portrait), true to stringResource(R.string.md_style_landscape)), draft.landscape) { update(draft.copy(landscape = it)) }
                            if (draft.paper == "custom") {
                                PdfValue(R.string.md_pdf_short_edge, draft.customWidthMm, 80.0..500.0, "mm") { update(draft.copy(customWidthMm = it)) }
                                PdfValue(R.string.md_pdf_long_edge, draft.customHeightMm, 80.0..500.0, "mm") { update(draft.copy(customHeightMm = it)) }
                            }
                            HorizontalDivider()
                            PdfSwitch(R.string.md_pdf_link_edges, linkEdges) { linkEdges = it; if (it) update(draft.copy(rightMm = draft.topMm, bottomMm = draft.topMm, leftMm = draft.topMm)) }
                            if (linkEdges) PdfValue(R.string.md_style_print_margin, draft.topMm, 0.0..min(45.0, (min(draft.paperMm().first, draft.paperMm().second) - 24) / 2), "mm") { update(draft.copy(topMm = it, rightMm = it, bottomMm = it, leftMm = it)) }
                            else {
                                PdfValue(R.string.md_pdf_top, draft.topMm, 0.0..45.0, "mm") { update(draft.copy(topMm = it)) }
                                PdfValue(R.string.md_pdf_bottom, draft.bottomMm, 0.0..45.0, "mm") { update(draft.copy(bottomMm = it)) }
                                PdfValue(R.string.md_pdf_left, draft.leftMm, 0.0..45.0, "mm") { update(draft.copy(leftMm = it)) }
                                PdfValue(R.string.md_pdf_right, draft.rightMm, 0.0..45.0, "mm") { update(draft.copy(rightMm = it)) }
                            }
                        }
                        1 -> {
                            PdfFonts(draft.font) { update(draft.copy(font = it)) }
                            PdfValue(R.string.md_style_font_size, draft.bodyPt, 8.0..20.0, "pt") { update(draft.copy(bodyPt = it)) }
                            PdfValue(R.string.md_style_line_height, draft.lineHeight, 1.0..2.5, "×", .05) { update(draft.copy(lineHeight = it)) }
                            PdfValue(R.string.md_pdf_before, draft.paragraphBeforePt, 0.0..32.0, "pt") { update(draft.copy(paragraphBeforePt = it)) }
                            PdfValue(R.string.md_pdf_after, draft.paragraphAfterPt, 0.0..32.0, "pt") { update(draft.copy(paragraphAfterPt = it)) }
                            PdfValue(R.string.md_pdf_indent, draft.indentEm, 0.0..4.0, "em") { update(draft.copy(indentEm = it)) }
                            PdfSwitch(R.string.md_style_justify, draft.justified) { update(draft.copy(justified = it)) }
                            PdfSwitch(R.string.md_pdf_indent_heading, draft.indentAfterHeading) { update(draft.copy(indentAfterHeading = it)) }
                        }
                        2 -> {
                            ExpressiveChoiceGroup((0..5).map { it to "H${it + 1}" }, heading) { heading = it }
                            fun changed(values: List<Double>, value: Double) = values.mapIndexed { i, old -> if (i == heading) value else old }
                            PdfValue(R.string.md_style_heading_size, draft.headingPt[heading], 10.0..40.0, "pt") { update(draft.copy(headingPt = changed(draft.headingPt, it))) }
                            PdfValue(R.string.md_pdf_heading_before, draft.headingBeforePt[heading], 0.0..48.0, "pt") { update(draft.copy(headingBeforePt = changed(draft.headingBeforePt, it))) }
                            PdfValue(R.string.md_pdf_heading_after, draft.headingAfterPt[heading], 0.0..32.0, "pt") { update(draft.copy(headingAfterPt = changed(draft.headingAfterPt, it))) }
                            PdfValue(R.string.md_pdf_heading_line, draft.headingLineHeight, 1.0..2.0, "×", .05) { update(draft.copy(headingLineHeight = it)) }
                            PdfSwitch(R.string.md_style_heading_bold, draft.headingBold) { update(draft.copy(headingBold = it)) }
                            PdfSwitch(R.string.md_style_heading_rules, draft.headingRules) { update(draft.copy(headingRules = it)) }
                        }
                        3 -> {
                            PdfValue(R.string.md_style_code_size, draft.codePt, 7.0..16.0, "pt") { update(draft.copy(codePt = it)) }
                            PdfValue(R.string.md_pdf_code_line, draft.codeLineHeight, 1.0..2.2, "×", .05) { update(draft.copy(codeLineHeight = it)) }
                            PdfValue(R.string.md_style_code_padding, draft.codePaddingPt, 0.0..20.0, "pt") { update(draft.copy(codePaddingPt = it)) }
                            PdfSwitch(R.string.md_style_code_wrap, draft.codeWrap) { update(draft.copy(codeWrap = it)) }
                            HorizontalDivider()
                            PdfValue(R.string.md_pdf_table_size, draft.tablePt, 7.0..16.0, "pt") { update(draft.copy(tablePt = it)) }
                            PdfValue(R.string.md_style_table_padding, draft.tablePaddingPt, 1.0..16.0, "pt") { update(draft.copy(tablePaddingPt = it)) }
                            PdfSwitch(R.string.md_style_table_borders, draft.tableBorders) { update(draft.copy(tableBorders = it)) }
                            PdfSwitch(R.string.md_style_table_stripes, draft.tableStripes) { update(draft.copy(tableStripes = it)) }
                        }
                        4 -> {
                            PdfValue(R.string.md_pdf_block_gap, draft.blockGapPt, 0.0..32.0, "pt") { update(draft.copy(blockGapPt = it)) }
                            PdfValue(R.string.md_pdf_quote_padding, draft.quotePaddingPt, 0.0..20.0, "pt") { update(draft.copy(quotePaddingPt = it)) }
                            PdfValue(R.string.md_pdf_quote_rule, draft.quoteRulePt, 0.0..6.0, "pt") { update(draft.copy(quoteRulePt = it)) }
                            PdfValue(R.string.md_pdf_list_gap, draft.listGapPt, 0.0..16.0, "pt") { update(draft.copy(listGapPt = it)) }
                            PdfValue(R.string.md_pdf_list_indent, draft.listIndentEm, 1.0..4.0, "em", .1) { update(draft.copy(listIndentEm = it)) }
                            PdfValue(R.string.md_pdf_image_width, draft.imageWidth.toDouble(), 30.0..100.0, "%", 1.0) { update(draft.copy(imageWidth = it.roundToInt())) }
                            PdfValue(R.string.md_pdf_image_height, draft.imageHeight.toDouble(), 20.0..100.0, "%", 1.0) { update(draft.copy(imageHeight = it.roundToInt())) }
                            PdfValue(R.string.md_style_math_scale, draft.mathScale.toDouble(), 80.0..180.0, "%", 1.0) { update(draft.copy(mathScale = it.roundToInt())) }
                        }
                        5 -> {
                            PdfChoice(R.string.md_pdf_color, listOf("paper" to stringResource(R.string.md_pdf_color_paper), "mono" to stringResource(R.string.md_pdf_color_mono), "reading" to stringResource(R.string.md_pdf_color_reading)), draft.colorMode) { update(draft.copy(colorMode = it)) }
                            PdfChoice(R.string.md_pdf_heading_break, listOf("none" to stringResource(R.string.md_pdf_break_none), "h1" to "H1", "h2" to "H2"), draft.headingPageBreak) { update(draft.copy(headingPageBreak = it)) }
                            PdfSwitch(R.string.md_pdf_keep_heading, draft.keepHeading) { update(draft.copy(keepHeading = it)) }
                            PdfSwitch(R.string.md_pdf_keep_rows, draft.keepTableRows) { update(draft.copy(keepTableRows = it)) }
                            PdfSwitch(R.string.md_pdf_repeat_header, draft.repeatTableHeader) { update(draft.copy(repeatTableHeader = it)) }
                            PdfValue(R.string.md_pdf_widows, draft.widowLines.toDouble(), 1.0..5.0, stringResource(R.string.md_pdf_lines), 1.0) { update(draft.copy(widowLines = it.roundToInt())) }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ if (allowExport) close(false) else close() }, enabled = !closing) { Text(stringResource(if (allowExport) R.string.md_pdf_save else R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button({ close(allowExport) }, enabled = !closing) { Text(stringResource(if (allowExport) R.string.export_pdf else R.string.pref_save_settings)) }
            }
        }
    }
}

private fun pdfPresetName(id: String?) = when (id) {
    "standard" -> R.string.md_pdf_standard; "compact" -> R.string.md_pdf_compact
    "comfortable" -> R.string.md_pdf_comfortable; "academic" -> R.string.md_pdf_academic; else -> R.string.md_pdf_custom
}
private fun pdfNumber(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
private fun PdfFonts(value: String, change: (String) -> Unit) = ExpressiveChoiceGroup(listOf("sans-serif" to stringResource(R.string.md_style_sans), "serif" to stringResource(R.string.md_style_serif), "monospace" to stringResource(R.string.md_style_mono)), value, onSelect = change)

@Composable
private fun PdfChoice(label: Int, options: List<Pair<String, String>>, value: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton({ expanded = true }) { Text(options.firstOrNull { it.first == value }?.second.orEmpty()); Icon(Icons.Outlined.ArrowDropDown, null) }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEachIndexed { i, (id, title) -> ChoiceMenuItem(selected = id == value, shapeIndex = i, shapeCount = options.size,
                    onClick = { expanded = false; change(id) }, text = { color -> Text(title, color = color) }) }
            }
        }
    }
}

@Composable
private fun PdfSwitch(label: Int, value: Boolean, change: (Boolean) -> Unit) =
    SettingsSwitchRow(stringResource(label), value, onChange = change)

@Composable
private fun PdfValue(label: Int, value: Double, range: ClosedFloatingPointRange<Double>, unit: String, step: Double = .5, change: (Double) -> Unit) {
    var precise by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton({ precise = true }) { Text("${pdfNumber(value)} $unit") }
        }
        Slider(value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()), onValueChange = { change((it / step).roundToInt() * step) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat())
    }
    if (precise) {
        var entry by remember { mutableStateOf(TextFieldValue(pdfNumber(value), TextRange(0, pdfNumber(value).length))) }
        val focus = remember { FocusRequester() }
        val number = entry.text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in range && (step < 1 || it % step == 0.0) }
        AlertDialog(onDismissRequest = { precise = false }, title = { Text(stringResource(label)) }, text = {
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(entry, { entry = it }, modifier = Modifier.focusRequester(focus), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (number != null) { change(number); precise = false } }),
                suffix = { Text(unit) }, isError = number == null, supportingText = { Text("${pdfNumber(range.start)}–${pdfNumber(range.endInclusive)} $unit") })
        }, confirmButton = { TextButton({ number?.let(change); precise = false }, enabled = number != null) { Text(stringResource(R.string.md_style_apply)) } },
            dismissButton = { TextButton({ precise = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun PdfPageGuide(style: MarkdownPdfStyle, reading: MarkdownStyle) {
    val p = style.clamped()
    val colors = MarkdownPage.colors(p.readingStyle(reading), LocalPalette.current)
    fun color(value: String): Color = if (value.startsWith('#')) Color(android.graphics.Color.parseColor(value)) else {
        val rgb = value.removePrefix("rgb(").removeSuffix(")").split(',').map { it.toInt() }; Color(rgb[0], rgb[1], rgb[2])
    }
    val paper by animateColorAsState(color(colors.paper), XnotesMotion.defaultEffectsSpec(), label = "PDF paper color")
    val ink by animateColorAsState(color(colors.text), XnotesMotion.defaultEffectsSpec(), label = "PDF ink color")
    val mm = p.paperMm()
    val geometry = listOf(mm.first, mm.second, p.leftMm, p.topMm, p.rightMm, p.bottomMm, p.bodyPt, p.lineHeight, max(p.paragraphBeforePt, p.paragraphAfterPt))
        .mapIndexed { i, v -> animateFloatAsState(v.toFloat(), XnotesMotion.defaultSpatialSpec(), label = "PDF page geometry $i").value }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(pdfPresetName(p.presetId())), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("${pdfNumber(mm.first)} × ${pdfNumber(mm.second)} mm", style = MaterialTheme.typography.labelMedium)
            }
            Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                val scale = min(size.width / geometry[0], size.height / geometry[1]) * .95f
                val w = geometry[0] * scale; val h = geometry[1] * scale
                val origin = Offset((size.width - w) / 2, (size.height - h) / 2)
                drawRect(paper, origin, Size(w, h))
                val left = origin.x + geometry[2] * scale; val top = origin.y + geometry[3] * scale
                val width = (w - (geometry[2] + geometry[4]) * scale).coerceAtLeast(0f)
                val height = (h - (geometry[3] + geometry[5]) * scale).coerceAtLeast(0f)
                drawRect(ink.copy(alpha = .25f), Offset(left, top), Size(width, height), style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
                val point = scale * 25.4f / 72
                val line = geometry[6] * point
                var y = top + line * 1.5f
                drawLine(ink, Offset(left, y), Offset(left + width * .65f, y), line * .65f)
                y += line * 2.5f
                repeat(4) {
                    repeat(4) { row ->
                        if (y + line < top + height) drawLine(ink.copy(alpha = .5f), Offset(left, y), Offset(left + width * if (row == 3) .72f else 1f, y), line * .18f)
                        y += line * geometry[7]
                    }
                    y += geometry[8] * point
                }
            }
            Text(stringResource(R.string.md_pdf_guide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun resetPdfSection(p: MarkdownPdfStyle, page: Int): MarkdownPdfStyle {
    val d = MarkdownPdfStyle.DEFAULT
    return when (page) {
        0 -> p.copy(paper = d.paper, landscape = d.landscape, customWidthMm = d.customWidthMm, customHeightMm = d.customHeightMm, topMm = d.topMm, rightMm = d.rightMm, bottomMm = d.bottomMm, leftMm = d.leftMm)
        1 -> p.copy(font = d.font, bodyPt = d.bodyPt, lineHeight = d.lineHeight, paragraphBeforePt = d.paragraphBeforePt, paragraphAfterPt = d.paragraphAfterPt, indentEm = d.indentEm, justified = d.justified, indentAfterHeading = d.indentAfterHeading)
        2 -> p.copy(headingPt = d.headingPt, headingBeforePt = d.headingBeforePt, headingAfterPt = d.headingAfterPt, headingLineHeight = d.headingLineHeight, headingBold = d.headingBold, headingRules = d.headingRules)
        3 -> p.copy(codePt = d.codePt, codeLineHeight = d.codeLineHeight, codePaddingPt = d.codePaddingPt, codeWrap = d.codeWrap, tablePt = d.tablePt, tablePaddingPt = d.tablePaddingPt, tableBorders = d.tableBorders, tableStripes = d.tableStripes)
        4 -> p.copy(blockGapPt = d.blockGapPt, quotePaddingPt = d.quotePaddingPt, quoteRulePt = d.quoteRulePt, listGapPt = d.listGapPt, listIndentEm = d.listIndentEm, imageWidth = d.imageWidth, imageHeight = d.imageHeight, mathScale = d.mathScale)
        5 -> p.copy(colorMode = d.colorMode, headingPageBreak = d.headingPageBreak, keepHeading = d.keepHeading, keepTableRows = d.keepTableRows, repeatTableHeader = d.repeatTableHeader, widowLines = d.widowLines)
        else -> d
    }
}
