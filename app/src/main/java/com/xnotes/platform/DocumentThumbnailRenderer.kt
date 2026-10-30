package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.xnotes.canvas.PdfPageFilter
import com.xnotes.core.infinite.InfiniteDocument

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageLayers
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.insets
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.text.FlowPainter
import com.xnotes.core.text.PageBox
import kotlin.math.roundToInt

/** CPU rendering for closed documents, with an appearance captured before queueing the work. */
internal class DocumentThumbnailRenderer(
    private val paper: Rgba,
    private val pageColorOverride: Rgba?,
    private val flowStyle: FlowRenderStyle,
) {
    private fun exportFootprint(doc: Document, page: Page): Rect {
        val i = page.insets(doc)
        return Rect(-i.left, -i.top, i.left + page.width + i.right, i.top + page.height + i.bottom)
    }

    fun noteSquare(doc: Document, sidePx: Int, filter: PdfPageFilter, rotation: Int = 0): Bitmap? {
        val page = doc.pages.firstOrNull() ?: return null
        val side = sidePx.coerceAtLeast(1)
        val cover = exportFootprint(doc, page)
        val turned = rotation == 90 || rotation == 270
        val shownW = if (turned) cover.h else cover.w
        val shownH = if (turned) cover.w else cover.h
        // An upright page shows its top across the whole tile.
        if (!turned && shownW <= shownH) return paintDocPage(doc, 0, side, side, side.toDouble() / cover.w, rotation, filter)
        // Otherwise the page as shown spans the tile's width: centred top to bottom when it's wider than tall.
        val whole = notePage(doc, 0, (side * shownH / shownW).roundToInt().coerceAtLeast(1), filter, rotation) ?: return null
        val tile = AndroidRasterSurface.create(side, side)
        tile.fill(thumbnailPaper(doc, page, filter))
        Canvas(tile.bitmap).drawBitmap(whole, 0f, if (shownW > shownH) (side - whole.height) / 2f else 0f, null)
        whole.recycle()
        return tile.bitmap
    }

    /** Page [index] whole, [heightPx] tall once turned by [rotation], at the page's own shape. */
    fun notePage(doc: Document, index: Int, heightPx: Int, filter: PdfPageFilter, rotation: Int = 0): Bitmap? {
        val page = doc.pages.getOrNull(index) ?: return null
        val cover = exportFootprint(doc, page)
        val turned = rotation == 90 || rotation == 270
        val scale = heightPx.coerceAtLeast(1) / (if (turned) cover.w else cover.h)
        val w = (cover.w * scale).roundToInt().coerceAtLeast(1)
        val h = (cover.h * scale).roundToInt().coerceAtLeast(1)
        return paintDocPage(doc, index, w, h, scale, rotation, filter)
    }

    private fun thumbnailPaper(doc: Document, page: Page, filter: PdfPageFilter): Rgba =
        (if (page.pdfPage != null) filter.paperColor else null)
            ?: page.resolvedPageColor(doc, pageColorOverride) ?: paper

    /** Page [index] painted from its top-left corner at [scale] onto a [w]×[h] surface, then turned by [rotation]. */
    private fun paintDocPage(
        doc: Document, index: Int, w: Int, h: Int, scale: Double, rotation: Int, filter: PdfPageFilter,
    ): Bitmap? {
        val page = doc.pages.getOrNull(index) ?: return null
        val cover = exportFootprint(doc, page)
        val surface = AndroidRasterSurface.create(w, h)
        // Resolve the paper colour against this note's own document/page style (not the open note's).
        surface.fill(thumbnailPaper(doc, page, filter))
        val r = surface.renderer()
        r.scale(scale, scale)
        r.translate(-cover.left, -cover.top)
        // A pixel per 300 px of tile, so the ruling survives the disk cache's half-size decode.
        TemplateLibrary.paint(r, doc, page, cover, cover, maxOf(1.0, maxOf(w, h) / 300.0) / scale)
        doc.pdfFile?.let { file ->
            runCatching {
                PdfSource.create(file)?.let { src ->
                    try {
                        page.pdfPage?.let { pi ->
                            val pw = (page.width * scale).toInt().coerceAtLeast(1)
                            val ph = (page.height * scale).toInt().coerceAtLeast(1)
                            PdfSource.withPriority(PdfPriority.THUMBNAIL) {
                                src.renderPage(pi, pw, ph, filter, page.markups)
                            }?.let { bg ->
                                try {
                                    r.drawRaster(bg, Rect(0.0, 0.0, page.width, page.height))
                                } finally {
                                    bg.recycle()
                                }
                            }
                        }
                    } finally {
                        src.close()
                    }
                }
            }
        }
        if (page.pdfPage == null) MarkupPainter.paint(r, page.markups, doc.dpi / 72.0)
        // A closed note's flow is laid out locally (the published snapshot serves the open note only),
        // and only as far as this page, so a tile never sets the formulas further on.
        PageLayers.paint(itemsSnapshot(page), {
            if (!doc.flow.isEmpty) {
                val firstPage = if (doc.pages.size > 1 && doc.pages[0].cover) 1 else 0
                val frame = flowStyle.layout(doc.flow).layout(
                    doc.flow, doc.pages.map { PageBox(it.width, it.height) }, doc.dpi,
                    throughPage = index, firstPage = firstPage,
                )
                FlowPainter.paintPage(r, frame, index, Rect(0.0, 0.0, page.width, page.height))
            }
        }) { it.paint(r) }
        val bmp = surface.bitmap
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                .also { if (it !== bmp) bmp.recycle() }
        } catch (error: Throwable) {
            bmp.recycle()
            throw error
        }
    }

    /** A canvas's content framed with a little margin in a [wPx]×[hPx] tile. */
    fun canvas(
        doc: InfiniteDocument,
        wPx: Int,
        hPx: Int,
    ): Bitmap? {
        val w = wPx.coerceAtLeast(1)
        val h = hPx.coerceAtLeast(1)
        val surface = AndroidRasterSurface.create(w, h)
        surface.fill(doc.background.paperColor ?: paper)
        val bounds = doc.contentBounds()
        if (bounds != null && bounds.w > 0.0 && bounds.h > 0.0) {
            val r = surface.renderer()
            val margin = minOf(w, h) * 0.06
            val scale = minOf((w - 2 * margin) / bounds.w, (h - 2 * margin) / bounds.h).coerceAtMost(1.0)
            // Centre the content in the tile, whichever way round it is.
            r.translate(
                margin + (w - 2 * margin - bounds.w * scale) / 2.0,
                margin + (h - 2 * margin - bounds.h * scale) / 2.0,
            )
            r.scale(scale, scale)
            r.translate(-bounds.left, -bounds.top)
            for (item in doc.items.toList()) item.paint(r)
        }
        return surface.bitmap
    }

    private fun itemsSnapshot(page: Page): List<CanvasItem> {
        repeat(8) {
            try {
                return ArrayList(page.items)
            } catch (_: java.util.ConcurrentModificationException) {
                // a main-thread edit landed mid-copy; retry
            }
        }
        return emptyList()
    }
}
