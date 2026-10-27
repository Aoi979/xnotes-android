package com.xnotes.platform

import android.os.Trace

/** Coarse render phases for system traces; keep this outside per-item and per-vertex loops. */
internal inline fun <T> renderTrace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
