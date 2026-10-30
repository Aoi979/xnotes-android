package com.xnotes.core.util

/** Keys of per-document stores ("authority|documentId"), where a folder's descendants share its key as a path prefix. */
object DocKeys {

    /** [key] rewritten for a document that moved from [from] to [to], or null when [key] is neither [from] nor under it. */
    fun moved(key: String, from: String, to: String): String? = when {
        key == from -> to
        key.startsWith("$from/") -> to + key.substring(from.length)
        else -> null
    }

    /** The ids from [top] down to [child] for path-like document ids ("root/a/b"), or null when [child] isn't under [top]. */
    fun chain(top: String, child: String): List<String>? {
        if (!within(child, top)) return null
        return generateSequence(child) { id ->
            if (id == top) null else id.substringBeforeLast('/', top)
        }.toList().asReversed()
    }

    /** Whether [key] is [of] itself or a document beneath it. */
    fun within(key: String, of: String): Boolean = key == of || key.startsWith("$of/")

    /** The creation time to assume for a file that never recorded one: the earliest clue, since each is an upper bound. */
    fun inferCreated(firstSeen: Long?, modified: Long?): Long? =
        listOfNotNull(firstSeen?.takeIf { it > 0 }, modified?.takeIf { it > 0 }).minOrNull()
}

/**
 * Move a document's records together, taking the values before removing overlapping source keys.
 * The owner must synchronize access to the map.
 */
internal fun <V : Any> MutableMap<String, V>.moveDocumentKeys(from: String, to: String): Boolean {
    val moves = keys.mapNotNull { key -> DocKeys.moved(key, from, to)?.let { target -> key to target } }
    if (moves.isEmpty()) return false

    val values = moves.associate { (source, target) -> target to getValue(source) }
    for ((source, _) in moves) remove(source)
    putAll(values)
    return true
}
