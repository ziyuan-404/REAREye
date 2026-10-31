package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Arbitrate before SubScreenLauncher's native vertical gesture dispatcher. */
internal object RearNotificationGestures {
    private class Target(val clearable: Boolean, val dismiss: () -> Boolean)
    private val targets = WeakHashMap<View, Target>()
    private val main = Handler(Looper.getMainLooper())
    private var pendingTap: Runnable? = null
    private var lastDown = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var doubleTap = false
    var suppressNativeDoubleTap = false
        private set
    private var deliveringCancel = false
    private var current: View? = null
    private var target: Target? = null
    private var surface: View? = null
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragX = 0f
    private var axis = 0 // undecided, horizontal, vertical
    private var restoreOutline: (() -> Unit)? = null
    private val settling = WeakHashMap<View, () -> Unit>()

    fun register(view: View, clearable: Boolean, dismiss: () -> Boolean) {
        targets[view] = Target(clearable, dismiss)
    }

    fun cancelTap() { pendingTap?.let(main::removeCallbacks); pendingTap = null }

    fun singleTap(view: View, action: () -> Unit) {
        cancelTap()
        if (doubleTap || axis != 0) return
        val reference = WeakReference(view)
        val job = Runnable {
            pendingTap = null
            if (reference.get()?.isAttachedToWindow == true && !doubleTap) action()
        }
        pendingTap = job
        // Factory Z1.d uses 400ms, longer than Android's default double-tap timeout.
        main.postDelayed(job, 416L)
    }

    fun dispatch(activity: Activity, event: MotionEvent): Boolean {
        if (deliveringCancel) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTap()
                val now = SystemClock.uptimeMillis()
                doubleTap = now - lastDown < 400L && abs(event.rawX - lastX) < 100f && abs(event.rawY - lastY) < 100f
                lastDown = now; lastX = event.rawX; lastY = event.rawY
                val rect = Rect()
                current = targets.keys.filter {
                    it.isAttachedToWindow && it.isShown && it.alpha > .5f &&
                        !NativeRearMotion.isFlipping(it) && it.getGlobalVisibleRect(rect) &&
                        rect.contains(event.rawX.toInt(), event.rawY.toInt())
                }.minByOrNull {
                    it.getGlobalVisibleRect(rect)
                    abs(rect.centerY() - event.rawY)
                }
                val footer = current?.findViewById<View>(hk.uwu.reareye.R.id.rear_notification_footer)
                suppressNativeDoubleTap = footer?.isShown == true && footer.getGlobalVisibleRect(rect) &&
                    rect.contains(event.rawX.toInt(), event.rawY.toInt())
                if (suppressNativeDoubleTap) doubleTap = false
                target = current?.let(targets::get)
                surface = current?.let { NativeRearCardHost.container(it) ?: it }
                surface?.let { card -> card.animate().cancel(); settling.remove(card)?.invoke() }
                restoreOutline?.invoke(); restoreOutline = null
                downX = event.rawX; downY = event.rawY; dragX = downX; axis = 0
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val view = current ?: return false
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val config = ViewConfiguration.get(activity)
                if (axis == 0 && target?.clearable == true &&
                    abs(dx) > config.scaledPagingTouchSlop && abs(dx) > abs(dy)) {
                    axis = 1
                    cancelTap()
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    deliveringCancel = true
                    try { activity.dispatchTouchEvent(cancel) } finally { deliveringCancel = false; cancel.recycle() }
                    restoreOutline = NativeRearCardHost.beginDrag(view)
                    dragX = event.rawX
                } else if (axis == 0 && abs(dy) > config.scaledTouchSlop && abs(dy) >= abs(dx)) {
                    axis = 2; cancelTap()
                }
                if (axis == 1) {
                    surface?.let {
                        it.translationX = event.rawX - dragX
                        it.alpha = (1f - abs(it.translationX) / it.width.coerceAtLeast(1)).coerceIn(.15f, 1f)
                    }
                    return true
                }
                // Do not feed horizontal jitter to the vertical recognizer before
                // the paging slop is reached. Both recognizers already saw DOWN.
                if (axis == 0 && target?.clearable == true && abs(dx) > abs(dy)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(event)
                if (axis == 1) {
                    val card = surface
                    val config = ViewConfiguration.get(activity)
                    velocity?.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
                    val speed = velocity?.xVelocity ?: 0f
                    val translation = card?.translationX ?: 0f
                    // Stock NotificationSwipeHelper: 500dp/s or 60% of row width.
                    val fast = abs(speed) > 500f * activity.resources.displayMetrics.density && speed * translation > 0f
                    val cutout = activity.display?.cutout
                    val visibleWidth = ((card?.width ?: 1) - (cutout?.safeInsetLeft ?: 0) -
                        (cutout?.safeInsetRight ?: 0)).coerceAtLeast(1)
                    val dismiss = event.actionMasked == MotionEvent.ACTION_UP && card != null &&
                        (fast || abs(translation) > visibleWidth * .6f)
                    val restore = restoreOutline; restoreOutline = null
                    val callback = target
                    if (card != null) {
                        settling[card] = { restore?.invoke() }
                        fun finished() { settling.remove(card)?.invoke() }
                        if (dismiss) card.animate().translationX(if (translation < 0) -card.width.toFloat() else card.width.toFloat())
                            .alpha(0f).setDuration(160L).withEndAction {
                                if (callback?.dismiss?.invoke() != true)
                                    card.animate().translationX(0f).alpha(1f).setDuration(180L).withEndAction { finished() }.start()
                                else finished()
                            }.start()
                        else card.animate().translationX(0f).alpha(1f).setDuration(180L).withEndAction { finished() }.start()
                    } else restore?.invoke()
                    velocity?.recycle(); velocity = null; current = null; target = null; surface = null
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || axis == 2 || doubleTap) cancelTap()
                velocity?.recycle(); velocity = null; current = null; target = null; surface = null
            }
            MotionEvent.ACTION_POINTER_DOWN -> { cancelTap(); axis = 2 }
        }
        return false
    }
}
