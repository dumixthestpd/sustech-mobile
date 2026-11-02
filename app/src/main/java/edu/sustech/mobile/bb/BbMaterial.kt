package edu.sustech.mobile.bb

import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.memory.MemoryFile
import edu.sustech.mobile.core.memory.MemoryStore

/**
 * BB attachments → in-memory files, one download per attachment per process.
 *
 * Preview and Share both call this, so the second tap never re-downloads and the
 * reader and the receiving app cannot disagree about the bytes.
 */
object BbMaterial {

    private val locks = HashMap<String, Any>()

    fun attachment(courseId: String, contentId: String, attachment: BbAttachment): MemoryFile {
        val key = "bb:${BbWire.coreId(courseId)}:${BbWire.coreId(contentId)}:${attachment.id}"
        MemoryStore.find(key)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(key) { Any() } }
        synchronized(lock) {
            MemoryStore.find(key)?.let { return it }
            val bytes = App.bb.download(courseId, contentId, attachment.id)
            val named = BbFiles.withExtension(attachment.name, BbFiles.mimeOf(attachment.name, bytes))
            val mime = if (attachment.mime.startsWith("application/") || attachment.mime.startsWith("image/")) {
                attachment.mime
            } else {
                BbFiles.mimeOf(named, bytes)
            }
            return MemoryStore.put(key, named, mime, bytes)
        }
    }

    /** An inline file a document body points at. */
    fun inline(url: String): MemoryFile {
        val key = "bb:inline:$url"
        MemoryStore.find(key)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(key) { Any() } }
        synchronized(lock) {
            MemoryStore.find(key)?.let { return it }
            val bytes = App.bb.downloadInline(url)
            val raw = url.substringAfterLast('/').substringBefore('?')
            val named = BbFiles.withExtension(raw.ifEmpty { "image" }, BbFiles.mimeOf(raw, bytes))
            return MemoryStore.put(key, named, BbFiles.mimeOf(named, bytes), bytes)
        }
    }
}
