package edu.sustech.mobile.bb

import org.json.JSONObject

/** One content item, as the tree shows it. */
data class BbItem(
    /** Core id (`637881`) — the cache/URL-safe form. */
    val id: String,
    /** The id exactly as Blackboard wrote it (`_637881_1`); URLs use this. */
    val bid: String,
    val title: String,
    val kind: Kind,
    val hasChildren: Boolean,
) {
    enum class Kind { FOLDER, DOCUMENT, FILE, ASSIGNMENT, LINK, VIDEO, UNKNOWN }
}

/** One file an item carries. */
data class BbAttachment(val id: String, val name: String, val mime: String = "")

/**
 * The Blackboard wire, mapped away from JSON so every rule below is unit-tested
 * instead of discovered on a phone.
 */
object BbWire {

    /** `_637881_1` → `637881`. Never rstrip: ids ending in 1 lose a digit. */
    fun coreId(bbId: String): String = bbId.trim('_').split('_').firstOrNull().orEmpty()

    /** `637881` → `_637881_1`; an already-bracketed id is left alone. */
    fun bracket(id: String): String = when {
        id.startsWith("_") -> id
        id.isEmpty() -> id
        else -> "_${id}_1"
    }

    fun kindOf(handlerId: String): BbItem.Kind = when (handlerId) {
        "resource/x-bb-folder" -> BbItem.Kind.FOLDER
        "resource/x-bb-document" -> BbItem.Kind.DOCUMENT
        "resource/x-bb-file" -> BbItem.Kind.FILE
        "resource/x-bb-assignment" -> BbItem.Kind.ASSIGNMENT
        "resource/x-bb-link" -> BbItem.Kind.LINK
        "resource/x-bb-video" -> BbItem.Kind.VIDEO
        else -> BbItem.Kind.UNKNOWN
    }

    /** Only a stated "No" hides an item; "Yes" grants nothing. */
    fun isUnavailable(item: JSONObject): Boolean =
        item.optJSONObject("availability")?.optString("available") == "No"

    /** Items of one listing page, unavailable ones dropped, titles de-blanked. */
    fun contents(json: JSONObject): List<BbItem> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<BbItem>(results.length())
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val rawId = item.optString("id")
            if (rawId.isEmpty() || isUnavailable(item)) continue
            out += BbItem(
                id = coreId(rawId),
                bid = rawId,
                title = item.optString("title").ifEmpty { "(untitled)" },
                kind = kindOf(item.optJSONObject("contentHandler")?.optString("id").orEmpty()),
                hasChildren = item.optBoolean("hasChildren"),
            )
        }
        return out
    }

    fun nextPage(json: JSONObject): String? =
        json.optJSONObject("paging")?.optString("nextPage").orEmpty().takeIf { it.isNotEmpty() }

    fun attachments(json: JSONObject): List<BbAttachment> {
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val a = results.optJSONObject(i) ?: return@mapNotNull null
            val id = a.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            val raw = a.optString("fileName")
            val name = runCatching {
                java.net.URLDecoder.decode(raw, "UTF-8")
            }.getOrDefault(raw)
            BbAttachment(id, name, a.optString("mimeType"))
        }
    }

    /** Body HTML → a one-line plain-text summary (no HTML parser dependency). */
    fun bodyText(html: String, limit: Int = 400): String = html
        .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(limit)
}

/** Name ↔ mime, because Blackboard serves some files as bare `xid-…`. */
object BbFiles {

    fun mimeOf(name: String, bytes: ByteArray): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppt" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> sniff(bytes)
        }

    /** The bytes decide when the name lies or carries no extension. */
    fun sniff(bytes: ByteArray): String = when {
        bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() -> "application/pdf"
        bytes.size >= 2 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() -> "application/zip"
        else -> "application/octet-stream"
    }

    /** `xid-12345` with PDF bytes still deserves a name the reader can use. */
    fun withExtension(name: String, mime: String): String {
        if (name.substringAfterLast('.', "").isNotEmpty()) return name
        val ext = when (mime) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "application/zip" -> "zip"
            "text/plain" -> "txt"
            else -> "bin"
        }
        return "$name.$ext"
    }
}
