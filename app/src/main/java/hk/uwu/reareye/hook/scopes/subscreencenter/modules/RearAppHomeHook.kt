package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.app.Activity
import android.content.Intent
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.HotReloadPolicy
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

/** Opens the actual stock app-card picker when navigation returns from a rear app. */
@OptIn(DexKitExperimentalApi::class)
class RearAppHomeHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            var active = true
            runtime.onClose { active = false }
            runCatching {
                val bridge = runtime.manage(createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = resolveHookPackageVersionCode(
                        hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir,
                    ),
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                ))
                val finishPoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_APP_HOME_FINISH") {
                    findMethod { matcher {
                        paramCount(0)
                        returnType = "void"
                        usingStrings("onFinishGesture: mLauncherContainer.getTranslationY() = ")
                    } }.singleOrNull()
                } ?: error("Native app picker completion method missing")
                val panelClass = finishPoint.className.toClass()
                val closePoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_APP_HOME_CLOSE_CARD") {
                    findMethod { matcher {
                        declaredClass = finishPoint.className
                        paramTypes(Boolean::class.java)
                        returnType = "void"
                        usingStrings("closeAppCard: mActivated = ")
                    } }.singleOrNull()
                }
                val closeCard = closePoint?.let {
                    panelClass.getDeclaredMethod(it.methodName, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                }
                val showPoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_APP_HOME_NATIVE_SHOW") {
                    findMethod { matcher {
                        paramTypes(Boolean::class.java)
                        returnType = "boolean"
                        usingStrings("State is same, show = ", "State is not same, old = ")
                    } }.singleOrNull()
                } ?: error("Native launcher show/hide gesture entry missing")
                val gestureClass = showPoint.className.toClass()
                val show = gestureClass.getDeclaredMethod(showPoint.methodName, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                val controllerName = resolveDexKitClassValue(bridge, "SSC_REAR_APP_HOME_CONTROLLER") {
                    findClass { matcher { usingStrings(
                        "LauncherController", "isChangBaAppSupportRearScreen support = ",
                    ) } }.singleOrNull()
                } ?: error("Native launcher controller missing")
                val controllerClass = controllerName.toClass()
                val gestureField = controllerClass.declaredFields.single { gestureClass.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }
                val launcher = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass()
                val field = launcher.declaredFields.single { panelClass.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }
                val controllerField = launcher.declaredFields.single { controllerClass.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }
                val currentLauncher="com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().declaredMethods.single {
                    java.lang.reflect.Modifier.isStatic(it.modifiers)&&it.parameterCount==0&&it.returnType==launcher
                }.apply { isAccessible=true }
                var registerAppHomeGesture:(()->Unit)?=null
                val assistant="com.xiaomi.subscreencenter.SmartAssistantPanel".toClass()
                val assistantField=launcher.declaredFields.single { assistant.isAssignableFrom(it.type) }.apply { isAccessible=true }
                val showAssistant=assistant.getMethod("setAssistantVisibleImpl",Boolean::class.javaPrimitiveType)
                var restoreNotificationOnFocus=false
                fun showNotificationOrigin(activity:Activity) {
                    assistantField.get(activity)?.let { showAssistant.invoke(it,true) }
                }
                val restoreOrigin:(Int)->Unit={ kind ->
                    runCatching {
                        val activity=currentLauncher.invoke(null) as? Activity
                        if(active&&kind==RearLaunchOrigin.PICKER&&activity!=null&&!activity.isDestroyed) {
                            val controller=controllerField.get(activity)
                            val gesture=controller?.let { gestureField.get(it) }
                            if(gesture!=null)show.invoke(gesture,true)
                        } else if(active&&kind==RearLaunchOrigin.NOTIFICATION&&activity!=null&&!activity.isDestroyed) {
                            restoreNotificationOnFocus=true
                            showNotificationOrigin(activity)
                        }
                    }.onFailure { YLog.warn("Restoring native rear launch source failed",it) }
                }
                NativeRearAppLaunch.onRestoreOrigin=restoreOrigin
                runtime.onClose { if(NativeRearAppLaunch.onRestoreOrigin===restoreOrigin)NativeRearAppLaunch.onRestoreOrigin=null }
                val closeAfterLaunch:()->Unit={
                    runCatching {
                        val activity=currentLauncher.invoke(null) as? Activity
                        if(active&&activity!=null&&!activity.isDestroyed) {
                            // The source has already been captured by Shell. Hide the
                            // picker now so returning reveals a clean rear home surface.
                            val controller=controllerField.get(activity)
                            val gesture=controller?.let { gestureField.get(it) }
                            if(gesture!=null)show.invoke(gesture,false)
                            registerAppHomeGesture?.invoke()
                        }
                    }.onFailure { YLog.warn("Closing native rear picker after app launch failed",it) }
                }
                NativeRearAppLaunch.onLaunched=closeAfterLaunch
                runtime.onClose { if(NativeRearAppLaunch.onLaunched===closeAfterLaunch)NativeRearAppLaunch.onLaunched=null }
                val taskManager="android.app.ActivityTaskManager".toClass().getDeclaredMethod("getService").invoke(null)
                val bringHome=taskManager.javaClass.getMethod("startActivityFromRecents",Int::class.javaPrimitiveType,android.os.Bundle::class.java)
                val returnHome=Runnable {
                    if(active&&hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)) {
                        NativeRearAppLaunch.restoreOriginIfNeeded()
                        val activity=currentLauncher.invoke(null) as? Activity
                        if(activity!=null)bringHome.invoke(taskManager,activity.taskId,
                            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(1).toBundle())
                    }
                }
                // The stock recents animation excludes freeform tasks and waits for a
                // callback that never arrives. Its built-in Home action recognizer
                // already supports callbacks for non-recents states; use that path
                // while an app is open, then restore normal launcher gestures.
                val managerName=resolveDexKitClassValue(bridge,"SSC_REAR_APP_GESTURE_MANAGER") {
                    findClass { matcher { usingStrings("SubScreenGestureManager","Try enable default back ") } }.singleOrNull()
                }?:error("Stock rear gesture manager missing")
                val registerPoint=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_HOME_REGISTER") {
                    findMethod { matcher {
                        declaredClass=managerName;paramTypes("java.lang.Runnable","int");returnType="void"
                        usingNumbers(100,103)
                    } }.singleOrNull()
                }?:error("Stock rear Home action registration missing")
                val unregisterPoint=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_HOME_UNREGISTER") {
                    findMethod { matcher {
                        declaredClass=managerName;paramTypes("int");returnType="void"
                        usingNumbers(100,103)
                    } }.singleOrNull()
                }?:error("Stock rear Home action removal missing")
                val managerClass=managerName.toClass()
                val appClass="com.xiaomi.subscreencenter.SubScreenCenterApp".toClass()
                val singleton=appClass.declaredFields.single { java.lang.reflect.Modifier.isStatic(it.modifiers)&&it.type==appClass }.apply { isAccessible=true }
                val managerField=appClass.declaredFields.single { it.type==managerClass }.apply { isAccessible=true }
                val register=managerClass.getDeclaredMethod(registerPoint.methodName,Runnable::class.java,Int::class.javaPrimitiveType)
                val unregister=managerClass.getDeclaredMethod(unregisterPoint.methodName,Int::class.javaPrimitiveType)
                val backRegisterPoint=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_BACK_REGISTER") {
                    findMethod { matcher { declaredClass=managerName;paramTypes("java.lang.Runnable","int");returnType="void";usingNumbers(9) } }.singleOrNull()
                }?:error("Stock rear Back action registration missing")
                val backUnregisterPoint=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_BACK_UNREGISTER") {
                    findMethod { matcher { declaredClass=managerName;paramTypes("int");returnType="void";usingNumbers(9) } }.singleOrNull()
                }?:error("Stock rear Back action removal missing")
                val registerBack=managerClass.getDeclaredMethod(backRegisterPoint.methodName,Runnable::class.java,Int::class.javaPrimitiveType)
                val unregisterBack=managerClass.getDeclaredMethod(backUnregisterPoint.methodName,Int::class.javaPrimitiveType)
                var registeredManager:Any?=null
                var registeredBack=false
                fun clearAppHomeGesture() {
                    registeredManager?.let {
                        unregister.invoke(it,100)
                        if(registeredBack)unregisterBack.invoke(it,1)
                    }
                    registeredBack=false;registeredManager=null
                }
                registerAppHomeGesture={
                    if(registeredManager==null)managerField.get(singleton.get(null))?.let {
                        register.invoke(it,returnHome,100);registeredManager=it
                        // A paused native panel may retain a default-back suppression
                        // token. Register its existing Back action as an app action;
                        // the factory recognizer and key dispatch remain unchanged.
                        val backMap=managerClass.declaredFields.filter { field->java.util.SortedMap::class.java.isAssignableFrom(field.type) }
                            .firstNotNullOfOrNull { field->
                                field.isAccessible=true
                                (field.get(it) as? Map<*,*>)?.takeIf { map->map[0] is Runnable }
                            }
                        val nativeBack=backMap?.get(0) as? Runnable
                        if(nativeBack!=null&&backMap[1]==null) {
                            registerBack.invoke(it,Runnable { nativeBack.run() },1);registeredBack=true
                        }
                    }
                }
                fun rearAppForeground(activity:Activity):Boolean {
                    @Suppress("DEPRECATION")
                    val top=activity.getSystemService(android.app.ActivityManager::class.java).getRunningTasks(30)
                        .firstOrNull { hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter.field(it,"displayId")==1 }
                    // Parked native freeforms may still report visible during their
                    // transition. Only the top rear task owns navigation; never
                    // let a background app capture drags inside the stock drawer.
                    return top?.isVisible==true&&top.topActivity?.packageName!="com.xiaomi.subscreencenter"
                }
                fun syncNavigation(activity:Activity) {
                    if(!active||!hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false))return
                    if(rearAppForeground(activity))registerAppHomeGesture?.invoke()
                    else clearAppHomeGesture()
                }
                launcher.resolve().firstMethod { name="onWindowFocusChanged";parameters(Boolean::class.javaPrimitiveType!!) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        if(args(0).boolean()) {
                            val activity=instance as? Activity
                            if(activity!=null&&rearAppForeground(activity)) { syncNavigation(activity);return@after }
                            clearAppHomeGesture();NativeRearAppLaunch.restoreOriginIfNeeded()
                            if(restoreNotificationOnFocus) {
                                restoreNotificationOnFocus=false
                                (instance as? Activity)?.let(::showNotificationOrigin)
                            }
                        } else (instance as? Activity)?.let { activity->activity.window.decorView.post { syncNavigation(activity) } }
                    }
                }
                // Adopt apps already in front after a module/launcher reload.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    (currentLauncher.invoke(null) as? Activity)?.let(::syncNavigation)
                }
                runtime.onClose { clearAppHomeGesture() }
                val gestureFinish=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_GESTURE_FINISH") {
                    findMethod { matcher {
                        paramTypes(Boolean::class.java,Boolean::class.java);returnType="void"
                        usingStrings("finish: toHome-sendUserLeaveHint : ")
                    } }.singleOrNull()
                }?:error("Native rear Home gesture completion missing")
                gestureFinish.className.toClass().resolve().firstMethod {
                    name=gestureFinish.methodName;parameters(Boolean::class.javaPrimitiveType!!,Boolean::class.javaPrimitiveType!!)
                }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        if(active&&args(0).boolean()&&hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)) {
                            returnHome.run()
                        }
                    }
                }

                fun open(activity: Activity, request: Intent?) {
                    if (!active || !hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, false) ||
                        request?.getBooleanExtra("hk.uwu.reareye.OPEN_REAR_HOME", false) != true ||
                        activity.display?.displayId != 1
                    ) return
                    // Consume once before posting: onNewIntent and onResume may both see the
                    // same request. Ordinary stock drags never carry this explicit navigation flag.
                    request.removeExtra("hk.uwu.reareye.OPEN_REAR_HOME")
                    activity.window.decorView.post {
                        runCatching {
                            if (!active || activity.isDestroyed || activity.isFinishing) return@runCatching
                            val panel = field.get(activity) as? View ?: return@runCatching
                            val controller = controllerField.get(activity) ?: return@runCatching
                            val gesture = gestureField.get(controller) ?: return@runCatching
                            // Use the same native close/show animation entry points as system
                            // navigation, leaving settling, layout and scroll offsets to the ROM.
                            closeCard?.invoke(panel, true)
                            show.invoke(gesture, true)
                            YLog.debug("Rear app requested native stock picker navigation")
                        }.onFailure { YLog.warn("Opening native rear app picker failed", it) }
                    }
                }
                launcher.resolve().firstMethod {
                    name = "onResume"
                    parameterCount = 0
                }.hook {
                    id = "reareye.rear.stock-home.resume"
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after { (instance as? Activity)?.let { open(it, it.intent) } }
                }
                Activity::class.java.resolve().firstMethod {
                    name = "onNewIntent"
                    parameters(Intent::class.java)
                }.hook {
                    id = "reareye.rear.stock-home.new-intent"
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after {
                        val activity = instance as? Activity ?: return@after
                        if (launcher.isInstance(activity)) open(activity, args(0).value as? Intent)
                    }
                }
                YLog.info("Native rear app picker home hook installed")
            }.onFailure { YLog.warn("Native rear app picker home unavailable", it) }
        }
    }
}
