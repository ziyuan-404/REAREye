package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A finite idle hold on the rear launcher window; never wakes a sleeping display. */
class RearScreenTimeoutHook : RoxyHooker() {
    private val main = Handler(Looper.getMainLooper())
    private var host: Activity? = null
    private var resumed = false
    private var heldView: View? = null
    private var previousKeepOn = false
    private var seconds: () -> Int = { 0 }
    private var displays: DisplayManager? = null
    private var lastDisplayState = Display.STATE_UNKNOWN
    private val expire = Runnable { release() }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { if (host?.display?.displayId == displayId) release() }
        override fun onDisplayChanged(displayId: Int) {
            if (host?.display?.displayId != displayId) return
            val state = displays?.getDisplay(displayId)?.state ?: Display.STATE_UNKNOWN
            if (state == lastDisplayState) return
            lastDisplayState = state
            if (state == Display.STATE_ON) arm() else release()
        }
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            seconds = { hookPrefs.getInt(ConfigKeys.REAR_SCREEN_TIMEOUT_SECONDS, 0) }
            val launcher = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass().resolve()
            launcher.firstMethod { name = "onResume"; parameterCount = 0 }.hook {
                after {
                    val activity = instance as? Activity ?: return@after
                    if (host !== activity) release()
                    host = activity
                    lastDisplayState = activity.display?.state ?: Display.STATE_UNKNOWN
                    resumed = true
                    if (displays == null) {
                        displays = activity.getSystemService(DisplayManager::class.java)
                        displays?.registerDisplayListener(displayListener, main)
                    }
                    activity.window.decorView.post { if (host === activity && resumed) arm() }
                }
            }
            launcher.firstMethod { name = "dispatchTouchEvent"; parameterCount = 1 }.hook {
                after {
                    val action = (args.firstOrNull() as? MotionEvent)?.actionMasked
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) arm()
                }
            }
            listOf("onPause", "onDestroy").forEach { method ->
                launcher.firstMethod { name = method; parameterCount = 0 }.hook {
                    before {
                        if (host === instance) { resumed = false; release() }
                    }
                }
            }
        }
    }

    private fun arm() {
        val activity = host ?: return
        val duration = seconds().takeIf { it in setOf(15, 30, 60, 120, 300) }
        if (duration == null || !resumed || activity.isDestroyed || activity.isFinishing ||
            activity.display?.displayId == Display.DEFAULT_DISPLAY || activity.display?.state != Display.STATE_ON) {
            release()
            return
        }
        val root = activity.window.decorView
        if (heldView !== root) {
            release()
            heldView = root
            previousKeepOn = root.keepScreenOn
        }
        root.keepScreenOn = true
        main.removeCallbacks(expire)
        main.postDelayed(expire, duration * 1_000L)
    }

    private fun release() {
        main.removeCallbacks(expire)
        heldView?.keepScreenOn = previousKeepOn
        heldView = null
    }

    override fun onHotReloadQuiesce() {
        val done = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                release()
                displays?.unregisterDisplayListener(displayListener)
                displays = null
                resumed = false
                host = null
                seconds = { 0 }
            } finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else { main.post(cleanup); check(done.await(3, TimeUnit.SECONDS)) { "Rear timeout cleanup timed out" } }
    }
}
