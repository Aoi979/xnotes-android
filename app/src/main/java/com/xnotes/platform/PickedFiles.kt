package com.xnotes.platform

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** System-picker IO. Results describe completed work; null provider streams are failures. */
internal class PickedFiles(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class NamedBytes(val bytes: ByteArray, val name: String?)

    suspend fun readName(uri: Uri): String? = withContext(io) { displayName(uri) }

    suspend fun read(uri: Uri): Result<ByteArray> = perform { readBytes(uri) }

    suspend fun readNamed(uri: Uri): Result<NamedBytes> = perform {
        NamedBytes(readBytes(uri), displayName(uri))
    }

    suspend fun copy(source: Uri, destination: Uri): Result<Unit> = perform {
        input(source).use { input -> output(destination).use { transfer(input, it) } }
    }

    suspend fun write(destination: Uri, bytes: ByteArray): Result<Unit> = perform {
        output(destination).use { it.write(bytes) }
    }

    suspend fun writeInTree(tree: Uri, name: String, mime: String, bytes: ByteArray): Result<Unit> = perform {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val destination = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("No destination document")
        var written = false
        try {
            output(destination).use { it.write(bytes) }
            ensureActive()
            written = true
        } finally {
            if (!written) runCatching { DocumentsContract.deleteDocument(resolver, destination) }
        }
    }

    /** The generated export belongs to this operation until saved, failed or cancelled. */
    suspend fun saveTemporary(source: File, destination: Uri?): Result<Unit>? = try {
        destination?.let { uri ->
            perform {
                source.inputStream().use { input -> output(uri).use { transfer(input, it) } }
            }
        }
    } finally {
        withContext(NonCancellable + io) { source.delete() }
    }

    suspend fun retainGrant(uri: Uri) {
        withContext(io) {
            // Providers without persistent grants still allow the current picker operation.
            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    private fun input(uri: Uri): InputStream = resolver.openInputStream(uri) ?: throw IOException("No input stream")
    private fun output(uri: Uri): OutputStream = resolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun CoroutineScope.readBytes(uri: Uri): ByteArray = input(uri).use { source ->
        ByteArrayOutputStream().use { destination ->
            transfer(source, destination)
            destination.toByteArray()
        }
    }

    private fun CoroutineScope.transfer(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        ensureActive()
    }

    private suspend fun <T> perform(block: suspend CoroutineScope.() -> T): Result<T> = try {
        Result.success(withContext(io, block))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    } catch (error: OutOfMemoryError) {
        Result.failure(error)
    }
}
