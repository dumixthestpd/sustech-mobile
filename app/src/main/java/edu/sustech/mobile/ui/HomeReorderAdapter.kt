package edu.sustech.mobile.ui

import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import edu.sustech.mobile.R

/** ItemTouchHelper supplies native drag scrolling and animations. */
class HomeReorderAdapter(private val changed: (List<Row>) -> Unit) : RecyclerView.Adapter<HomeReorderAdapter.Holder>() {
    data class Row(val key: String, val title: String, var enabled: Boolean)
    private val rows = mutableListOf<Row>()
    private lateinit var helper: ItemTouchHelper
    class Holder(val row: LinearLayout, val toggle: SwitchMaterial, val grip: ImageView) : RecyclerView.ViewHolder(row)
    fun attach(list: RecyclerView) {
        list.adapter = this
        helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
                move(source.bindingAdapterPosition, target.bindingAdapterPosition)
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(holder, state)
                if (state == ItemTouchHelper.ACTION_STATE_DRAG) holder?.itemView?.alpha = .82f
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder); holder.itemView.alpha = 1f
                rv.post { if (!rv.isComputingLayout) notifyDataSetChanged() }
            }
        })
        helper.attachToRecyclerView(list)
    }
    fun replace(next: List<Row>) { rows.clear(); rows.addAll(next); notifyDataSetChanged() }
    private fun move(from: Int, to: Int): Boolean {
        if (from !in rows.indices || to !in rows.indices || from == to) return false
        rows.add(to, rows.removeAt(from)); notifyItemMoved(from, to); changed(rows.toList()); return true
    }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
        val context = parent.context
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(2), 0); setBackgroundResource(R.drawable.paper_panel)
            layoutParams = RecyclerView.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(6) }
        }
        val toggle = SwitchMaterial(context).apply { textSize = 14f; setTextColor(context.getColor(R.color.ink)); maxLines = 2 }
        row.addView(toggle, LinearLayout.LayoutParams(0, -1, 1f))
        val grip = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag_handle); imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ink_muted))
            setPadding(dp(14), dp(14), dp(14), dp(14)); isFocusable = true
        }
        row.addView(grip, LinearLayout.LayoutParams(dp(48), dp(48)))
        val holder = Holder(row, toggle, grip)
        grip.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { helper.startDrag(holder); true } else false
        }
        return holder
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = rows[position]; holder.row.tag = item.key
        holder.toggle.apply {
            setOnCheckedChangeListener(null); text = item.title; tag = "toggle:${item.key}"; isChecked = item.enabled
            setOnCheckedChangeListener { _, checked -> item.enabled = checked; changed(rows.toList()) }
        }
        holder.grip.apply {
            tag = "drag:${item.key}"; contentDescription = context.getString(R.string.home_drag_handle, item.title)
            listOf(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD to -1,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD to 1).forEach { (action, step) ->
                ViewCompat.replaceAccessibilityAction(this, action, context.getString(if (step < 0) R.string.home_layout_move_up else R.string.home_layout_move_down, item.title)) { _, _ ->
                    val current = holder.bindingAdapterPosition
                    val moved = move(current, current + step)
                    if (moved) notifyDataSetChanged()
                    moved
                }
            }
        }
    }
}
