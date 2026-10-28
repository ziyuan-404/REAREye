package hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.InputEvent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.reflect.Modifier
import com.highcapable.kavaref.KavaRef.Companion.resolve
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle

@OptIn(DexKitExperimentalApi::class)
class RearChargingHook : RoxyHooker() {
    companion object {
        internal var notificationAvailable: (() -> Unit)? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var displays: DisplayManager? = null
    private var receiver: BroadcastReceiver? = null
    private var launcher: Activity? = null
    private var overlayHost: ViewGroup? = null
    private var findLauncher: () -> Activity? = { null }
    private var liquid: RearChargingLiquidView? = null
    @Volatile private var plugged = false
    private var level = -1
    private var status = BatteryManager.BATTERY_STATUS_UNKNOWN
    @Volatile private var deadline = 0L
    @Volatile private var suppressed = false
    private var enabled: () -> Boolean = { false }
    private var keepVisible: () -> Boolean = { false }
    @Volatile private var nativeNotificationManager: Any? = null
    private var reloadNativeNotifications: ((Any) -> Unit)? = null
    private val deferredNotificationRefreshes = LinkedHashMap<Any, () -> Unit>()
    private val timeout = Runnable { finishEntryPriority() }
    private val awaitHost = Runnable { refresh() }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) { dismiss(); refresh() }
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            enabled = { hookPrefs.getBoolean(ConfigKeys.REAR_LIQUID_CHARGING, false) }
            keepVisible = { hookPrefs.getBoolean(ConfigKeys.REAR_LIQUID_CHARGING_PERSISTENT, false) }
            val launcherClass = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass()
            val appClass = "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass()
            val getter = appClass.declaredMethods.singleOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == launcherClass
            }?.apply { isAccessible = true }
            findLauncher = { runCatching { getter?.invoke(null) as? Activity }.getOrNull() }
            listOf("onCreate" to 1, "onResume" to 0).forEach { (method, count) ->
                launcherClass.resolve().firstMethod { name = method; parameterCount = count }.hook {
                    after { (instance as? Activity)?.let { host -> main.post { adoptLauncher(host); refresh() } } }
                }
            }
            launcherClass.resolve().firstMethod { name = "onDestroy"; parameterCount = 0 }.hook {
                before { if (instance === launcher) { dismiss(); launcher = null } }
            }
            installNotificationPriority()
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
        YLog.info("[REAREye-Charging] attached enabled=${enabled()} persistent=${keepVisible()}")
        notificationAvailable = {
            // The finite entry has priority over notifications. A persistent idle
            // charge view yields after that entry, like any lower-priority content.
            if (!ownsEntryPriority() && keepVisible()) { suppressed = true; dismiss() }
        }
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
                        YLog.info("[REAREye-Charging] power connected enabled=${enabled()}")
                        if (enabled()) {
                            deadline = SystemClock.uptimeMillis() + 8_000L
                            nativeNotificationManager?.let(::deferNativeNotification)
                            YLog.info("[REAREye-Charging] priority acquired durationMs=8000")
                            rearDisplay()?.let(::wakeRearDisplay)
                            main.removeCallbacks(timeout)
                            main.postDelayed(timeout, 8_000L)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> { plugged = false; finishEntryPriority() }
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
        if (context == null) return
        val display = rearDisplay()
        val requested = keepVisible() || SystemClock.uptimeMillis() < deadline
        if (!enabled() || suppressed || !plugged || level < 0 || !requested || display?.state != Display.STATE_ON) {
            dismiss()
            return
        }
        val host = launcher?.takeUnless { it.isFinishing || it.isDestroyed } ?: findLauncher()?.also(::adoptLauncher)
        val root = host?.takeIf { it.display?.displayId == display.displayId }?.window?.decorView as? ViewGroup
        if (root == null || !root.isAttachedToWindow) {
            dismiss()
            main.removeCallbacks(awaitHost)
            if (requested) main.postDelayed(awaitHost, 150L)
            return
        }
        if (overlayHost !== root) dismiss()
        val label = when (status) {
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_CHARGING -> "正在充电"
            else -> "已连接电源 · 暂停充电"
        }
        if (liquid == null) {
            runCatching {
                // Use the existing rear launcher window. Android 17 can reject a new
                // Presentation window on this internal secondary display after wake.
                val view = RearChargingLiquidView(root.context)
                view.elevation = 32_000f
                view.keepScreenOn = ownsEntryPriority()
                view.setOnClickListener { suppressed = true; finishEntryPriority() }
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                overlayHost = root
                liquid = view
                view.updateBattery(level, label, animateEntry = true)
                YLog.info("[REAREye-Charging] shown level=$level hostDisplay=${host.display?.displayId}")
            }.onFailure {
                dismiss()
                YLog.warn("Rear liquid charging host unavailable: ${it.javaClass.simpleName}")
            }
        } else {
            liquid?.keepScreenOn = ownsEntryPriority()
            liquid?.updateBattery(level, label)
        }
    }

    private fun ownsEntryPriority(): Boolean = enabled() && plugged && !suppressed &&
        SystemClock.uptimeMillis() < deadline

    private fun deferNativeNotification(manager: Any): Boolean {
        val reload = reloadNativeNotifications ?: return false
        return synchronized(deferredNotificationRefreshes) {
            if (!ownsEntryPriority()) return@synchronized false
            deferredNotificationRefreshes[manager] = { reload(manager) }
            true
        }
    }

    private fun finishEntryPriority() {
        main.removeCallbacks(timeout)
        val pending = synchronized(deferredNotificationRefreshes) {
            deadline = 0L
            deferredNotificationRefreshes.values.toList().also { deferredNotificationRefreshes.clear() }
        }
        if (!keepVisible() || suppressed || pending.isNotEmpty()) {
            if (pending.isNotEmpty()) suppressed = true
            dismiss()
        } else liquid?.keepScreenOn = false
        YLog.info("[REAREye-Charging] priority released notificationRefreshes=${pending.size}")
        pending.forEach { reload -> main.post {
            runCatching(reload).onFailure { YLog.warn("Deferred rear notification refresh unavailable: ${it.javaClass.simpleName}") }
        } }
    }

    private fun PackageScope.installNotificationPriority() {
        runCatching {
            val bridge = runtime.manage(createDexKitCacheBridge(
                packageName = hookAppInfo.packageName,
                packageVersionCode = resolveHookPackageVersionCode(hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir),
                sourceDir = hookAppInfo.sourceDir,
                dataDir = hookAppInfo.dataDir,
            ))
            val show = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_CHARGE_PRIORITY_NOTIFICATION_SHOW") {
                findMethod { matcher { paramCount(1); returnType = "void"; usingStrings("post notification: ") } }.singleOrNull()
            })
            val reload = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_CHARGE_PRIORITY_NOTIFICATION_RELOAD") {
                findMethod { matcher { declaredClass = show.className; paramCount(0); returnType = "void";
                    usingStrings("load keyguard notifications cursor is null") } }.singleOrNull()
            })
            val reloadMethod = reload.className.toClass().getDeclaredMethod(reload.methodName).apply { isAccessible = true }
            reloadNativeNotifications = { manager -> reloadMethod.invoke(manager) }
            show.className.toClass().resolve().firstMethod { name = show.methodName; parameterCount = 1 }.hook {
                before {
                    val manager = instance ?: return@before
                    nativeNotificationManager = manager
                    if (deferNativeNotification(manager)) {
                        result = null
                    } else if (keepVisible() && liquid != null) {
                        main.post { suppressed = true; dismiss() }
                    }
                }
            }
            YLog.info("[REAREye-Charging] notification priority installed")
        }.onFailure { YLog.warn("Rear notification priority unavailable: ${it.javaClass.simpleName}") }
    }

    private fun adoptLauncher(host: Activity) {
        if (host.display?.displayId == Display.DEFAULT_DISPLAY) return
        if (launcher !== host) dismiss()
        launcher = host
    }

    private fun wakeRearDisplay(display: Display) {
        // Target only the rear display; never wake the main display or keep a wake loop.
        // HyperOS' own rear launcher uses this display-aware PowerManager overload.
        // An injected KEYCODE_WAKEUP alone may leave the rear in DOZE on Android 17.
        val nativeWake = runCatching {
            val power = context?.getSystemService(PowerManager::class.java) ?: return@runCatching false
            PowerManager::class.java.getMethod(
                "wakeUp", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType,
            ).invoke(power, SystemClock.uptimeMillis(), 2, "REAREye:rearCharging", display.displayId)
            true
        }.getOrDefault(false)
        if (nativeWake) return
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
        main.removeCallbacks(awaitHost)
        val view = liquid
        val root = overlayHost
        liquid = null
        overlayHost = null
        if (view != null) {
            runCatching { root?.removeView(view) }
            YLog.info("[REAREye-Charging] dismissed")
        }
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
                launcher = null
                deadline = 0L
                synchronized(deferredNotificationRefreshes) { deferredNotificationRefreshes.clear() }
                nativeNotificationManager = null
                reloadNativeNotifications = null
                findLauncher = { null }
                notificationAvailable = null
                enabled = { false }
                keepVisible = { false }
            } finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else { main.post(cleanup); check(done.await(3, TimeUnit.SECONDS)) { "Charge cleanup timed out" } }
    }
}
