package com.xnotes.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract as Docs
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.xnotes.core.text.MarkdownSource
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64

/** All methods do IO. Markdown bytes and recovery snapshots bypass the notebook codecs. */
class MarkdownStore(private val context: Context, dir: File) {
    enum class Problem { READ, WRITE, UTF8, TOO_LARGE, CONFLICT }
    class Failure(val problem: Problem, cause: Throwable? = null) : IOException(problem.name, cause)
    data class Opened(val source: MarkdownSource, val digest: String, val writable: Boolean)
    data class Draft(val uri: String?, val name: String, val source: MarkdownSource, val baseline: String?,
                     val dirty: Boolean, val start: Int, val end: Int, val preview: Boolean, val resourceParent: String? = null,
                     val sourceScroll: Int = 0, val previewScroll: Int = 0)

    private val recovery = AtomicFile(File(dir, "markdown-session.json"))
    private val resourceRoots = AtomicFile(File(context.filesDir, "markdown-resource-roots.json"))
    private var generation = 0L

    @Synchronized fun activate(): Long = ++generation

    @Synchronized fun saveDraft(draft: Draft, owner: Long) {
        if (generation != owner) return
        val obj = JSONObject().put("version", 1).put("name", draft.name)
            .put("bytes", Base64.getEncoder().encodeToString(draft.source.bytes()))
            .put("dirty", draft.dirty).put("start", draft.start).put("end", draft.end).put("preview", draft.preview)
            .put("sourceScroll", draft.sourceScroll).put("previewScroll", draft.previewScroll)
        draft.uri?.let { obj.put("uri", it) }
        draft.baseline?.let { obj.put("baseline", it) }
        draft.resourceParent?.let { obj.put("resourceParent", it) }
        recovery.baseFile.parentFile?.mkdirs()
        val out = recovery.startWrite()
        try { out.write(obj.toString().toByteArray(Charsets.UTF_8)); recovery.finishWrite(out) }
        catch (e: Exception) { recovery.failWrite(out); throw Failure(Problem.WRITE, e) }
        if (draft.uri != null && draft.resourceParent != null) runCatching { rememberResourceRoot(draft.uri, draft.resourceParent) }
    }

    @Synchronized fun loadDraft(): Draft? = runCatching {
        val obj = JSONObject(recovery.openRead().use { String(readLimited(it, MarkdownSource.MAX_BYTES * 2), Charsets.UTF_8) })
        if (obj.getInt("version") != 1) return null
        Draft(obj.optString("uri").ifEmpty { null }, obj.getString("name"),
            MarkdownSource.decode(Base64.getDecoder().decode(obj.getString("bytes"))),
            obj.optString("baseline").ifEmpty { null }, obj.optBoolean("dirty"),
            obj.optInt("start"), obj.optInt("end"), obj.optBoolean("preview"), obj.optString("resourceParent").ifEmpty { null },
            obj.optInt("sourceScroll"), obj.optInt("previewScroll"))
    }.getOrNull()

    @Synchronized fun clear(owner: Long? = null) {
        if (owner != null && owner != generation) return
        generation++
        recovery.delete()
    }

    fun open(uri: String): Opened {
        val bytes = read(uri)
        val source = try { MarkdownSource.decode(bytes) }
        catch (e: java.nio.charset.CharacterCodingException) { throw Failure(Problem.UTF8, e) }
        if (!source.editable) throw Failure(Problem.TOO_LARGE)
        return Opened(source, MarkdownSource.digest(bytes), writable(Uri.parse(uri)))
    }

    fun read(uri: String): ByteArray = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { readLimited(it) } ?: throw Failure(Problem.READ)
    } catch (e: Failure) { throw e } catch (e: Exception) { throw Failure(Problem.READ, e) }

    /** A draft must already be durable before this method is called. External providers may not
     * offer atomic replacement; the private draft remains available if their write is interrupted. */
    fun write(uri: String, bytes: ByteArray, expectedDigest: String?): String = synchronized(writeLock) {
        if (bytes.size > MarkdownSource.MAX_BYTES) throw Failure(Problem.TOO_LARGE)
        if (expectedDigest != null && MarkdownSource.digest(read(uri)) != expectedDigest) throw Failure(Problem.CONFLICT)
        try {
            val u = Uri.parse(uri)
            val ownFile = internalFile(u)
            if (ownFile != null) {
                val file = AtomicFile(ownFile)
                val out = file.startWrite()
                try { out.write(bytes); file.finishWrite(out) }
                catch (e: Exception) { file.failWrite(out); throw e }
            } else {
                val out = context.contentResolver.openOutputStream(u, "wt") ?: throw Failure(Problem.WRITE)
                out.use { it.write(bytes); it.flush() }
            }
            val hash = MarkdownSource.digest(bytes)
            if (MarkdownSource.digest(read(uri)) != hash) throw Failure(Problem.WRITE)
            hash
        } catch (e: Failure) { throw e } catch (e: Exception) { throw Failure(Problem.WRITE, e) }
    }

    private fun internalFile(uri: Uri): File? {
        if (uri.authority != "${context.packageName}.documents") return null
        val id = Docs.getDocumentId(uri)
        if (!id.startsWith("root/")) throw Failure(Problem.WRITE)
        val root = AppStorageDocumentsProvider.rootDir(context).canonicalFile
        val file = File(root, id.removePrefix("root/")).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) throw Failure(Problem.WRITE)
        return file
    }

    private fun writable(uri: Uri): Boolean {
        if (uri.authority == "${context.packageName}.documents") return true
        val permission = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (permission != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            context.contentResolver.query(uri, arrayOf(Docs.Document.COLUMN_FLAGS), null, null, null)?.use {
                it.moveToFirst() && (it.getInt(0) and Docs.Document.FLAG_SUPPORTS_WRITE) != 0
            } ?: false
        }.getOrDefault(false)
    }

    /** A parent obtained through the provider, never by guessing an opaque document id. */
    fun parent(uri: String, tree: String?): Uri? = runCatching {
        rememberedResourceRoot(uri)?.let { return it }
        val u = Uri.parse(uri)
        val root = tree?.let(Uri::parse) ?: if (Docs.isTreeUri(u)) u else return null
        if (u.authority != root.authority) return null
        val anchored = Docs.buildDocumentUriUsingTree(root, Docs.getDocumentId(u))
        val path = Docs.findDocumentPath(context.contentResolver, anchored)?.path ?: return null
        val parent = path.getOrNull(path.size - 2) ?: return null
        Docs.buildDocumentUriUsingTree(root, parent)
    }.getOrNull()

    private fun resourceKey(uri: String): String = runCatching {
        val parsed = Uri.parse(uri)
        "${parsed.authority}|${Docs.getDocumentId(parsed)}"
    }.getOrDefault(uri)

    /** Persist only an explicitly selected/authorized root, so closed-file thumbnails and PDF
     * export keep resolving attachments after a different document or a restart. */
    private fun rememberResourceRoot(uri: String, root: String) = synchronized(resourceLock) {
        val json = runCatching { resourceRoots.openRead().bufferedReader().use { JSONObject(it.readText()) } }.getOrElse { JSONObject() }
        val key = resourceKey(uri)
        if (json.optString(key) == root) return@synchronized
        json.put(key, root)
        val out = resourceRoots.startWrite()
        try { out.write(json.toString().toByteArray(Charsets.UTF_8)); resourceRoots.finishWrite(out) }
        catch (e: Exception) { resourceRoots.failWrite(out); throw e }
    }

    private fun rememberedResourceRoot(uri: String): Uri? = synchronized(resourceLock) {
        runCatching {
            val json = resourceRoots.openRead().bufferedReader().use { JSONObject(it.readText()) }
            val root = json.optString(resourceKey(uri)).takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@runCatching null
            if (root.authority == "${context.packageName}.documents" ||
                context.checkUriPermission(root, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) root else null
        }.getOrNull()
    }

    fun resource(parent: Uri, segments: List<String>): Uri? = runCatching {
        var at = parent
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == ".." || '/' in segment || '\\' in segment) return null
            val children = Docs.buildChildDocumentsUriUsingTree(at, Docs.getDocumentId(at))
            val next = context.contentResolver.query(children,
                arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                var found: String? = null
                while (c.moveToNext()) if (c.getString(1) == segment) { found = c.getString(0); break }
                found
            } ?: return null
            at = Docs.buildDocumentUriUsingTree(at, next)
        }
        at
    }.getOrNull()

    fun insertImage(parent: Uri, source: Uri): String {
        val resolver = context.contentResolver
        val mime = resolver.getType(source)?.takeIf { it in setOf("image/png", "image/jpeg", "image/webp", "image/gif") }
            ?: throw Failure(Problem.READ)
        val ext = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
        val assets = resource(parent, listOf("attachments")) ?: Docs.createDocument(resolver, parent, Docs.Document.MIME_TYPE_DIR, "attachments")
            ?: throw Failure(Problem.WRITE)
        val name = "image-${java.util.UUID.randomUUID()}.$ext"
        val dest = Docs.createDocument(resolver, assets, mime, name) ?: throw Failure(Problem.WRITE)
        try {
            val bytes = resolver.openInputStream(source)?.use { readLimited(it, 24 * 1024 * 1024) } ?: throw Failure(Problem.READ)
            resolver.openOutputStream(dest, "wt")?.use { it.write(bytes) } ?: throw Failure(Problem.WRITE)
        } catch (e: Exception) { runCatching { Docs.deleteDocument(resolver, dest) }; throw e }
        return "attachments/$name"
    }

    companion object {
        private val writeLock = Any()
        private val resourceLock = Any()

        fun readLimited(input: InputStream, limit: Int = MarkdownSource.MAX_BYTES): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > limit) throw Failure(Problem.TOO_LARGE)
                out.write(buf, 0, n)
            }
        }
    }
}
