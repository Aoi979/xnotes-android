package com.xnotes.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.*
import android.webkit.*
import com.xnotes.core.text.MarkdownReader
import com.xnotes.settings.MarkdownPdfStyle
import kotlin.math.roundToInt

/** Uses Android's supported HTML print path. A dedicated immutable page cannot race live edits.
 * The system owns destination selection (including Save as PDF) and the adapter lifecycle. */
object MarkdownPrint {
    fun start(context: Context, store: MarkdownStore, parent: Uri?, page: String, title: String,
              style: MarkdownPdfStyle, onReady: (Boolean) -> Unit) {
        val pdf = style.clamped()
        var wrapped: Context? = context
        while (wrapped is ContextWrapper && wrapped !is Activity) wrapped = wrapped.baseContext
        val activity = wrapped as? Activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) { onReady(false); return }
        val web = runCatching { WebView(activity) }.getOrNull()
        if (web == null) { onReady(false); return }
        var submitted = false
        var preparing = false
        var released = false
        val handler = Handler(Looper.getMainLooper())
        fun release() {
            if (released) return
            released = true
            web.stopLoading(); web.webViewClient = WebViewClient(); web.destroy()
        }
        val timeout = Runnable { if (!submitted && !released) { release(); onReady(false) } }
        fun failed() {
            if (released) return
            handler.removeCallbacks(timeout)
            release()
            if (!submitted) onReady(false)
        }
        try {
            MarkdownWeb.configure(web, page)
            web.webViewClient = object : MarkdownWebClient(activity, store, parent, page) {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (submitted || preparing || released) return
                    preparing = true
                    MarkdownWeb.afterDiagrams(web, page, { !released && !submitted }) {
                        try {
                            val manager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: error("No print service")
                            val delegate = web.createPrintDocumentAdapter(title)
                            val adapter = ReleasingPrintAdapter(delegate, activity.getString(com.xnotes.R.string.err_export_pdf), ::release)
                            val attributes = pdf.printAttributes(activity)
                            manager.print(title, adapter, attributes) ?: error("Print job unavailable")
                            submitted = true
                            handler.removeCallbacks(timeout)
                            onReady(true)
                        } catch (_: Exception) { failed() }
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failed()
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failed(); return true
                }
            }
            handler.postDelayed(timeout, 20_000)
            web.loadUrl(MarkdownReader.BASE)
        } catch (_: Exception) { failed() }
    }

    private fun MarkdownPdfStyle.printAttributes(context: Context): PrintAttributes {
        fun mils(mm: Double) = (mm * 1000 / 25.4).roundToInt()
        val media = when (paper) {
            "a5" -> PrintAttributes.MediaSize.ISO_A5
            "letter" -> PrintAttributes.MediaSize.NA_LETTER
            "legal" -> PrintAttributes.MediaSize.NA_LEGAL
            "custom" -> PrintAttributes.MediaSize("xnotes-md-custom", context.getString(com.xnotes.R.string.md_pdf_custom), mils(customWidthMm), mils(customHeightMm))
            else -> PrintAttributes.MediaSize.ISO_A4
        }.let { if (landscape) it.asLandscape() else it.asPortrait() }
        return PrintAttributes.Builder().setMediaSize(media)
            .setMinMargins(PrintAttributes.Margins(mils(leftMm), mils(topMm), mils(rightMm), mils(bottomMm)))
            .setColorMode(if (colorMode == "mono") PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR).build()
    }
}

/** Android owns the callbacks; this adapter owns the dedicated WebView until printing finishes. */
private class ReleasingPrintAdapter(
    private val delegate: PrintDocumentAdapter,
    private val errorMessage: String,
    private val release: () -> Unit,
) : PrintDocumentAdapter() {
    override fun onStart() { runCatching { delegate.onStart() } }

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        try { delegate.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras) }
        catch (_: Exception) {
            if (cancellationSignal.isCanceled) callback.onLayoutCancelled() else callback.onLayoutFailed(errorMessage)
            release()
        }
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        try { delegate.onWrite(pages, destination, cancellationSignal, callback) }
        catch (_: Exception) {
            if (cancellationSignal.isCanceled) callback.onWriteCancelled() else callback.onWriteFailed(errorMessage)
            release()
        }
    }

    override fun onFinish() {
        try { delegate.onFinish() } catch (_: Exception) { } finally { release() }
    }
}
