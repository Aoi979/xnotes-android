package com.xnotes.platform

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Application-wide records. Pane-local session/recovery stores deliberately live elsewhere. */
internal class DocumentStores private constructor(context: Context) {
    val views = ViewStateStore(JsonStore.viewStates(context))
    val created = CreationTimeStore(JsonStore.createdTimes(context))
    val metadata = DocMetaStore(JsonStore.docMeta(context))

    companion object {
        private val instances = ConcurrentHashMap<String, DocumentStores>()

        fun forApp(context: Context): DocumentStores {
            val app = context.applicationContext
            return instances.getOrPut(app.filesDir.canonicalPath) { DocumentStores(app) }
        }
    }
}
