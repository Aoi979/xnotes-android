package com.xnotes.core.util

/**
 * The document types the app stores, and the file extension each uses.
 *
 * The extension is spelled once here rather than at the two dozen sites that used to hard-code
 * `.xnote`. That matters now there are two: every place that lists, names, renames or opens a file
 * has to agree on which extensions are documents, and a place that forgets one silently hides the
 * user's files from the explorer.
 */
enum class DocumentKind(val extension: String, vararg aliases: String) {
    /** The paged notebook. */
    NOTE("xnote"),

    /** The infinite canvas. */
    CANVAS("xcanvas"),

    /** A plain UTF-8 document; editing never converts it to a notebook bundle. */
    MARKDOWN("md", "markdown"),
    ;

    /** The extension with its dot, as it appears at the end of a file name. */
    val suffix: String get() = ".$extension"

    private val extensions = listOf(extension) + aliases

    fun suffixOf(name: String): String? = extensions.firstOrNull { name.endsWith(".$it", ignoreCase = true) }
        ?.let { name.takeLast(it.length + 1) }

    val canExportPdf: Boolean get() = this != MARKDOWN

    companion object {
        /** The kind [name] denotes, or null when it is not a document at all. */
        fun ofName(name: String): DocumentKind? =
            entries.firstOrNull { it.suffixOf(name) != null }

        fun isDocument(name: String): Boolean = ofName(name) != null

        /** [name] without its document extension, or unchanged when it has none. */
        fun stripSuffix(name: String): String = entries.firstNotNullOfOrNull { it.suffixOf(name) }
            ?.let { suffix -> name.dropLast(suffix.length) }
            ?: name

        /** [name] with [kind]'s extension, added only if it is not already the right one. */
        fun withSuffix(name: String, kind: DocumentKind): String =
            if (kind.suffixOf(name) != null) name else "$name${kind.suffix}"
    }
}
