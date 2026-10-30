package com.xnotes.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.core.text.*
import com.xnotes.platform.MarkdownStore
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun MarkdownPane(editor: Editor, session: MarkdownSession, onHome: () -> Unit, onClose: (() -> Unit)?) {
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val pickedFiles = remember(context) { com.xnotes.platform.PickedFiles(context.contentResolver) }
    val palette = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val text = session.input.text.toString()
    val oversized = !MarkdownSource.editableText(text)
    var find by rememberSaveable { mutableStateOf(false) }
    var outline by remember { mutableStateOf(false) }
    val outlineState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var more by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    var tableRequest by remember(session) { mutableStateOf<MarkdownTableRequest?>(null) }
    var styleSettings by remember { mutableStateOf(false) }
    var pdfSettings by remember { mutableStateOf(false) }
    var preparingPdf by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var previewAnchor by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val sourceScroll = remember(session) { ScrollState(session.sourceScroll.coerceAtLeast(0)) }
    var sourceLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    LaunchedEffect(sourceScroll) { snapshotFlow { sourceScroll.value }.collect { session.sourceScroll = it } }
    fun revealSource(start: Int, end: Int = start) {
        session.select(start, end)
        scope.launch {
            withFrameNanos { }
            // A mode change may mount the source field on this frame. Wait for its layout,
            // including the current window width and font scale, before mapping source offsets.
            withFrameNanos { }
            sourceLayout?.takeIf { it.layoutInput.text.text == session.input.text.toString() }?.let { layout ->
                val line = layout.getLineForOffset(start.coerceIn(0, session.input.text.length))
                sourceScroll.animateScrollTo(layout.getLineTop(line).toInt(), XnotesMotion.defaultSpatialSpec())
            }
        }
    }
    val large = text.length > 64 * 1024
    var manualPreview by remember { mutableIntStateOf(0) }
    var parsedText by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<MarkdownPreview?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    fun showFind(value: Boolean) {
        find = value
        if (!value) focusManager.clearFocus()
    }
    fun showPreview(value: Boolean) {
        if (value) {
            focusManager.clearFocus()
            manualPreview++
        }
        session.preview = value
    }
    fun exportMarkdownPdf(pdf: com.xnotes.settings.MarkdownPdfStyle) {
        if (preparingPdf || oversized) return
        focusManager.clearFocus()
        val snapshot = session.input.text.toString()
        val renderer = editor.markdownRenderer(pagePalette = palette)
        val name = com.xnotes.core.util.DocumentKind.stripSuffix(session.name)
        val parent = session.resourceParent
        preparingPdf = true
        scope.launch {
            val page = try {
                withContext(Dispatchers.Default) {
                    renderer.printPage(snapshot, pdf)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (page == null) { preparingPdf = false; editor.say(resources.getString(R.string.err_export_pdf)); return@launch }
            com.xnotes.platform.MarkdownPrint.start(context, session.store, parent, page, name, pdf) { ok ->
                preparingPdf = false
                if (!ok) editor.say(resources.getString(R.string.err_export_pdf))
            }
        }
    }
    LaunchedEffect(if (large) "" else text, editor.prefsVersion, palette, manualPreview, large && session.preview) {
        if (oversized) return@LaunchedEffect
        if (large && !session.preview && manualPreview == 0) return@LaunchedEffect
        delay(220)
        previewFailed = false
        val renderer = editor.markdownRenderer(pagePalette = palette)
        val result = try { withContext(Dispatchers.Default) { renderer.render(text) } }
        catch (e: Throwable) { if (e is CancellationException) throw e; null }
        if (result == null) previewFailed = true else { parsedText = text; parsed = result }
    }
    val search by produceState(MdMatches("", "", false, emptyList()), text, query, matchCase) {
        value = withContext(Dispatchers.Default) { MdMatches(text, query, matchCase, MarkdownEdit.matches(text, query, matchCase)) }
    }
    val matches = search.ranges.takeIf { search.text == text && search.query == query && search.matchCase == matchCase }.orEmpty()
    val activeMatch = matches.indexOfFirst { it.first == session.input.selection.min && it.last + 1 == session.input.selection.max }
    fun moveMatch(forward: Boolean) {
        if (matches.isEmpty()) return
        val index = if (activeMatch >= 0) Math.floorMod(activeMatch + if (forward) 1 else -1, matches.size)
        else if (forward) matches.indexOfFirst { it.first >= session.input.selection.end }.let { if (it < 0) 0 else it }
        else matches.indexOfLast { it.last < session.input.selection.start }.let { if (it < 0) matches.lastIndex else it }
        val range = matches[index]
        session.preview = false
        revealSource(range.first, range.last + 1)
    }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            pickedFiles.retainGrant(uri)
            session.saveCopy(uri.toString(), pickedFiles.readName(uri) ?: session.name) { ok ->
                if (ok) scope.launch { session.resourceParent = withContext(Dispatchers.IO) { session.store.parent(uri.toString(), null) } }
            }
        }
    }
    val grantDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            pickedFiles.retainGrant(uri)
            session.resourceParent = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri))
        }
    }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val parent = session.resourceParent
        if (uri != null && parent != null) scope.launch {
            val path = withContext(Dispatchers.IO) { runCatching { session.store.insertImage(parent, uri) }.getOrNull() }
            if (path == null) editor.say(resources.getString(R.string.err_read_image))
            else if (editor.markdown === session) {
                val current = session.input.text.toString()
                val range = session.input.selection
                session.apply(MarkdownEdit(current.replaceRange(range.min, range.max, "![image]($path)"), range.min + 2, range.min + 7))
            }
        }
    }
    fun wrap(left: String, right: String = left) = session.apply(MarkdownEdit.wrap(
        session.input.text.toString(), session.input.selection.start, session.input.selection.end, left, right))
    fun editTable() {
        focusManager.clearFocus()
        scope.launch {
            withFrameNanos { }
            val source = session.input.text.toString()
            val range = session.input.selection
            val region = withContext(Dispatchers.Default) { MarkdownTable.at(source, range.min) }
            if (editor.markdown !== session || session.input.text.toString() != source) return@launch
            if (region == null && withContext(Dispatchers.Default) { MarkdownBlocks.isLiteral(source, range.min) }) {
                editor.say(resources.getString(R.string.md_block_unsupported)); return@launch
            }
            if (region != null && (range.max > region.end || region.table.rows.size > TableDefaults.MAX_ROWS || region.table.columns > TableDefaults.MAX_COLS) ||
                region == null && withContext(Dispatchers.Default) { MarkdownTable.contains(source, range.min) }) {
                editor.say(resources.getString(R.string.md_table_unsupported))
            } else tableRequest = MarkdownTableRequest(source, range.start, range.end, region)
        }
    }

    CompositionLocalProvider(LocalMarkdownStyle provides editor.preferences.markdownStyle) {
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.paper.toComposeColor())) {
        val dual = maxWidth >= 840.dp && !large && !oversized
        val previewOnly = session.preview && !dual
        val maxSearchHeight = (maxHeight / 2 - 16.dp).coerceAtLeast(120.dp)
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfaceContainer),
                title = { Text(com.xnotes.core.util.DocumentKind.stripSuffix(session.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = {
                    val status = when {
                        session.saving -> R.string.md_saving
                        session.problem != null -> if (session.recoveryReady) R.string.md_save_problem else R.string.md_recovery_failed
                        session.readOnly -> R.string.md_read_only
                        session.dirty -> R.string.md_modified
                        else -> R.string.md_saved
                    }
                    AnimatedContent(status, transitionSpec = { materialFadeThrough(smallSurface = true).using(null) }, label = "Markdown save status") { shown ->
                        Text(stringResource(shown), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (shown == R.string.md_save_problem || shown == R.string.md_recovery_failed) colors.error else colors.onSurfaceVariant)
                    }
                },
                navigationIcon = { MdIcon(XnotesIcons.home, stringResource(R.string.home), onClick = onHome) },
                actions = {
                    MdIcon(XnotesIcons.save, stringResource(R.string.save), !session.readOnly && !session.saving) { session.requestSave() }
                    Tooltip(stringResource(R.string.md_find_replace)) {
                        IconToggleButton(checked = find, onCheckedChange = ::showFind, enabled = !oversized,
                            shapes = IconButtonDefaults.toggleableShapes(), modifier = Modifier.size(48.dp)) {
                            Icon(XnotesIcons.search, stringResource(R.string.md_find_replace))
                        }
                    }
                    MdIcon(XnotesIcons.list, stringResource(R.string.md_outline), !oversized) { outline = true }
                    Box {
                        MdIcon(XnotesIcons.more, stringResource(R.string.more)) { more = true }
                        DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.md_style_title)) }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }, onClick = {
                            more = false; focusManager.clearFocus(); styleSettings = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_pdf)) }, leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) }, enabled = !oversized && !preparingPdf, onClick = {
                            more = false; focusManager.clearFocus(); pdfSettings = true
                        })
                        HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.md_save_copy)) }, onClick = { more = false; saveCopy.launch(session.name) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.md_reload)) }, onClick = { more = false; reload = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.md_image_directory)) }, onClick = { more = false; grantDirectory.launch(null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.md_import_notebook)) }, onClick = {
                                    more = false
                                    session.requestSave { ok ->
                                        if (ok) session.uri?.let { uri ->
                                            if (editor.browseRoot == null) editor.useInternalStorage()
                                            editor.requestImport(com.xnotes.core.util.DocumentKind.stripSuffix(session.name), uri, true)
                                            onHome()
                                        }
                                    }
                            })
                            if (onClose != null) DropdownMenuItem(text = { Text(stringResource(R.string.md_close)) }, onClick = { more = false; onClose() })
                        }
                    }
                },
            )
            if (!oversized) Row(Modifier.fillMaxWidth().background(colors.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(dual, modifier = Modifier.weight(1f),
                    transitionSpec = { materialFadeThrough(smallSurface = true).using(null) }, label = "Markdown pane headings") { twoPanes ->
                    if (twoPanes) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        MdPaneLabel(Icons.Outlined.Code, stringResource(R.string.md_source), Modifier.weight(1f))
                        MdPaneLabel(Icons.Outlined.Visibility, stringResource(R.string.md_preview), Modifier.weight(1f))
                    } else PrimaryTabRow(selectedTabIndex = if (session.preview) 1 else 0, containerColor = colors.surfaceContainer) {
                        Tab(selected = !session.preview, onClick = { showPreview(false) },
                            text = { MdPaneLabel(Icons.Outlined.Code, stringResource(R.string.md_source)) })
                        Tab(selected = session.preview, onClick = { showPreview(true) },
                            text = { MdPaneLabel(Icons.Outlined.Visibility, stringResource(R.string.md_preview)) })
                    }
                }
                if (large) MdIcon(Icons.Outlined.Refresh, stringResource(R.string.md_refresh_preview)) { manualPreview++ }
                AnimatedVisibility(session.preview || dual,
                    enter = expandHorizontally(XnotesMotion.fastSpatialSpec()) + fadeIn(XnotesMotion.fastEffectsSpec()),
                    exit = shrinkHorizontally(XnotesMotion.fastSpatialSpec()) + fadeOut(XnotesMotion.fastEffectsSpec())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MdIcon(Icons.Outlined.Tune, stringResource(R.string.md_style_title), !preparingPdf) { styleSettings = true }
                        if (preparingPdf) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
                        else MdIcon(Icons.Outlined.PictureAsPdf, stringResource(R.string.export_pdf), !oversized) { focusManager.clearFocus(); pdfSettings = true }
                    }
                }
            }
            val error = session.problem?.let { problem ->
                if (!session.recoveryReady) R.string.md_recovery_failed else when (problem) {
                    MarkdownStore.Problem.CONFLICT -> R.string.md_conflict
                    MarkdownStore.Problem.TOO_LARGE -> R.string.md_too_large
                    MarkdownStore.Problem.UTF8 -> R.string.md_invalid_utf8
                    MarkdownStore.Problem.READ -> R.string.md_read_failed
                    else -> R.string.md_write_failed
                }
            }
            val shownError = rememberExitContent(error)
            AnimatedVisibility(error != null, enter = materialExpandEnter(), exit = materialExpandExit()) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large,
                    color = colors.errorContainer, contentColor = colors.onErrorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        shownError?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val buttonColors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer)
                            TextButton({ session.requestSave() }, colors = buttonColors, enabled = error != null && session.problem != MarkdownStore.Problem.CONFLICT && !session.readOnly) { Text(stringResource(R.string.md_retry)) }
                            TextButton({ saveCopy.launch(session.name) }, colors = buttonColors) { Text(stringResource(R.string.md_save_copy)) }
                            if (shownError == R.string.md_conflict) TextButton({ reload = true }, colors = buttonColors) { Text(stringResource(R.string.md_reload)) }
                        }
                    }
                }
            }
            AnimatedVisibility(find && !oversized, enter = materialExpandEnter(), exit = materialExpandExit()) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh) {
                    val searchFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    Column(Modifier.heightIn(max = maxSearchHeight).verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(query, { query = it }, Modifier.weight(1f).focusRequester(searchFocus), singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { moveMatch(true) }),
                                label = { Text(stringResource(R.string.md_find)) })
                            MdIcon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.md_previous_match), matches.isNotEmpty()) { moveMatch(false) }
                            MdIcon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.md_next_match), matches.isNotEmpty()) { moveMatch(true) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.md_replace)) })
                            TextButton(enabled = activeMatch >= 0 && !session.readOnly, onClick = {
                                    matches.getOrNull(activeMatch)?.let { r -> session.apply(MarkdownEdit(text.replaceRange(r.first, r.last + 1, replacement), r.first, r.first + replacement.length)) }
                            }) { Text(stringResource(R.string.md_replace)) }
                            TextButton(enabled = matches.isNotEmpty() && !session.readOnly, onClick = {
                                    val edit = MarkdownEdit.replaceAll(text, matches, replacement, session.input.selection.start)
                                    if (edit != null) session.apply(edit) else editor.say(resources.getString(R.string.md_too_large))
                            }) { Text(stringResource(R.string.md_replace_all)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.toggleable(matchCase, role = Role.Checkbox, onValueChange = { matchCase = it }).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(matchCase, null)
                                Text(stringResource(R.string.md_match_case), style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(if (activeMatch >= 0) "${activeMatch + 1}/${matches.size}" else "${matches.size}", style = MaterialTheme.typography.labelMedium)
                            MdIcon(XnotesIcons.close, stringResource(R.string.md_close)) { showFind(false) }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (oversized) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.md_too_large), style = MaterialTheme.typography.bodyLarge)
                        TextButton({ saveCopy.launch(session.name) }) { Text(stringResource(R.string.md_save_copy)) }
                    }
                } else Row(Modifier.fillMaxSize()) {
                    // Only the presentation changes. Text, undo history and scroll live above this
                    // transition; an autosave or a new parse never becomes an animation target.
                    AnimatedContent(previewOnly, modifier = Modifier.weight(1f).fillMaxHeight(),
                        transitionSpec = { materialFadeThrough().using(null) }, label = "Markdown source and preview") { reading ->
                        if (!reading) {
                            val decorations = parsed?.highlights.orEmpty().takeIf { parsedText == text && !large }.orEmpty()
                            val transformation = remember(decorations, palette) { OutputTransformation {
                                    for (s in decorations) {
                                        if (s.end > length) continue
                                        val style = when (s.kind) {
                                            MarkdownHighlight.Kind.HEADING -> SpanStyle(color = palette.accent.toComposeColor(), fontWeight = FontWeight.Bold)
                                            MarkdownHighlight.Kind.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
                                            MarkdownHighlight.Kind.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
                                            MarkdownHighlight.Kind.CODE -> SpanStyle(color = palette.accent.toComposeColor())
                                            MarkdownHighlight.Kind.LINK -> SpanStyle(color = palette.accent.toComposeColor(), textDecoration = TextDecoration.Underline)
                                            MarkdownHighlight.Kind.MUTED -> SpanStyle(color = palette.textDim.toComposeColor())
                                            MarkdownHighlight.Kind.MARK -> SpanStyle(background = palette.selectionBackground.toComposeColor())
                                        }
                                        addStyle(style, s.start, s.end)
                                    }
                                } }
                            val inputRules = remember(session) { InputTransformation {
                                    val next = asCharSequence().toString()
                                    if (!MarkdownSource.editableText(next)) {
                                        revertAllChanges()
                                        editor.say(resources.getString(R.string.md_too_large))
                                    } else if (session.input.composition == null) {
                                        val old = originalText.toString()
                                        val at = originalSelection.start
                                        if (originalSelection.collapsed && next.length == old.length + 1 && next.getOrNull(at) == '\n' &&
                                            next.removeRange(at, at + 1) == old) {
                                            MarkdownEdit.enter(old, at)?.let { edit ->
                                                replace(0, length, edit.text); selection = androidx.compose.ui.text.TextRange(edit.start, edit.end)
                                            }
                                        }
                                    }
                                } }
                            BasicTextField(
                                state = session.input,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
                                    .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown || session.input.composition != null) false
                                    else when {
                                        event.isCtrlPressed && event.key == Key.S && event.isShiftPressed -> { saveCopy.launch(session.name); true }
                                        event.isCtrlPressed && event.key == Key.S -> { session.requestSave(); true }
                                        event.isCtrlPressed && event.key == Key.F -> { showFind(true); true }
                                        event.isCtrlPressed && event.key == Key.B -> { wrap("**"); true }
                                        event.isCtrlPressed && event.key == Key.I -> { wrap("*"); true }
                                        event.isCtrlPressed && event.key == Key.P -> { showPreview(!session.preview); true }
                                        event.isCtrlPressed && event.isAltPressed && event.nativeKeyEvent.keyCode in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_6 -> {
                                            val selection = session.input.selection
                                            MarkdownBlocks.heading(session.input.text.toString(), selection.start, selection.end,
                                                event.nativeKeyEvent.keyCode - android.view.KeyEvent.KEYCODE_0)?.let(session::apply)
                                            true
                                        }
                                        event.key == Key.Tab && !session.readOnly -> {
                                            val range = session.input.selection
                                            session.apply(MarkdownEdit.indent(session.input.text.toString(), range.start, range.end, event.isShiftPressed))
                                            true
                                        }
                                        else -> false
                                    }
                                },
                                readOnly = session.readOnly,
                                scrollState = sourceScroll,
                                onTextLayout = { getLayout -> sourceLayout = getLayout() },
                                inputTransformation = inputRules,
                                outputTransformation = transformation,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.text.toComposeColor(), fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(palette.accent.toComposeColor()),
                            )
                        } else MdPreview(session, parsed, previewFailed, previewAnchor)
                    }
                    if (dual) {
                        VerticalDivider(color = colors.outlineVariant)
                        Box(Modifier.weight(1f).fillMaxHeight()) { MdPreview(session, parsed, previewFailed, previewAnchor) }
                    }
                }
            }
            AnimatedVisibility(!previewOnly && !find && !session.readOnly && !oversized,
                enter = expandVertically(XnotesMotion.defaultSpatialSpec(), expandFrom = Alignment.Bottom) + fadeIn(XnotesMotion.fastEffectsSpec()),
                exit = shrinkVertically(XnotesMotion.fastSpatialSpec(), shrinkTowards = Alignment.Bottom) + fadeOut(XnotesMotion.fastEffectsSpec())) {
                MarkdownFormattingToolbar(session, onTable = ::editTable, onImage = {
                    if (session.resourceParent == null) grantDirectory.launch(null) else image.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif"))
                }, onUnsupported = { editor.say(resources.getString(R.string.md_block_unsupported)) })
            }
        }
    }
    }
    if (styleSettings) MarkdownStyleSheet(editor.preferences.markdownStyle, onDismiss = { styleSettings = false }, onApply = editor::setMarkdownStyle)
    if (pdfSettings) MarkdownPdfSheet(editor.preferences.markdownPdfStyle, editor.preferences.markdownStyle,
        onDismiss = { pdfSettings = false }, onConfirm = { pdf, export -> editor.setMarkdownPdfStyle(pdf); if (export) exportMarkdownPdf(pdf) })
    tableRequest?.let { request -> MarkdownTableSheet(request, onDismiss = { tableRequest = null }, onApply = { table ->
        if (session.readOnly || session.input.text.toString() != request.source) {
            editor.say(resources.getString(R.string.md_table_changed)); false
        } else if (request.region?.table == table) true else {
            val edit = if (request.region == null) MarkdownTable.insert(request.source, request.start, request.end, table)
                else request.region.replace(request.source, table)
            if (edit != null && MarkdownSource.editableText(edit.text)) {
                session.apply(edit); revealSource(edit.start, edit.end); true
            } else { editor.say(resources.getString(R.string.md_too_large)); false }
        }
    }) }
    if (outline) ModalBottomSheet(sheetState = outlineState, onDismissRequest = { outline = false }) {
        Text(stringResource(R.string.md_outline), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        if (parsed?.headings.isNullOrEmpty()) Text(stringResource(R.string.md_no_headings), Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 24.dp)) {
            items(parsed?.headings.orEmpty()) { heading ->
                TextButton(modifier = Modifier.fillMaxWidth().padding(start = ((heading.level - 1) * 12).dp, end = 12.dp), onClick = {
                    if (parsedText == session.input.text.toString()) {
                        revealSource(heading.offset)
                        previewAnchor = heading.anchor to android.os.SystemClock.uptimeMillis()
                    }
                    scope.launch { outlineState.hide(); outline = false }
                }) { Text(heading.title, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text(stringResource(R.string.md_reload)) },
        text = { Text(stringResource(R.string.md_reload_prompt)) },
        confirmButton = { TextButton({ reload = false; session.reload() }) { Text(stringResource(R.string.md_reload)) } },
        dismissButton = { TextButton({ reload = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun MdPaneLabel(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MdPreview(session: MarkdownSession, parsed: MarkdownPreview?, failed: Boolean, anchor: Pair<String, Long>?) {
    Box(Modifier.fillMaxSize()) {
        when {
            failed -> Text(stringResource(R.string.md_preview_failed), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            parsed == null -> LoadingIndicator(Modifier.align(Alignment.Center))
            else -> MarkdownReaderView(session, parsed.html, anchor, Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MdIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Tooltip(label) {
        IconButton(onClick, shapes = IconButtonDefaults.shapes(), enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, label)
        }
    }
}

/** Only bundled diagram scripts may run; the finished document is frozen with JavaScript off. */
@Composable
private fun MarkdownReaderView(session: MarkdownSession, body: String, anchor: Pair<String, Long>?, modifier: Modifier) {
    val palette = LocalPalette.current
    val fontZoom = (LocalConfiguration.current.fontScale * 100).roundToInt()
    val context = LocalContext.current
    val parent = session.resourceParent
    val style = LocalMarkdownStyle.current
    val page = remember(body, palette, style) { com.xnotes.platform.MarkdownPage.html(body, style, palette) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var shown by remember { mutableStateOf<String?>(null) }
    var readyPage by remember { mutableStateOf<String?>(null) }
    var shownParent by remember { mutableStateOf<Uri?>(null) }
    var pendingScroll by remember { mutableIntStateOf(session.previewScroll) }
    var appliedAnchor by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    if (unavailable) {
        Text(stringResource(R.string.md_preview_failed), modifier.padding(24.dp))
        return
    }
    AndroidView(modifier = modifier, factory = { ctx ->
        try {
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                settings.textZoom = fontZoom
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnScrollChangeListener { _, _, y, _, _ -> session.previewScroll = y }
                view = this
            }
        } catch (_: Exception) { unavailable = true; android.view.View(ctx) }
    }, update = { raw ->
        val web = raw as? WebView ?: return@AndroidView
        if (web.settings.textZoom != fontZoom) web.settings.textZoom = fontZoom
        web.webViewClient = object : com.xnotes.platform.MarkdownWebClient(context, session.store, parent, page) {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "xnotes-markdown.invalid" && uri.fragment != null) return false
                MarkdownParser.linkTarget(uri.toString())?.let { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
                return true
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (url?.let { Uri.parse(it).fragment } != null) return
                com.xnotes.platform.MarkdownWeb.afterDiagrams(v, page, { view === v && shown == page && shownParent == parent }) {
                    readyPage = page
                    if (anchor != null && appliedAnchor != anchor) {
                        appliedAnchor = anchor
                        v.loadUrl("${MarkdownReader.BASE}#${Uri.encode(anchor.first)}")
                    } else v.post { v.scrollTo(0, pendingScroll) }
                }
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) unavailable = true
            }
            override fun onRenderProcessGone(v: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                unavailable = true
                return true
            }
        }
        if (shown != page || shownParent != parent) {
            if (shown != null) pendingScroll = web.scrollY
            shown = page
            readyPage = null
            shownParent = parent
            web.stopLoading()
            com.xnotes.platform.MarkdownWeb.configure(web, page)
            web.settings.textZoom = fontZoom
            web.loadUrl(MarkdownReader.BASE)
        }
    }, onRelease = { raw ->
        (raw as? WebView)?.apply { session.previewScroll = scrollY; stopLoading(); webViewClient = WebViewClient(); destroy() }
        view = null
    })
    LaunchedEffect(anchor, readyPage) {
        if (readyPage != null && readyPage == shown && appliedAnchor != anchor)
            anchor?.let { appliedAnchor = it; view?.loadUrl("${MarkdownReader.BASE}#${Uri.encode(it.first)}") }
    }
}

private data class MdMatches(val text: String, val query: String, val matchCase: Boolean, val ranges: List<IntRange>)
