package com.xnotes.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xnotes.canvas.PdfPageFilter
import com.xnotes.core.model.Document
import com.xnotes.core.text.MarkdownSource
import com.xnotes.core.util.DocumentKind
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.platform.DocumentThumbnailRenderer
import com.xnotes.platform.MarkdownPage
import com.xnotes.platform.MarkdownRenderer
import com.xnotes.platform.MarkdownStore
import com.xnotes.platform.MarkdownWeb
import com.xnotes.platform.NoteThumbnailCache
import com.xnotes.platform.TransientDocuments
import com.xnotes.settings.MarkdownStyle
import com.xnotes.ui.theme.Palette
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Everything a queued render needs, captured before it leaves the caller's thread. */
internal data class ThumbnailStyle(
    val renderer: DocumentThumbnailRenderer,
    val filter: PdfPageFilter,
    val rotation: Int,
    val markdownStyle: MarkdownStyle,
    val palette: Palette,
    val markdownRenderer: MarkdownRenderer,
    val resourceParent: Uri?,
    val browseRoot: String?,
)

/** Owns the explorer's bounded caches and worker. Coroutines remain owned by their UI/save callers. */
internal class DocumentThumbnails(
    private val context: Context,
    private val documents: TransientDocuments,
    private val markdownStore: MarkdownStore,
    private val captureStyle: (String) -> ThumbnailStyle,
    private val rememberCreated: (String, Long) -> Unit,
) : AutoCloseable {
    private val disk = NoteThumbnailCache(File(context.applicationContext.filesDir, "note_thumbs"))
    private val memory = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "note-thumbs").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    @Volatile private var generation = -1L
    @Volatile private var epoch = 0L
    @Volatile private var sourceRevision = 0L
    @Volatile private var closed = false
    // Serialize writes with invalidation, without blocking synchronous memory-cache reads on PNG encoding.
    private val writes = Any()
    private var theme: String? = null
    var version by mutableStateOf(0)
        private set

    fun useTheme(signature: String): Boolean = synchronized(writes) {
        if (theme == signature) return@synchronized false
        theme = signature
        generation = disk.useTheme(signature)
        epoch++
        synchronized(memory) { memory.evictAll() }
        version++
        true
    }

    /** Shared with live page rendering, so a released pane cannot publish a late result. */
    fun generationGuard(): () -> Boolean {
        val expectedGeneration = generation
        val expectedEpoch = epoch
        return { !closed && expectedEpoch == epoch && expectedGeneration == generation && disk.isCurrent(expectedGeneration) }
    }

    private fun sourceGuard(): () -> Boolean {
        val current = generationGuard()
        val revision = sourceRevision
        return { revision == sourceRevision && current() }
    }

    fun cachedTile(uri: String): ImageBitmap? = cached(uri)
    fun cachedPage(uri: String): ImageBitmap? = cached(pageKey(uri))
    private fun cached(key: String): ImageBitmap? = synchronized(memory) { memory.get(key) }

    suspend fun tile(uri: String, name: String): ImageBitmap? = load(uri, DocumentKind.ofName(name), whole = false)
    suspend fun page(uri: String, name: String): ImageBitmap? = load(uri, DocumentKind.ofName(name), whole = true)
    suspend fun canvas(uri: String): ImageBitmap? = load(uri, DocumentKind.CANVAS, whole = false)
    suspend fun note(uri: String): ImageBitmap? = load(uri, DocumentKind.NOTE, whole = false)

    private suspend fun load(uri: String, kind: DocumentKind?, whole: Boolean): ImageBitmap? {
        if (closed) return null
        val current = sourceGuard()
        val expectedGeneration = generation
        val key = if (whole) pageKey(uri) else uri
        if (kind != DocumentKind.MARKDOWN) cached(key)?.let { return it }
        val style = captureStyle(uri)
        return withContext(worker) {
            delay(150) // A quick scroll-past can cancel before decoding or painting starts.
            if (!isActive || !current()) return@withContext null
            if (kind == DocumentKind.MARKDOWN) {
                return@withContext markdown(uri, whole, style, expectedGeneration) { isActive && current() }
            }
            cached(key)?.let { return@withContext it }
            val bitmap = disk.load(key, expectedGeneration) ?: run {
                val rendered = if (kind == DocumentKind.CANVAS) {
                    documents.withCanvas(uri) { doc ->
                        doc.created?.let { rememberCreated(uri, it) }
                        style.renderer.canvas(doc, if (whole) (SIZE * 1.414).roundToInt() else SIZE, SIZE)
                    }
                } else {
                    documents.withNote(uri) { doc ->
                        doc.created?.let { rememberCreated(uri, it) }
                        if (whole) style.renderer.notePage(doc, 0, SIZE, style.filter, style.rotation)
                        else style.renderer.noteSquare(doc, SIZE, style.filter, style.rotation)
                    }
                } ?: return@withContext null
                if (!store(key, rendered, expectedGeneration) { isActive && current() }) {
                    rendered.recycle()
                    return@withContext null
                }
                rendered
            }
            publish(key, bitmap) { isActive && current() }
        }
    }

    private suspend fun markdown(
        uri: String, whole: Boolean, style: ThumbnailStyle, generation: Long, active: () -> Boolean,
    ): ImageBitmap? {
        // Always read Markdown: the digest detects providers whose modification time never changes.
        val bytes = runCatching { markdownStore.read(uri) }.getOrNull() ?: return null
        val source = runCatching { MarkdownSource.decode(bytes).text }.getOrNull() ?: return null
        val parent = style.resourceParent ?: markdownStore.parent(uri, style.browseRoot)
            ?: markdownStore.parent(uri, AppStorageDocumentsProvider.treeUri(context).toString())
        val key = "$uri#markdown:science-1:${MarkdownSource.digest(bytes)}:$parent"
        val bitmap = disk.load(key, generation) ?: run {
            val body = style.markdownRenderer.render(source.take(16 * 1024)).html
            val html = MarkdownPage.html(body, style.markdownStyle, style.palette)
            MarkdownWeb.thumbnail(context, markdownStore, parent, html, SIZE, (SIZE * 1.414).roundToInt())
                ?.also { store(key, it, generation, active) }
        } ?: return null
        if (!active()) { bitmap.recycle(); return null }
        val result = if (whole) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, minOf(bitmap.width, bitmap.height))
        if (result !== bitmap) bitmap.recycle()
        return publish(if (whole) pageKey(uri) else uri, result, active)
    }

    suspend fun strip(uri: String, name: String, max: Int, heightPx: Int): List<ImageBitmap> {
        if (closed || DocumentKind.ofName(name) != DocumentKind.NOTE) return emptyList()
        val current = sourceGuard()
        val style = captureStyle(uri)
        return withContext(worker) {
            if (!isActive || !current()) return@withContext emptyList()
            documents.withNote(uri) { doc ->
                (0 until minOf(max, doc.pages.size)).mapNotNull { i ->
                    if (!isActive || !current()) return@withNote emptyList()
                    val bitmap = runCatching { style.renderer.notePage(doc, i, heightPx, style.filter, style.rotation) }.getOrNull()
                        ?: return@mapNotNull null
                    if (!isActive || !current()) { bitmap.recycle(); return@withNote emptyList() }
                    bitmap.asImageBitmap()
                }.takeIf { isActive && current() } ?: emptyList()
            } ?: emptyList()
        }
    }

    /** The live document is borrowed for this operation; its extracts belong to the session. */
    suspend fun regenerate(uri: String, doc: Document, style: ThumbnailStyle, stillCurrent: () -> Boolean) {
        if (closed) return
        val current = sourceGuard()
        val expectedGeneration = generation
        withContext(worker) {
            if (!isActive || !current() || !stillCurrent()) return@withContext
            val bitmap = runCatching { style.renderer.noteSquare(doc, SIZE, style.filter, style.rotation) }.getOrNull()
                ?: return@withContext
            if (!isActive || !current() || !stillCurrent()) { bitmap.recycle(); return@withContext }
            if (!store(uri, bitmap, expectedGeneration) { isActive && current() && stillCurrent() }) {
                bitmap.recycle()
                return@withContext
            }
            synchronized(memory) { memory.remove(pageKey(uri)) }
            disk.remove(pageKey(uri))
            publish(uri, bitmap) { isActive && current() && stillCurrent() }
        }
    }

    private fun publish(key: String, bitmap: Bitmap, active: () -> Boolean): ImageBitmap? = synchronized(memory) {
        if (!active()) { bitmap.recycle(); null }
        else bitmap.asImageBitmap().also { memory.put(key, it) }
    }

    private fun store(key: String, bitmap: Bitmap, generation: Long, active: () -> Boolean): Boolean = synchronized(writes) {
        if (!active()) return@synchronized false
        disk.store(key, bitmap, generation)
        true
    }

    fun invalidate(uri: String) {
        synchronized(writes) {
            sourceRevision++
            synchronized(memory) { memory.remove(uri); memory.remove(pageKey(uri)) }
            disk.remove(uri)
            disk.remove(pageKey(uri))
            // Restart retired requests; other documents retain their cached pixels.
            version++
        }
    }

    fun clearFolder() {
        synchronized(writes) {
            epoch++
            synchronized(memory) { memory.evictAll() }
            disk.prune(emptySet())
            version++
        }
    }

    override fun close() {
        synchronized(writes) {
            closed = true
            synchronized(memory) { memory.evictAll() }
        }
        worker.close()
    }

    private fun pageKey(uri: String): String = "$uri#page"
    private companion object { const val SIZE = 600 }
}
