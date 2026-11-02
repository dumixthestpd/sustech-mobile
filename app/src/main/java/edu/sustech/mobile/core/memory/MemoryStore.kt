package edu.sustech.mobile.core.memory

import java.util.UUID

/** A file that exists only in this process's heap. */
class MemoryFile(val token: String, val name: String, val mime: String, val bytes: ByteArray) {
    val size: Long get() = bytes.size.toLong()
}

/**
 * Transient, in-memory attachments.
 *
 * Keyed by whatever identifies the source (an attachment id, a URL); the token
 * is what a `content://` URI carries, so no path ever leaves the process. A live
 * read pins the entry: the LRU must not pull bytes out from under an app that is
 * still reading them.
 */
object MemoryStore {

    private const val CAP_BYTES = 48L * 1024 * 1024

    /** Access-ordered, so eviction takes the least recently used entry. */
    private val byKey = object : LinkedHashMap<String, MemoryFile>(16, 0.75f, true) {}
    private val byToken = HashMap<String, MemoryFile>()
    private val pins = HashMap<String, Int>()
    private var total = 0L

    /** Insert (or refresh) an entry and return it; unpinned LRU entries go first. */
    @Synchronized
    fun put(key: String, name: String, mime: String, bytes: ByteArray): MemoryFile {
        byKey.remove(key)?.let { forget(it) }
        // A single file bigger than the whole budget is still allowed: refusing
        // to show the manual would be worse than being over the cap.
        val file = MemoryFile(UUID.randomUUID().toString(), name, mime, bytes)
        byKey[key] = file
        byToken[file.token] = file
        total += bytes.size
        while (total > CAP_BYTES) {
            val victim = byKey.entries.firstOrNull { (pins[it.value.token] ?: 0) == 0 } ?: break
            byKey.remove(victim.key)
            forget(victim.value)
        }
        return file
    }

    @Synchronized
    fun find(key: String): MemoryFile? = byKey[key]

    @Synchronized
    fun byToken(token: String): MemoryFile? = byToken[token]

    @Synchronized
    fun pin(token: String) {
        pins[token] = (pins[token] ?: 0) + 1
    }

    @Synchronized
    fun unpin(token: String) {
        val left = (pins[token] ?: 1) - 1
        if (left <= 0) pins.remove(token) else pins[token] = left
    }

    /** For diagnostics and tests. */
    @Synchronized
    fun isPinned(token: String): Boolean = (pins[token] ?: 0) > 0

    @Synchronized
    fun size(): Int = byKey.size

    @Synchronized
    fun totalBytes(): Long = total

    private fun forget(file: MemoryFile) {
        byToken.remove(file.token)
        total -= file.bytes.size
    }

    @Synchronized
    fun clear() {
        byKey.clear()
        byToken.clear()
        pins.clear()
        total = 0
    }
}
