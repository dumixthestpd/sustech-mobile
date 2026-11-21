package edu.sustech.mobile.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

class AssignmentPagerCard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : MaterialCardView(context, attrs) {
    var onPage: ((Int) -> Unit)? = null
    private var downX = 0f; private var downY = 0f; private var horizontal = false
    init { isClickable = true }
    private fun track(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y; horizontal = false }
        if (e.actionMasked == MotionEvent.ACTION_MOVE && abs(e.x-downX) > ViewConfiguration.get(context).scaledTouchSlop * 2 && abs(e.x-downX) > abs(e.y-downY)*1.5f) {
            horizontal = true; isPressed = false; parent?.requestDisallowInterceptTouchEvent(true)
        }
    }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        track(e)
        if (horizontal) return true
        return super.onInterceptTouchEvent(e)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // When a non-clickable label/blank area was hit, this card owns DOWN itself;
        // ViewGroup does not call onInterceptTouchEvent for its later MOVE events.
        track(e)
        if (horizontal) {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (abs(e.x-downX) > 40 * resources.displayMetrics.density) onPage?.invoke(if (e.x < downX) 1 else -1)
                horizontal = false; parent?.requestDisallowInterceptTouchEvent(false)
            }
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) { horizontal = false; parent?.requestDisallowInterceptTouchEvent(false) }
            return true
        }
        return super.onTouchEvent(e)
    }
}
