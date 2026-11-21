package edu.sustech.mobile.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import edu.sustech.mobile.R
import java.util.WeakHashMap

/** Styling follows the first displayed card, independently of its service type. */
object HomeCardStyler {
    private data class Baseline(val text: ColorStateList, val tint: ColorStateList?, val background: android.graphics.drawable.Drawable.ConstantState?)
    private val baselines = WeakHashMap<TextView, Baseline>()
    fun apply(card: View, emphasized: Boolean) {
        val context = card.context
        (card as? MaterialCardView)?.apply {
            setCardBackgroundColor(context.getColor(if (emphasized) R.color.brand else R.color.surface))
            strokeColor = context.getColor(if (emphasized) R.color.brand else R.color.divider)
        }
        fun visit(view: View) {
            if (view.tag == "palette_exempt") return
            if (view is TextView) {
                val base = baselines.getOrPut(view) { Baseline(view.textColors, (view as? MaterialButton)?.backgroundTintList, view.background?.constantState) }
                when (view) {
                    is Chip -> {
                        view.chipBackgroundColor = if (emphasized) ColorStateList.valueOf(if (view.isChecked) context.getColor(R.color.white) else 0x22ffffff) else context.getColorStateList(R.color.paper_choice_surface)
                        view.setTextColor(if (emphasized) context.getColor(if (view.isChecked) R.color.brand else R.color.white) else context.getColor(R.color.ink))
                    }
                    is MaterialButton -> {
                        view.setTextColor(if (emphasized) ColorStateList.valueOf(context.getColor(R.color.white)) else base.text)
                        view.backgroundTintList = if (emphasized) ColorStateList.valueOf(0x22ffffff) else base.tint
                    }
                    else -> {
                        view.setTextColor(if (emphasized) ColorStateList.valueOf(context.getColor(R.color.white)) else base.text)
                        if (base.background != null) view.background = if (emphasized) context.getDrawable(R.drawable.paper_hero_tag) else base.background.newDrawable(context.resources)
                    }
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(card)
    }
}
