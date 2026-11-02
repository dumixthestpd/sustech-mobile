package edu.sustech.mobile.ui.bb

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import edu.sustech.mobile.R
import edu.sustech.mobile.bb.BbAttachment
import edu.sustech.mobile.bb.BbDoc
import edu.sustech.mobile.bb.BbItem
import edu.sustech.mobile.bb.BbMaterial
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.memory.MemoryFile
import edu.sustech.mobile.core.memory.ShareIntent
import edu.sustech.mobile.core.runIo

/**
 * One content item: what it is, and every file it carries.
 *
 * Preview reads the bytes in this app; Share / Open in another app hand the same
 * in-memory bytes to another app through a read grant — nothing is written to
 * storage on either path.
 */
class BbItemFragment : Fragment(R.layout.fragment_bb_item) {

    private val courseId: String get() = requireArguments().getString(ARG_COURSE).orEmpty()
    private val courseName: String get() = requireArguments().getString(ARG_COURSE_NAME).orEmpty()
    private val contentId: String get() = requireArguments().getString(ARG_CONTENT).orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = view.findViewById<TextView>(R.id.bb_item_title)
        val meta = view.findViewById<TextView>(R.id.bb_item_meta)
        val body = view.findViewById<TextView>(R.id.bb_item_body)
        val files = view.findViewById<LinearLayout>(R.id.bb_item_files)
        val empty = view.findViewById<TextView>(R.id.bb_item_empty)
        val label = view.findViewById<TextView>(R.id.bb_item_files_label)

        title.text = ""
        meta.text = courseName
        body.text = ""
        files.removeAllViews()
        empty.text = getString(R.string.bb_item_loading)
        empty.visibility = View.VISIBLE
        label.visibility = View.GONE

        runIo(
            block = { App.bb.item(courseId, contentId) },
            onOk = { detail ->
                if (!isAdded) return@runIo
                title.text = detail.title
                meta.text = listOf(courseName, kindLabel(detail.kind)).filter { it.isNotEmpty() }
                    .joinToString(" · ")
                body.text = detail.bodyText
                body.visibility = if (detail.bodyText.isEmpty()) View.GONE else View.VISIBLE
                label.visibility = View.VISIBLE
                files.removeAllViews()
                if (detail.attachments.isEmpty()) {
                    empty.text = getString(R.string.bb_item_no_files)
                    empty.visibility = View.VISIBLE
                } else {
                    empty.visibility = View.GONE
                    detail.attachments.forEach { files.addView(bindFile(it)) }
                }
            },
            onErr = { error ->
                if (!isAdded) return@runIo
                empty.text = error.friendly(requireContext())
                empty.visibility = View.VISIBLE
            },
        )
    }

    private fun bindFile(attachment: BbAttachment): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_bb_attachment, null, false)
        row.findViewById<TextView>(R.id.bb_file_name).text = attachment.name
        row.findViewById<TextView>(R.id.bb_file_meta).text = typeLabel(attachment)
        row.findViewById<View>(R.id.bb_file_preview).setOnClickListener { preview(attachment) }
        row.findViewById<View>(R.id.bb_file_share).setOnClickListener { share(attachment) }
        // Tapping the file itself is the same as Preview: the file must never
        // leave the app by accident (the system would hand a .docx to Office).
        row.setOnClickListener { preview(attachment) }
        return row
    }

    /** What the row says under the file name — a name, never a mime string. */
    private fun typeLabel(attachment: BbAttachment): String {
        val name = attachment.name.lowercase()
        val mime = attachment.mime.lowercase()
        return when {
            name.endsWith(".pdf") || mime == "application/pdf" -> getString(R.string.bb_file_pdf)
            name.endsWith(".docx") || name.endsWith(".doc") || mime.contains("wordprocessing") ->
                getString(R.string.bb_file_word)
            mime.startsWith("image/") -> getString(R.string.bb_file_image)
            name.endsWith(".txt") || mime == "text/plain" -> getString(R.string.bb_file_text)
            else -> getString(R.string.bb_file_other)
        }
    }

    private fun load(attachment: BbAttachment, then: (MemoryFile) -> Unit) = runIo(
        block = { BbMaterial.attachment(courseId, contentId, attachment) },
        onOk = { file -> if (isAdded) then(file) },
        onErr = { error ->
            if (isAdded) showError(error)
        },
    )

    private fun preview(attachment: BbAttachment) = load(attachment) { file ->
        if (readableHere(file)) {
            startActivity(MaterialReaderActivity.intent(requireContext(), file))
        } else {
            Toast.makeText(requireContext(), R.string.bb_preview_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /** Types the reader can show without leaving the app. */
    private fun readableHere(file: MemoryFile): Boolean =
        file.mime == "application/pdf" ||
            file.mime.startsWith("image/") ||
            BbDoc.isText(file.mime) ||
            file.name.endsWith(".docx", ignoreCase = true)

    private fun share(attachment: BbAttachment) = load(attachment) { file ->
        startActivity(
            ShareIntent.chooser(
                requireContext(),
                ShareIntent.send(requireContext(), file),
                getString(R.string.bb_share_with),
            ),
        )
    }

    private fun showError(error: Throwable) {
        val text = error.friendly(requireContext())
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }

    private fun kindLabel(kind: BbItem.Kind): String = when (kind) {
        BbItem.Kind.FOLDER -> getString(R.string.bb_kind_folder)
        BbItem.Kind.DOCUMENT, BbItem.Kind.FILE -> getString(R.string.bb_kind_file)
        BbItem.Kind.ASSIGNMENT -> getString(R.string.bb_kind_assignment)
        BbItem.Kind.LINK -> getString(R.string.bb_kind_link)
        BbItem.Kind.VIDEO -> getString(R.string.bb_kind_video)
        BbItem.Kind.UNKNOWN -> getString(R.string.bb_kind_unknown)
    }

    companion object {
        private const val ARG_COURSE = "courseId"
        private const val ARG_COURSE_NAME = "courseName"
        private const val ARG_CONTENT = "contentId"

        fun newInstance(courseId: String, courseName: String, contentId: String): BbItemFragment =
            BbItemFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_COURSE, courseId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_CONTENT, contentId)
                }
            }
    }
}
