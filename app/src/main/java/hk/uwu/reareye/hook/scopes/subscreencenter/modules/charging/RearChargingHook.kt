package hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging

import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputEvent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle

class RearChargingHook : RoxyHooker() {
    companion object {
        internal var interruptForNotification: (() -> Unit)? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var displays: DisplayManager? = null
    private var receiver: BroadcastReceiver? = null
    private var presentation: Presentation? = null
    private var liquid: RearChargingLiquidView? = null
    private var plugged = false
    private var level = -1
    private var status = BatteryManager.BATTERY_STATUS_UNKNOWN
    private var deadline = 0L
    private var suppressed = false
    private var enabled: () -> Boolean = { false }
    private var keepVisible: () -> Boolean = { false }
    private val timeout = Runnable { if (!keepVisible()) dismiss() }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) { dismiss(); refresh() }
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            enabled = { hookPrefs.getBoolean(ConfigKeys.REAR_LIQUID_CHARGING, false) }
            keepVisible = { hookPrefs.getBoolean(ConfigKeys.REAR_LIQUID_CHARGING_PERSISTENT, false) }
            lifecycle {
                onAttach(replay = true) {
                    val ctx = hookAppContext ?: this.context
                    main.post { attach(ctx.applicationContext ?: ctx) }
                }
            }
        }
    }

    private fun attach(ctx: Context) {
        if (context != null) return
        context = ctx
        interruptForNotification = { suppressed = true; deadline = 0L; dismiss() }
        displays = ctx.getSystemService(DisplayManager::class.java)
        displays?.registerDisplayListener(displayListener, main)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        level = if (raw >= 0 && scale > 0) ((raw.toLong() * 100L) / scale).toInt().coerceIn(0, 100) else -1
                        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                        status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        plugged = true
                        suppressed = false
                        if (enabled()) {
                            deadline = SystemClock.uptimeMillis() + 8_000L
                            rearDisplay()?.let(::wakeRearDisplay)
                            main.removeCallbacks(timeout)
                            main.postDelayed(timeout, 8_000L)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> { plugged = false; deadline = 0L }
                }
                refresh()
            }
        }
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun rearDisplay(): Display? = displays?.displays?.firstOrNull {
        it.displayId != Display.DEFAULT_DISPLAY && runCatching {
            val internal = Display::class.java.getDeclaredField("TYPE_INTERNAL").getInt(null)
            Display::class.java.getMethod("getType").invoke(it) == internal
        }.getOrDefault(false)
    }

    private fun refresh() {
        val ctx = context ?: return
        val display = rearDisplay()
        val requested = keepVisible() || SystemClock.uptimeMillis() < deadline
        if (!enabled() || suppressed || !plugged || level < 0 || !requested || display?.state != Display.STATE_ON) {
            dismiss()
            return
        }
        if (presentation?.display?.displayId != display.displayId) dismiss()
        val label = when (status) {
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_CHARGING -> "正在充电"
            else -> "已连接电源 · 暂停充电"
        }
        if (presentation == null) {
            runCatching {
                val dialog = Presentation(ctx, display)
                val view = RearChargingLiquidView(dialog.context)
                dialog.setContentView(view)
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
                dialog.window?.setDecorFitsSystemWindows(false)
                dialog.window?.let { window ->
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                }
                view.setOnClickListener { suppressed = true; deadline = 0L; dismiss() }
                presentation = dialog
                liquid = view
                dialog.show()
                dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                view.updateBattery(level, label, animateEntry = true)
            }.onFailure {
                dismiss()
                YLog.warn("Rear liquid charging window unavailable: ${it.javaClass.simpleName}")
            }
        } else liquid?.updateBattery(level, label)
    }

    private fun wakeRearDisplay(display: Display) {
        // Target only the rear display; never wake the main display or keep a wake loop.
        runCatching {
            val managerClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val inject = managerClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            val now = SystemClock.uptimeMillis()
            for (action in arrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, now, action, KeyEvent.KEYCODE_WAKEUP, 0)
                event.source = InputDevice.SOURCE_KEYBOARD
                setDisplay.invoke(event, display.displayId)
                inject.invoke(manager, event, 0)
            }
        }.onFailure { YLog.warn("Rear charge wake unavailable: ${it.javaClass.simpleName}") }
    }

    private fun dismiss() {
        liquid = null
        runCatching { presentation?.dismiss() }
        presentation = null
    }

    override fun onHotReloadQuiesce() {
        val done = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                main.removeCallbacksAndMessages(null)
                dismiss()
                displays?.unregisterDisplayListener(displayListener)
                receiver?.let { context?.unregisterReceiver(it) }
                receiver = null
                displays = null
                context = null
                interruptForNotification = null
                enabled = { false }
                keepVisible = { false }
            } finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else { main.post(cleanup); check(done.await(3, TimeUnit.SECONDS)) { "Charge cleanup timed out" } }
    }
}
