package com.xnotes.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import com.xnotes.core.text.MarkdownEdit
import com.xnotes.core.text.MarkdownSource
import com.xnotes.platform.MarkdownStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A pane's source document. The live field is authoritative; all IO uses immutable snapshots. */
class MarkdownSession(
    val store: MarkdownStore,
    initial: MarkdownStore.Draft,
    writable: Boolean,
    initialProblem: MarkdownStore.Problem? = null,
    private val onChanged: () -> Unit = {},
) {
    val input = TextFieldState(initial.source.text,
        TextRange(initial.start.coerceIn(0, initial.source.text.length), initial.end.coerceIn(0, initial.source.text.length)))
    var uri by mutableStateOf(initial.uri)
        private set
    var name by mutableStateOf(initial.name)
        private set
    var readOnly by mutableStateOf(!writable)
        private set
    var preview by mutableStateOf(initial.preview)
    var sourceScroll: Int = initial.sourceScroll
    var previewScroll: Int = initial.previewScroll
    var recoveryReady by mutableStateOf(initialProblem != null)
        private set
    var dirty by mutableStateOf(initial.dirty)
        private set
    var saving by mutableStateOf(false)
        private set
    var problem by mutableStateOf(initialProblem)
        private set
    var resourceParent by mutableStateOf(initial.resourceParent?.let(android.net.Uri::parse))
    private var source = initial.source
    private var baseline = initial.baseline
    private var savedText: String? = if (initial.dirty) null else source.text
    private var savedSource: MarkdownSource? = if (initial.dirty) null else source
    private val owner = store.activate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioLock = Mutex()
    private var timer: Job? = null
    private var checkpoint: Job? = null
    private var disposed = false
    private val watcher = scope.launch {
        snapshotFlow { input.text.toString() }.collect { text ->
            if (text != source.text) {
                capture()
                schedule()
                onChanged()
            }
        }
    }

    fun snapshot(): MarkdownStore.Draft {
        capture()
        return MarkdownStore.Draft(uri, name, source, baseline, dirty, input.selection.start, input.selection.end, preview,
            resourceParent?.toString(), sourceScroll, previewScroll)
    }

    private fun capture() {
        val value = input.text.toString()
        val next = savedSource?.takeIf { it.text == value } ?: source.edited(value)
        if (next != source) recoveryReady = false
        source = next
        dirty = source.text != savedText
    }

    private fun schedule() {
        timer?.cancel()
        timer = scope.launch { delay(750); requestSave() }
        if (checkpoint?.isActive != true) checkpoint = scope.launch { delay(3_000); requestSave() }
    }

    fun requestSave(onDone: (Boolean) -> Unit = {}) {
        timer?.cancel(); checkpoint?.cancel()
        scope.launch { onDone(saveNow()) }
    }

    /** The mutex spans draft, source write and adoption. A late write never cleans newer input. */
    suspend fun saveNow(destination: String? = uri, displayName: String = name): Boolean = ioLock.withLock {
        val draft = snapshot()
        val changingFile = destination != uri
        saving = true
        try {
            withContext(Dispatchers.IO) { store.saveDraft(draft, owner) }
            recoveryReady = input.text.toString() == draft.source.text
            if (destination == null || (!changingFile && (readOnly || problem == MarkdownStore.Problem.CONFLICT))) return@withLock !dirty
            if (!dirty && !changingFile) return@withLock true
            val hash = withContext(Dispatchers.IO) {
                store.write(destination, draft.source.bytes(), if (changingFile) null else baseline)
            }
            uri = destination
            name = displayName
            readOnly = false
            baseline = hash
            savedText = draft.source.text
            savedSource = draft.source
            problem = null
            capture()
            val committed = snapshot()
            withContext(Dispatchers.IO) { store.saveDraft(committed, owner) }
            recoveryReady = input.text.toString() == committed.source.text
            return@withLock !dirty
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            problem = (e as? MarkdownStore.Failure)?.problem ?: MarkdownStore.Problem.WRITE
            return@withLock false
        } finally {
            saving = false
            onChanged()
        }
    }

    fun saveCopy(destination: String, displayName: String, onDone: (Boolean) -> Unit = {}) {
        timer?.cancel(); checkpoint?.cancel()
        scope.launch { onDone(saveNow(destination, displayName)) }
    }

    fun apply(edit: MarkdownEdit) {
        if (readOnly || input.composition != null) return
        if (!MarkdownSource.editableText(edit.text)) { problem = MarkdownStore.Problem.TOO_LARGE; return }
        input.edit {
            replace(0, length, edit.text)
            selection = TextRange(edit.start.coerceIn(0, length), edit.end.coerceIn(0, length))
        }
        capture(); schedule(); onChanged()
    }

    fun select(start: Int, end: Int = start) {
        input.edit { selection = TextRange(start.coerceIn(0, length), end.coerceIn(0, length)) }
    }

    fun reload(onDone: (Boolean) -> Unit = {}) {
        val from = uri ?: return
        scope.launch {
            ioLock.withLock {
                try {
                    val disk = withContext(Dispatchers.IO) { store.open(from) }
                    source = disk.source; savedSource = source; savedText = source.text; baseline = disk.digest
                    input.edit { replace(0, length, source.text); selection = TextRange(0) }
                    input.undoState.clearHistory()
                    readOnly = !disk.writable; problem = null; dirty = false
                    val restored = snapshot()
                    withContext(Dispatchers.IO) { store.saveDraft(restored, owner) }
                    recoveryReady = true
                    onChanged(); onDone(true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    problem = (e as? MarkdownStore.Failure)?.problem ?: MarkdownStore.Problem.READ
                    onChanged(); onDone(false)
                }
            }
        }
    }

    /** Stop observation, allowing a write already in progress to finish on its immutable bytes. */
    fun dispose(clearDraft: Boolean = false) {
        if (disposed) return
        disposed = true
        watcher.cancel(); timer?.cancel(); checkpoint?.cancel()
        if (clearDraft) scope.launch { ioLock.withLock { withContext(Dispatchers.IO) { store.clear(owner) } } }
    }
}
