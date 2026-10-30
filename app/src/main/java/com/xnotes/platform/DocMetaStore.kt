package com.xnotes.platform

import com.xnotes.core.util.moveDocumentKeys
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the explorer shows about each note without opening it (its page count and whether it annotates a
 * PDF), keyed by document identity like [CreationTimeStore]. Each entry carries the file's modified time
 * when it was read, so a file changed since (by this app or a sync) is read again. Writes to disk are
 * batched: a folder of new notes fills in one tile at a time, and each would otherwise rewrite the file.
 */
class DocMetaStore(private val store: JsonStore) {

    data class Meta(val pages: Int, val pdf: Boolean, val modified: Long)

    private val metas: MutableMap<String, Meta> = load()
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "doc-meta").apply { isDaemon = true } }
    private var scheduled = false

    private fun load(): MutableMap<String, Meta> {
        val saved = store.read()
        return saved.keys().asSequence().mapNotNull { key ->
            saved.optJSONObject(key)?.let { entry ->
                key to Meta(
                    pages = entry.optInt("pages", 0),
                    pdf = entry.optBoolean("pdf", false),
                    modified = entry.optLong("modified", 0L),
                )
            }
        }.toMap(mutableMapOf())
    }

    /** The meta read from [key] while it was last modified at [modified], or null when it has changed since. */
    @Synchronized
    fun get(key: String, modified: Long): Meta? = metas[key]?.takeIf { it.modified == modified }

    /** The meta last read from [key], however old. */
    @Synchronized
    fun latest(key: String): Meta? = metas[key]

    @Synchronized
    fun put(key: String, meta: Meta) {
        if (metas[key] == meta) return
        metas[key] = meta
        writeSoon()
    }

    /** Carry entries across a rename or move, a folder's descendants included. */
    @Synchronized
    fun rekeyTree(from: String, to: String) {
        if (metas.moveDocumentKeys(from, to)) writeSoon()
    }

    @Synchronized
    fun removeMatching(predicate: (String) -> Boolean) {
        if (metas.keys.removeAll(predicate)) writeSoon()
    }

    @Synchronized
    fun clear() {
        metas.clear()
        writeSoon()
    }

    // Called with the lock held. A change after a write took its snapshot schedules the next write.
    private fun writeSoon() {
        if (scheduled) return
        scheduled = true
        writer.schedule(Runnable { store.write(takeSnapshot()) }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun takeSnapshot(): JSONObject {
        scheduled = false
        return JSONObject().apply {
            for ((key, meta) in metas) {
                put(key, JSONObject().apply {
                    put("pages", meta.pages)
                    put("pdf", meta.pdf)
                    put("modified", meta.modified)
                })
            }
        }
    }

    private companion object {
        const val WRITE_DELAY_MS = 1500L
    }
}
