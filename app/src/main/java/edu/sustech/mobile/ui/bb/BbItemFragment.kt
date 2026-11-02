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
        row.findViewById<TextView>(R.id.bb_file_meta).text = attachment.mime.ifEmpty {
            attachment.name.substringAfterLast('.', "").uppercase()
        }
        row.findViewById<View>(R.id.bb_file_preview).setOnClickListener { preview(attachment) }
        row.findViewById<View>(R.id.bb_file_share).setOnClickListener { share(attachment) }
        row.findViewById<View>(R.id.bb_file_open).setOnClickListener { openWith(attachment) }
        return row
    }

    private fun load(attachment: BbAttachment, then: (MemoryFile) -> Unit) = runIo(
        block = { BbMaterial.attachment(courseId, contentId, attachment) },
        onOk = { file -> if (isAdded) then(file) },
        onErr = { error ->
            if (isAdded) showError(error)
        },
    )

    private fun preview(attachment: BbAttachment) = load(attachment) { file ->
        if (file.mime == "application/pdf" || file.mime.startsWith("image/")) {
            startActivity(PdfReaderActivity.intent(requireContext(), file))
        } else {
            Toast.makeText(requireContext(), R.string.bb_preview_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    private fun share(attachment: BbAttachment) = load(attachment) { file ->
        startActivity(
            Intent.createChooser(
                ShareIntent.send(requireContext(), file),
                getString(R.string.bb_share_with),
            ),
        )
    }

    private fun openWith(attachment: BbAttachment) = load(attachment) { file ->
        startActivity(
            Intent.createChooser(
                ShareIntent.view(requireContext(), file),
                getString(R.string.bb_open_with),
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
