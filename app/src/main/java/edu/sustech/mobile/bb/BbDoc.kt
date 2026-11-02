package edu.sustech.mobile.bb

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reading a Word document without a document library.
 *
 * A .docx is a zip whose `word/document.xml` holds the text: paragraphs become
 * line breaks, tabs stay tabs, and every other tag (runs, formatting, fields)
 * is dropped. That is enough to read a lab report template or a handout in the
 * app, and it costs no dependency. Images and tables lose their layout — that is
 * stated in the UI rather than pretended away.
 */
object BbDoc {

    /** Plain text of a .docx, or null when the zip has no document body. */
    fun docxText(bytes: ByteArray): String? = runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return@use textOfXml(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** WordprocessingML → text. Visible for the unit test. */
    fun textOfXml(xml: String): String = xml
        .replace("</w:p>", "\n")
        .replace("<w:tab/>", "\t")
        .replace("<w:br/>", "\n")
        .replace(Regex("<[^>]+>"), "")
        .let(::unescape)
        .replace(Regex("\n{3,}"), "\n\n")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: ""
        }

    /** True when the mime is a document this app can read as text. */
    fun isText(mime: String): Boolean = mime == DOCX || mime == "text/plain"

    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
}
