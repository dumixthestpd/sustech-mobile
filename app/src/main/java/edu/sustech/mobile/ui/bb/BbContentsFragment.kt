package edu.sustech.mobile.ui.bb

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import edu.sustech.mobile.R
import edu.sustech.mobile.bb.BbApi
import edu.sustech.mobile.bb.BbItem
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.ui.ListFragment

/**
 * One level of a course's content tree, pushed on top of the shell.
 *
 * Folders descend, files open the item screen. Each level is its own fragment on
 * the back stack, so the toolbar back arrow and the system back gesture both do
 * the obvious thing. The course's own level also shows that course's
 * announcements above the items — they are what the site calls notifications,
 * and they belong to the course rather than to the tree.
 */
class BbContentsFragment : ListFragment<BbItem>(R.layout.fragment_bb_contents) {

    private val courseId: String get() = requireArguments().getString(ARG_COURSE).orEmpty()
    private val courseName: String get() = requireArguments().getString(ARG_COURSE_NAME).orEmpty()
    private val parentId: String? get() = requireArguments().getString(ARG_PARENT)

    override fun rowLayout() = R.layout.item_tis_course

    override fun cachePrefix() = "bb.tree.${courseId}.${parentId ?: "root"}"

    override fun emptyText() = getString(R.string.bb_contents_empty)

    override suspend fun fetch(): List<BbItem> = App.bb.contents(courseId, parentId)

    override fun onReady(view: View) = loadNotices(view)

    override fun bindRow(view: View, item: BbItem, position: Int) {
        view.findViewById<TextView>(R.id.course_code).text = kindLabel(item.kind)
        view.findViewById<TextView>(R.id.course_name).text = item.title
        view.findViewById<TextView>(R.id.course_meta).text =
            if (item.hasChildren) getString(R.string.bb_kind_folder) else ""
        view.setOnClickListener {
            val next = if (item.hasChildren) {
                newInstance(courseId, courseName, item.bid, item.title)
            } else {
                BbItemFragment.newInstance(courseId, courseName, item.bid)
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.service_container, next)
                .addToBackStack("bb_${item.id}")
                .commit()
        }
    }

    /**
     * Announcements, above the items on the course's own level.
     *
     * A failure here is not an error state: the tree is why this screen exists,
     * so a course whose announcements cannot be read still lists its folders
     * instead of showing a banner over them.
     */
    private fun loadNotices(view: View) {
        if (parentId != null) return
        val header = view.findViewById<TextView>(R.id.bb_notice_header)
        val container = view.findViewById<LinearLayout>(R.id.bb_notice_list)
        viewLifecycleOwner.runIo(
            block = { App.bb.announcements(courseId) },
            onOk = { notices ->
                if (!isAdded) return@runIo
                container.removeAllViews()
                if (notices.isEmpty()) {
                    header.visibility = View.GONE
                    return@runIo
                }
                header.visibility = View.VISIBLE
                header.text = getString(R.string.bb_notice_count, notices.size)
                notices.take(MAX_NOTICES).forEach { notice ->
                    val row = layoutInflater.inflate(R.layout.item_tis_course, container, false)
                    row.findViewById<TextView>(R.id.course_code).text = notice.date
                    row.findViewById<TextView>(R.id.course_name).text = notice.title
                    row.findViewById<TextView>(R.id.course_meta).text =
                        getString(R.string.bb_kind_notice)
                    row.setOnClickListener { showNotice(notice) }
                    container.addView(row)
                }
            },
        )
    }

    private fun showNotice(notice: BbApi.Notice) {
        AlertDialog.Builder(requireContext())
            .setTitle(notice.title)
            .setMessage(notice.body.ifEmpty { getString(R.string.bb_item_no_files) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        private const val ARG_PARENT = "parentId"
        private const val MAX_NOTICES = 3

        fun newInstance(
            courseId: String,
            courseName: String,
            parentId: String? = null,
            @Suppress("UNUSED_PARAMETER") parentTitle: String? = null,
        ): BbContentsFragment = BbContentsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_COURSE, courseId)
                putString(ARG_COURSE_NAME, courseName)
                parentId?.let { putString(ARG_PARENT, it) }
            }
        }
    }
}
