package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.Toast
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import java.util.concurrent.atomic.AtomicBoolean

/** Preserve the OEM unlocked-device exemption; never substitute face detection. */
internal object RearAppFaceAuth {
    var authenticate: ((View, () -> Unit) -> Unit)? = null

    fun authorize(view: View, prefs: HookPrefs, launch: () -> Unit) {
        if (!prefs.isRemoteReady()) {
            Toast.makeText(view.context, "设置尚未连接，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        if (!prefs.getBoolean(ConfigKeys.REAR_APP_FACE_AUTH, false)) { launch(); return }
        val keyguard = view.context.getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard != null && (!keyguard.isDeviceSecure || !keyguard.isDeviceLocked)) {
            launch(); return
        }
        val native = authenticate
        if (native == null) {
            Toast.makeText(view.context, "原厂人脸验证不可用，未打开应用", Toast.LENGTH_LONG).show()
            return
        }
        native(view, launch)
    }
}

/** Reuse the installed ROM's complete helper, including enrollment check and TrustService. */
class RearAppFaceAuthHook : RoxyHooker() {
    @Volatile private var closeSession: (() -> Unit)? = null
    override fun onHotReloadQuiesce() { closeSession?.invoke() }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val main = Handler(Looper.getMainLooper())
            val callbacks = java.util.IdentityHashMap<Any, () -> Unit>()
            var helper: Any? = null
            var terminate: java.lang.reflect.Method? = null
            var success = false
            var acceptSuccess = false
            var active = true
            var ownsGate = false
            var gate: AtomicBoolean? = null
            fun releaseGate() {
                if (ownsGate) gate?.set(false)
                ownsGate = false
            }
            runCatching {
                // Names and constructor signatures checked against the installed ROM's DEX.
                val helperClass = "r2.i".toClass()
                gate = "U1.f".toClass().getDeclaredField("b").apply { isAccessible = true }.get(null) as AtomicBoolean
                val callbackClass = "q2.g".toClass()
                val nativeCallbacks = helperClass.declaredFields.filter { it.type == callbackClass }
                    .onEach { it.isAccessible = true }
                check(nativeCallbacks.size == 2) { "Unsupported native face callback layout" }
                val callbackConstructor = callbackClass.getConstructor(
                    "q2.k".toClass(), AtomicBoolean::class.java, Int::class.javaPrimitiveType,
                )
                val starter = "U1.a0".toClass().getConstructor(
                    Any::class.java, Any::class.java, Any::class.java, Int::class.javaPrimitiveType,
                )
                terminate = helperClass.getMethod("g", String::class.java, Boolean::class.javaPrimitiveType)
                helperClass.resolve().firstMethod {
                    name = "g"; parameters(String::class.java, Boolean::class.javaPrimitiveType!!)
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        if (instance === helper) acceptSuccess = args(0).value == "success"
                    }
                }
                callbackClass.resolve().firstMethod { name = "run"; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        val callback = callbacks.remove(instance) ?: return@before
                        result = null
                        callback()
                    }
                }
                val authenticate: (View, () -> Unit) -> Unit = authenticate@ { view, launch ->
                    if (!active || helper != null) return@authenticate
                    if (view.display?.displayId != 1 || !view.isAttachedToWindow) return@authenticate
                    if (gate?.compareAndSet(false, true) != true) {
                        Toast.makeText(view.context, "人脸验证正在进行，请稍后重试", Toast.LENGTH_SHORT).show()
                        return@authenticate
                    }
                    ownsGate = true
                    success = false; acceptSuccess = false
                    runCatching {
                        val current = helperClass.getConstructor().newInstance()
                        helper = current
                        val succeeded = callbackConstructor.newInstance(null, AtomicBoolean(false), 0)
                        val finished = callbackConstructor.newInstance(null, AtomicBoolean(false), 1)
                        callbacks[succeeded] = { success = true }
                        callbacks[finished] = {
                            val authorized = success && acceptSuccess
                            helper = null
                            callbacks.clear()
                            releaseGate()
                            // The native helper has removed the overlay and released the camera.
                            // A screen-off, Back, Home, timeout, or hot reload cannot launch later.
                            if (authorized && active && view.isAttachedToWindow &&
                                view.display?.state == Display.STATE_ON) launch()
                        }
                        (starter.newInstance(current, succeeded, finished, 14) as Runnable).run()
                    }.onFailure {
                        helper?.let { current -> runCatching { terminate?.invoke(current, "module_error", true) } }
                        helper = null; callbacks.clear()
                        releaseGate()
                        YLog.warn("Native rear face authentication failed", it)
                        Toast.makeText(view.context, "人脸验证启动失败，未打开应用", Toast.LENGTH_LONG).show()
                    }
                }
                RearAppFaceAuth.authenticate = authenticate
                val close: () -> Unit = {
                    active = false
                    if (RearAppFaceAuth.authenticate === authenticate) RearAppFaceAuth.authenticate = null
                    // Detach module-owned callbacks before Roxy removes their hooks.
                    // Native teardown may finish asynchronously after this generation closes.
                    val current = helper
                    current?.let { value -> nativeCallbacks.forEach { it.set(value, null) } }
                    main.post {
                        current?.let { runCatching { terminate?.invoke(it, "module_closed", true) } }
                        helper = null; callbacks.clear()
                        releaseGate()
                    }
                }
                closeSession = close
                runtime.onClose { close(); closeSession = null }
            }.onFailure { YLog.warn("Native rear face authentication unavailable; protected launches stay blocked", it) }
        }
    }
}
