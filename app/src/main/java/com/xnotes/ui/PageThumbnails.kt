package com.xnotes.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xnotes.core.model.Page

/** Page identity survives reordering. An invalidation also retires renders already in flight. */
internal class PageThumbnails {
    private val cache = object : LruCache<Page, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Page, value: ImageBitmap) = value.width * value.height * 4
    }
    private var version = -1
    private var revision = 0L
    @Volatile var awaitingMath = false

    data class Request(val version: Int, val revision: Long)

    @Synchronized fun request(contentVersion: Int): Request {
        if (version != contentVersion) {
            clear()
            version = contentVersion
        }
        return Request(version, revision)
    }

    @Synchronized fun get(page: Page, contentVersion: Int): ImageBitmap? {
        request(contentVersion)
        return cache.get(page)
    }

    @Synchronized fun publish(page: Page, bitmap: Bitmap, request: Request, active: Boolean): ImageBitmap? {
        if (!active || request.version != version || request.revision != revision) {
            bitmap.recycle()
            return null
        }
        // Once published, Compose owns the pixels; eviction must not recycle them.
        return bitmap.asImageBitmap().also { cache.put(page, it) }
    }

    @Synchronized fun clear() {
        revision++
        cache.evictAll()
    }

    @Synchronized fun removePdfPages(pages: List<Page>): Boolean {
        if (pages.none { it.pdfPage != null }) return false
        revision++
        pages.forEach { if (it.pdfPage != null) cache.remove(it) }
        // Notify even before the first bitmap lands: retired requests also need a replacement.
        return true
    }

    @Synchronized fun onMathReady(): Boolean {
        if (!awaitingMath) return false
        awaitingMath = false
        clear()
        return true
    }
}
