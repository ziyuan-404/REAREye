package hk.uwu.reareye.hook.scopes.system.modules

import android.view.MotionEvent
import android.content.pm.ActivityInfo
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class DisableSubScreenDoubleTapSleepHook : RoxyHooker() {
    @Volatile
    private var focusedPackageName: String? = null
    @Volatile private var rearFocusedWindow=java.lang.ref.WeakReference<Any>(null)

    override fun PackageScope.onHook() {
        loadSystem {
            val clz =
                "com.miui.server.input.gesture.multifingergesture.gesture.MiuiSubscreenDoubleTapGesture"
                    .toClass()
                    .resolve()
            val managerRef =
                "com.miui.server.input.gesture.multifingergesture.MiuiSubScreenMultiFingerGestureManager"
                    .toClass()
                    .resolve()

            managerRef.firstMethod {
                name = "onFocusedWindowChanged"
                parameterCount = 3
            }.hook {
                id="hrs.rear.app.sleep.focus"
                hotReloadPolicy = HotReloadPolicy.REINSTALL
                after {
                    if(args(0).int()==1) {
                        rearFocusedWindow=java.lang.ref.WeakReference(args(2).value)
                        focusedPackageName = args(2).value.owningPackage()
                    }
                }
            }

            clz.firstMethod {
                name = "onPointerEvent"
                returnType = Void.TYPE
                parameters(MotionEvent::class.java)
            }.hook {
                id="hrs.rear.app.sleep.suppression"
                hotReloadPolicy = HotReloadPolicy.REINSTALL
                before {
                    val whitelist = hookPrefs.getStringSet(
                        ConfigKeys.SUBSCREEN_DOUBLE_TAP_SLEEP_DISABLED_APPS,
                    )
                    val focused=rearFocusedWindow.get()?:managerRef.firstMethod {
                        name = "getFocusedWindow"
                    }.invoke()
                    val packageName=focused?.owningPackage()?:focusedPackageName
                    val info=focused?.let { Server.field(it,"mActivityRecord") }?.let { Server.field(it,"info") } as? ActivityInfo
                    val rearApp=hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)&&
                        focused?.let { Server.displayId(it)==1 }==true&&info!=null&&info.applicationInfo.uid%100000>=10000&&
                        info.packageName !in setOf("com.xiaomi.subscreencenter","com.android.systemui","com.miui.aod","com.miui.miwallpaper")
                    if (rearApp || packageName != null && packageName in whitelist) {
                        if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                            YLog.debug("Rejected subscreen double tap sleep gesture package=$packageName")
                        }
                        result=null
                    }
                }
            }
            YLog.info("Rear app double-tap sleep guard installed")
        }
    }

    private fun Any?.owningPackage(): String? {
        return runCatching {
            this?.asResolver()?.firstMethod {
                name = "getOwningPackage"
                returnType = String::class.java
            }?.invoke() as? String
        }.getOrNull()
    }
}
