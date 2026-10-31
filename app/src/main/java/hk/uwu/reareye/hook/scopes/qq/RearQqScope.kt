package hk.uwu.reareye.hook.scopes.qq

import android.app.Application
import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.Display
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

/** QQ caches the primary-screen status-bar height even when its rear window has no bar. */
class RearQqScope : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadApp("com.tencent.mobileqq") {
            if(processName!="com.tencent.mobileqq")return@loadApp
            var active=true
            runtime.onClose { active=false }
            val rearDensityDispatch=ThreadLocal<Boolean>()
            val checkingDensity=ThreadLocal<Boolean>()
            var lastAppDisplay=-1
            var foreground=java.lang.ref.WeakReference<Activity>(null)
            val readingConfiguration=ThreadLocal<Boolean>()
            fun rememberActivity(value:Any?) { (value as? Activity)?.let { foreground=java.lang.ref.WeakReference(it) } }
            Activity::class.java.resolve().firstMethod { name="attach" }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after { rememberActivity(instance) }
            }
            Activity::class.java.resolve().firstMethod { name="onResume";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before { rememberActivity(instance) }
            }
            fun rearConfiguration():Configuration? {
                if(!active||readingConfiguration.get()==true)return null
                readingConfiguration.set(true)
                try {
                if(!hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false))return null
                foreground.get()?.let { activity ->
                    // A native freeform task has an Activity override while the process's
                    // Application configuration can remain on display 0.
                    return if(activity.display?.displayId==1)activity.baseContext.resources.configuration else null
                }
                return runCatching {
                    val thread="android.app.ActivityThread".toClass().getDeclaredMethod("currentActivityThread").invoke(null)
                    val records=Server.field(thread,"mActivities") as? Map<*,*>
                    records?.values?.firstNotNullOfOrNull { record ->
                        record?.let { Server.field(it,"activity") as? Activity }?.takeIf { it.display?.displayId==1 }
                    }?.let { activity ->
                        rememberActivity(activity)
                        return@runCatching activity.baseContext.resources.configuration
                    }
                    val controller=Server.field(thread,"mConfigurationController")?:return@runCatching null
                    val config=Server.call(controller,"getConfiguration") as? Configuration?:return@runCatching null
                    val extra=Server.field(config,"extraConfig")?:return@runCatching null
                    config.takeIf { Server.field(extra,"mDisplayId")==1 }
                }.getOrNull()
                } finally { readingConfiguration.remove() }
            }
            fun adjustMetrics(metrics:DisplayMetrics,config:Configuration) {
                val dpi=config.densityDpi
                if(dpi !in 120..960)return
                val window=Server.field(config,"windowConfiguration")
                val bounds=window?.let { Server.call(it,"getBounds") } as? Rect
                metrics.densityDpi=dpi;metrics.density=dpi/160f
                @Suppress("DEPRECATION")
                metrics.scaledDensity=metrics.density*config.fontScale
                bounds?.takeUnless { it.isEmpty }?.let {
                    metrics.widthPixels=it.width();metrics.heightPixels=it.height()
                }
            }
            // QQ also keeps Application/default-display metrics. These belong to this
            // process only; adapting them avoids retaining the primary-screen scale.
            Resources::class.java.resolve().firstMethod { name="getDisplayMetrics";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    val config=rearConfiguration()?:return@after
                    val original=result<DisplayMetrics>()?:return@after
                    val copy=DisplayMetrics().apply { setTo(original) }
                    adjustMetrics(copy,config);result=copy
                }
            }
            listOf("getMetrics","getRealMetrics").forEach { methodName ->
                Display::class.java.resolve().firstMethod { name=methodName;parameters(DisplayMetrics::class.java) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        val config=rearConfiguration()?:return@after
                        (args(0).value as? DisplayMetrics)?.let { adjustMetrics(it,config) }
                    }
                }
            }
            val installed=HashSet<String>()
            fun installGetters() {
                if("density-dispatch" !in installed)runCatching {
                    val application="com.tencent.common.app.BaseApplicationImpl".toClass()
                    val manager="com.tencent.mobileqq.app.utils.GlobalDisplayMetricsManager".toClass()
                    val checks=manager.declaredMethods.filter { it.name=="checkChangeOfSysDensity"&&it.returnType==Void.TYPE }
                    val exits=manager.declaredMethods.filter { it.name=="killProcess"&&it.returnType==Void.TYPE }
                    check(checks.isNotEmpty()&&exits.isNotEmpty())
                    application.resolve().firstMethod { name="onConfigurationChanged";parameters(Configuration::class.java) }.hook {
                        hotReloadPolicy=HotReloadPolicy.REINSTALL
                        before {
                            val next=args(0).value as? Configuration?:return@before
                            val display=Server.field(next,"extraConfig")?.let { Server.field(it,"mDisplayId") } as? Int?:-1
                            rearDensityDispatch.set(active&&hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)&&(display==1||lastAppDisplay==1))
                            lastAppDisplay=display
                        }
                        after { rearDensityDispatch.remove() }
                    }
                    checks.forEach { check ->
                        manager.resolve().firstMethod { name=check.name;parameters(*check.parameterTypes) }.hook {
                            hotReloadPolicy=HotReloadPolicy.REINSTALL
                            before {
                                checkingDensity.set(rearDensityDispatch.get()==true)
                            }
                            after { checkingDensity.remove() }
                        }
                    }
                    exits.forEach { exit ->
                        manager.resolve().firstMethod { name=exit.name;parameters(*exit.parameterTypes) }.hook {
                            hotReloadPolicy=HotReloadPolicy.REINSTALL
                            before {
                                // Preserve QQ's metric refresh, skipping only its deliberate
                                // density-restart exit during a rear-window configuration update.
                                if(rearDensityDispatch.get()==true&&checkingDensity.get()==true)result=null
                            }
                        }
                    }
                    installed.add("density-dispatch")
                    if(rearConfiguration()!=null)lastAppDisplay=1
                    YLog.info("QQ rear density restart guard installed")
                }.onFailure { YLog.warn("QQ rear density restart guard unavailable",it) }
                listOf("com.tencent.widget.immersive.ImmersiveUtils",
                    "com.tencent.mobileqq.utils.DisplayUtils","com.tencent.mobileqq.utils.ViewUtils").forEach { className ->
                    if(className in installed)return@forEach
                    val type=runCatching { className.toClass() }.getOrNull()?:return@forEach
                    val methods=type.declaredMethods.filter {
                        it.name=="getStatusBarHeight"&&it.returnType==Int::class.javaPrimitiveType&&it.parameterCount<=1
                    }
                    methods.forEach { method ->
                        type.resolve().firstMethod { name=method.name;parameters(*method.parameterTypes) }.hook {
                            hotReloadPolicy=HotReloadPolicy.REINSTALL
                            before { if(rearConfiguration()!=null)result=0 }
                        }
                    }
                    if(methods.isNotEmpty()) {
                        installed.add(className)
                        YLog.info("Rear QQ status-bar height getter adapted: $className")
                    }
                }
            }
            // QQ may finish preparing its classloader in Application.attachBaseContext.
            Application::class.java.resolve().firstMethod { name="onCreate";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before { installGetters() }
            }
            installGetters()
        }
    }
}
