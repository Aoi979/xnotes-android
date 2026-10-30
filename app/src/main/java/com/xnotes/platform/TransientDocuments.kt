package com.xnotes.platform

import android.content.ContentResolver
import android.net.Uri
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import java.io.File

/** Reads a private, short-lived document and releases its extracts even if rendering fails. */
internal class TransientDocuments(
    private val resolver: ContentResolver,
    private val notes: DocumentCodec,
    private val canvases: CanvasCodec,
    private val pdfDir: File,
    private val imageDir: File,
) {
    fun <T> withNote(uri: String, block: (Document) -> T): T? {
        val doc = runCatching {
            resolver.openInputStream(Uri.parse(uri))?.use { notes.read(it, pdfDir, imageDir) }
        }.getOrNull() ?: return null
        return try {
            block(doc)
        } finally {
            doc.pdfFile?.delete()
            deleteTransientResources(doc)
        }
    }

    fun <T> withCanvas(uri: String, block: (InfiniteDocument) -> T): T? {
        val doc = runCatching {
            resolver.openInputStream(Uri.parse(uri))?.use { canvases.read(it, imageDir) }
        }.getOrNull() ?: return null
        return try {
            block(doc)
        } finally {
            deleteTransientResources(doc)
        }
    }
}

/** Only for transient or abandoned documents. The live session owns its own resources. */
internal fun deleteTransientResources(doc: Document) {
    doc.pages.forEach { deleteTransientItems(it.items) }
    doc.templatePackages.values.forEach { runCatching { it.delete() } }
}

internal fun deleteTransientResources(doc: InfiniteDocument) = deleteTransientItems(doc.items)

private fun deleteTransientItems(items: List<CanvasItem>) {
    for (item in items) {
        val file = when (item) {
            is ImageItem -> item.image.file
            is AudioItem -> item.audio.file
            else -> continue
        }
        runCatching { file.delete() }
    }
}
