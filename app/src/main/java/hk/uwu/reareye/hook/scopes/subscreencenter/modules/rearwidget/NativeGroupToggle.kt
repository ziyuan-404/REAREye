package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.TranslateAnimation
import android.widget.TextSwitcher
import android.widget.TextView

/** Two real text views, clipped to one line, so labels roll through the same slot. */
internal class NativeGroupToggle(context: Context, private val count: Int, expanded: Boolean) : TextSwitcher(context) {
    private var expanded = expanded
    init {
        tag = "rear_notification_group_toggle"
        isClickable = true; isFocusable = true
        clipChildren = true; clipToPadding = true
        setFactory {
            TextView(context).apply {
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; gravity = Gravity.CENTER
                layoutParams = LayoutParams(-1, -1)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        setCurrentText(label(expanded))
        contentDescription = label(expanded)
    }
    private fun label(value: Boolean) = if (value) "收起 $count 条通知" else "展开 $count 条通知"
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        // A reversal can arrive before either child finishes its old animation.
        // Keep one visible label and start the replacement from this new state.
        for (index in 0 until childCount) getChildAt(index).clearAnimation()
        val direction = if (value) 1f else -1f
        fun slide(from: Float, to: Float) = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, from, Animation.RELATIVE_TO_SELF, to).apply {
                duration = if (ValueAnimator.areAnimatorsEnabled()) 200L else 0L
                interpolator = AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in)
            }
        inAnimation = slide(-direction, 0f)
        outAnimation = slide(0f, direction)
        setText(label(value)); contentDescription = label(value)
    }
}
