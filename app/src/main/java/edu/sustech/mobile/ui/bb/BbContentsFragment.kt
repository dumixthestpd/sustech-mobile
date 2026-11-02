package edu.sustech.mobile.ui.bb

import android.os.Bundle
import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.bb.BbItem
import edu.sustech.mobile.core.App
import edu.sustech.mobile.ui.ListFragment

/**
 * One level of a course's content tree, pushed on top of the shell.
 *
 * Folders descend, files open the item screen. Each level is its own fragment on
 * the back stack, so the toolbar back arrow and the system back gesture both do
 * the obvious thing.
 */
class BbContentsFragment : ListFragment<BbItem>(R.layout.fragment_list) {

    private val courseId: String get() = requireArguments().getString(ARG_COURSE).orEmpty()
    private val courseName: String get() = requireArguments().getString(ARG_COURSE_NAME).orEmpty()
    private val parentId: String? get() = requireArguments().getString(ARG_PARENT)

    override fun rowLayout() = R.layout.item_tis_course

    override fun cachePrefix() = "bb.tree.${courseId}.${parentId ?: "root"}"

    override fun emptyText() = getString(R.string.bb_contents_empty)

    override suspend fun fetch(): List<BbItem> = App.bb.contents(courseId, parentId)

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
