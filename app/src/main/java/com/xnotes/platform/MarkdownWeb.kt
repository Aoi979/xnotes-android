package com.xnotes.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import com.xnotes.core.text.MarkdownReader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** The same offline resource boundary is used by reading, thumbnails and printing. */
open class MarkdownWebClient(
    private val context: Context,
    private val store: MarkdownStore,
    private val parent: Uri?,
    private val page: String,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        val directory = parent
        if (request.isForMainFrame && url.scheme == "https" && url.host == "xnotes-markdown.invalid" && url.path == "/document/")
            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(page.toByteArray(Charsets.UTF_8)))
        if (!request.isForMainFrame && MarkdownPage.hasDiagrams(page) && url.scheme == "https" && url.host == "xnotes-markdown.invalid" &&
            url.path in setOf("/science/bootstrap.js", "/science/diagrams.js", "/science/mermaid.min.js")) {
            runCatching { context.assets.open("markdown-science/${url.lastPathSegment}") }.getOrNull()?.let {
                return WebResourceResponse("application/javascript", "UTF-8", it)
            }
        }
        if (url.scheme == "https" && url.host == "xnotes-markdown.invalid" && url.encodedPath?.startsWith("/images/") == true && directory != null) {
            val path = MarkdownReader.resourcePath(url.encodedPath!!.removePrefix("/images/"))
            if (path != null) runCatching {
                store.resource(directory, path)?.let { uri ->
                    val mime = context.contentResolver.getType(uri)
                    if (mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                        context.contentResolver.openInputStream(uri)?.let { return WebResourceResponse(mime, null, it) }
                    }
                }
            }
        }
        return WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
}

object MarkdownWeb {
    private val thumbnailLock = Mutex()
    fun configure(web: WebView, page: String = "") = with(web.settings) {
        javaScriptEnabled = MarkdownPage.hasDiagrams(page); allowFileAccess = false; allowContentAccess = false
        domStorageEnabled = false; blockNetworkLoads = true; textZoom = 100
    }

    /** Wait for shipped diagram scripts, then freeze the document. There is no JavaScript bridge.
     * A page token prevents a late callback from freezing the next edit's document. */
    fun afterDiagrams(web: WebView, page: String, isCurrent: () -> Boolean, ready: () -> Unit) {
        if (!MarkdownPage.hasDiagrams(page)) { if (isCurrent()) ready(); return }
        val id = page.substringAfter("data-xnotes-page=\"", "").substringBefore('"')
        val handler = Handler(Looper.getMainLooper())
        val deadline = android.os.SystemClock.uptimeMillis() + 9_000
        fun finish() { if (isCurrent()) { web.settings.javaScriptEnabled = false; ready() } }
        fun poll() {
            if (!isCurrent()) return
            web.evaluateJavascript("document.documentElement.getAttribute('data-xnotes-page') === '$id' && window.__xnotesScienceReady === true") { result ->
                if (!isCurrent()) return@evaluateJavascript
                if (result == "true") finish()
                else if (android.os.SystemClock.uptimeMillis() >= deadline) {
                    web.evaluateJavascript("if(document.documentElement.getAttribute('data-xnotes-page') === '$id' && window.__xnotesScienceFinish) window.__xnotesScienceFinish()") { finish() }
                } else handler.postDelayed({ poll() }, 80)
            }
        }
        poll()
    }

    /** One measured first-page render, attached behind Compose until Chromium has produced a frame.
     * The caller serializes work and owns the returned bitmap; scrolling cancellation tears down
     * the view on the main thread instead of leaving hidden browsers in the activity. */
    suspend fun thumbnail(context: Context, store: MarkdownStore, parent: Uri?, page: String, width: Int, height: Int): Bitmap? =
        thumbnailLock.withLock { withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine { continuation ->
                    var current: Context? = context
                    while (current is ContextWrapper && current !is Activity) current = current.baseContext
                    val activity = current as? Activity
                    val host = activity?.findViewById<ViewGroup>(android.R.id.content)
                    if (activity == null || activity.isFinishing || activity.isDestroyed || host == null) {
                        continuation.resume(null); return@suspendCancellableCoroutine
                    }
                    val web = runCatching { WebView(context) }.getOrNull()
                    if (web == null) { continuation.resume(null); return@suspendCancellableCoroutine }
                    var released = false
                    var waiting = false
                    fun release() {
                        if (released) return
                        released = true
                        (web.parent as? ViewGroup)?.removeView(web)
                        web.stopLoading(); web.webViewClient = WebViewClient(); web.destroy()
                    }
                    fun finish(bitmap: Bitmap?) {
                        release()
                        if (continuation.isActive) continuation.resume(bitmap) { _, value, _ -> value?.recycle() }
                        else bitmap?.recycle()
                    }
                    continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { release() } }
                    try {
                        configure(web, page)
                        web.alpha = 0f
                        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        web.isFocusable = false
                        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        val viewWidth = (760 * context.resources.displayMetrics.density).roundToInt().coerceIn(760, 3040)
                        val viewHeight = (viewWidth.toDouble() * height / width).roundToInt()
                        host.addView(web, 0, ViewGroup.LayoutParams(viewWidth, viewHeight))
                        web.measure(View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(viewHeight, View.MeasureSpec.EXACTLY))
                        web.layout(0, 0, viewWidth, viewHeight)
                        web.webViewClient = object : MarkdownWebClient(context, store, parent, page) {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (waiting || released || !continuation.isActive) return
                                waiting = true
                                afterDiagrams(web, page, { !released && continuation.isActive }) {
                                    web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) {
                                            if (released || !continuation.isActive) return
                                            val bitmap = runCatching {
                                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                                                    val canvas = Canvas(bitmap)
                                                    canvas.scale(width.toFloat() / viewWidth, height.toFloat() / viewHeight)
                                                    web.draw(canvas)
                                                }
                                            }.getOrNull()
                                            finish(bitmap)
                                        }
                                    })
                                }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) finish(null)
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                finish(null); return true
                            }
                        }
                        web.loadUrl(MarkdownReader.BASE)
                    } catch (_: Exception) { finish(null) }
                }
            }
        } }
}
