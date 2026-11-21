package edu.sustech.mobile.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** A readable single column on phones and large WSA windows alike. */
class PaperColumn @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = minOf(MeasureSpec.getSize(widthMeasureSpec), (760 * resources.displayMetrics.density).toInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthMeasureSpec)), heightMeasureSpec)
    }
}
