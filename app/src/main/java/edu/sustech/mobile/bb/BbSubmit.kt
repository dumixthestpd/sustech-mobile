package edu.sustech.mobile.bb

/**
 * Reading the assignment upload form Blackboard renders, and staging a file the
 * way the site's own file picker does.
 *
 * Submission is the one write this app performs against the school, and BB has
 * no way to withdraw an attempt, so everything here is a faithful port of the
 * form the server itself emits: every input's name and value, plus the five
 * `newFile_*` fields its JavaScript adds once a file is staged. Guessing any of
 * them means a submission that silently arrives without the file.
 */
object BbSubmit {

    /** The parsed form: every field BB would post, and the part name for the file. */
    data class Form(val fields: Map<String, String>, val filePart: String = FILE_PART)

    private val INPUT = Regex("<input[^>]*>", RegexOption.IGNORE_CASE)
    private val TEXTAREA = Regex(
        "<textarea[^>]*name=[\"']([^\"']+)[\"'][^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val NAME = Regex("name=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val VALUE = Regex("value=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val TYPE = Regex("type=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    fun parse(html: String): Form {
        val fields = LinkedHashMap<String, String>()
        INPUT.findAll(html).forEach { match ->
            val chunk = match.value
            val type = TYPE.find(chunk)?.groupValues?.get(1)?.lowercase().orEmpty()
            // Buttons and the file input are not form data; only a checked
            // checkbox or radio is submitted, exactly as a browser would.
            if (type in setOf("submit", "button", "file", "reset", "image")) return@forEach
            if (type in setOf("checkbox", "radio") && !chunk.contains("checked", ignoreCase = true)) {
                return@forEach
            }
            val name = NAME.find(chunk)?.groupValues?.get(1) ?: return@forEach
            val value = VALUE.find(chunk)?.groupValues?.get(1) ?: return@forEach
            fields[name] = unescape(value)
        }
        // Editor fields carry no value attribute; an empty string still has to
        // be present, otherwise BB stores the attempt with no comment body.
        TEXTAREA.findAll(html).forEach { match ->
            val name = match.groupValues[1]
            if (!fields.containsKey(name)) fields[name] = ""
        }
        return Form(fields)
    }

    /** Adds what BB's picker adds once a local file is chosen. */
    fun stage(form: Form, fileName: String): Form {
        val fields = LinkedHashMap(form.fields)
        fields["newFile_attachmentType"] = "L"
        fields["newFile_fileId"] = "new"
        fields["newFile_artifactFileId"] = "undefined"
        fields["newFile_artifactType"] = "undefined"
        fields["newFile_artifactTypeResourceKey"] = "undefined"
        fields["newFile_linkTitle"] = fileName
        fields["dispatch"] = "submit"
        return form.copy(fields = fields)
    }

    /** The form is worth posting only if BB gave us its nonce. */
    fun isUsable(form: Form): Boolean =
        form.fields.keys.any { it.contains("NonceUtil.nonce") } &&
            form.fields.containsKey("attempt_id")

    const val FILE_PART = "newFile_LocalFile0"

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
