package com.xnotes.platform

import android.graphics.Bitmap
import android.util.Base64
import com.xnotes.core.model.Rgba
import com.xnotes.core.text.MarkdownFormula
import java.io.ByteArrayOutputStream

/** Reuses notebook fonts/layout, without teaching the Markdown core about Android or TeX. */
class MarkdownMath(private val fontSize: Int, private val color: Rgba, private val scale: Double) {
    private val engineAvailable by lazy { runCatching { MathRendering.Blocking.ready() }.getOrDefault(false) }
    private val images = HashMap<Pair<String, Boolean>, MarkdownFormula?>()
    private var sourceSize = 0
    private var imageBytes = 0

    fun render(source: String, display: Boolean): MarkdownFormula? {
        if (!engineAvailable) return null // Wait at most once per page when font installation fails.
        val key = source to display
        if (images.containsKey(key)) return images[key]
        if (source.length > 4096 || images.size >= 128 || sourceSize + source.length > 64 * 1024 || imageBytes > 4 * 1024 * 1024) return null
        sourceSize += source.length
        val result = runCatching {
            if (!balanced(source) || LatexInternals.hasUnknownCommands(source)) return@runCatching null
            val (bitmap, box) = MathRendering.formulaBitmap(source, fontSize / AndroidText.POINTS_TO_PX.toDouble(), color, display) ?: return@runCatching null
            val bytes = try {
                ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            } finally { bitmap.recycle() }
            imageBytes += bytes.size
            if (imageBytes > 4 * 1024 * 1024) return@runCatching null
            MarkdownFormula("data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                box.width / fontSize * scale, box.height / fontSize * scale, box.descent / fontSize * scale)
        }.getOrNull()
        images[key] = result
        return result
    }

    private fun balanced(source: String): Boolean {
        var depth = 0
        var index = 0
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index++ // Escaped braces and percent signs are literal characters.
                '%' -> { while (index < source.length && source[index] != '\n') index++ }
                '{' -> { depth++; if (depth > 64) return false }
                '}' -> { depth--; if (depth < 0) return false }
            }
            index++
        }
        return depth == 0
    }
}
