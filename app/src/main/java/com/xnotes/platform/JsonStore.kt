package com.xnotes.platform

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads and atomically writes a single UTF-8 JSON file (PAL §11). Failure
 * tolerant: a missing/corrupt file yields an empty object; a failed write returns
 * false and retains the previous file. No Android runtime is needed by the file operations.
 */
class JsonStore(file: File) {
    private val file = file.absoluteFile
    private val lock = locks.getOrPut(runCatching { file.canonicalPath }.getOrDefault(this.file.path)) { Any() }

    fun read(): JSONObject = synchronized(lock) {
        try {
            if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /** Commit the complete new JSON or retain the previous file. Callers choose how to report failure. */
    fun write(obj: JSONObject): Boolean = synchronized(lock) {
        var tmp: File? = null
        try {
            val parent = file.parentFile ?: return@synchronized false
            parent.mkdirs()
            tmp = File.createTempFile(".${file.name}.", ".tmp", parent)
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            // Same-directory atomic replacement; never delete a good file to retry a failed move.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        } finally {
            tmp?.let { runCatching { it.delete() } }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        /** The app's per-user settings file under the private config directory. */
        fun settings(context: Context): JsonStore =
            JsonStore(File(File(context.filesDir, "config"), "settings.json"))

        /** Per-note view state (zoom/scroll) for folder notes, under the private config directory. */
        fun viewStates(context: Context): JsonStore =
            JsonStore(File(File(context.filesDir, "config"), "view_states.json"))

        /** App-tracked creation times for explorer items, under the private config directory. */
        fun createdTimes(context: Context): JsonStore =
            JsonStore(File(File(context.filesDir, "config"), "created_times.json"))

        /** Where playback stood in each recording, under the private config directory. */
        fun audioPositions(context: Context): JsonStore =
            JsonStore(File(File(context.filesDir, "config"), "audio_positions.json"))

        /** Page counts and PDF flags the explorer read from notes, under the private config directory. */
        fun docMeta(context: Context): JsonStore =
            JsonStore(File(File(context.filesDir, "config"), "doc_meta.json"))
    }
}
