package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

/** Keep Xiaomi's native rounded task; omit its resize-guide controls on the rear display. */
class RearFreeformUiHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadApp("com.android.systemui") {
            val prefs=hookPrefs
            val context=hookSystemContext
            val rear=context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(1)
            val hardwareRadius=rear?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)?.radius?.toFloat()?:0f
            data class Corners(val enabled:Boolean,val rounded:Boolean,val radius:Float)
            fun corners()=Corners(prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false),
                prefs.getBoolean(ConfigKeys.REAR_APP_ROUNDED,true),prefs.getFloat(ConfigKeys.REAR_APP_CORNER_RADIUS,RearAppDefaults.CORNER_RADIUS))
            val config=java.util.concurrent.atomic.AtomicReference(corners())
            val observer=prefs.observe { config.set(corners()) }
            var active=true
            runtime.onClose { active=false;observer?.close() }
            val infoClass="com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo".toClass()
            // WMS updates an organized task's configuration, but its surface is
            // owned by Shell. A settings resize must update Shell's native Folme
            // target too; otherwise the keyboard moves while the app leash stays
            // at its old position. This is a one-time layout update, never a frame
            // loop and never a replacement for the system's launch animations.
            val oldBounds=ThreadLocal<android.graphics.Rect>()
            val base="com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingBaseTaskInfo".toClass()
            base.resolve().firstMethod { name="update";parameterCount=2;parameters(android.view.SurfaceControl::class.java,android.app.ActivityManager.RunningTaskInfo::class.java) }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    oldBounds.remove()
                    val info=instance?:return@before
                    if(active&&infoClass.isInstance(info))
                        (Server.call(info,"getTaskBounds") as? android.graphics.Rect)?.let { oldBounds.set(android.graphics.Rect(it)) }
                }
                after {
                    val previous=oldBounds.get();oldBounds.remove()
                    if(previous==null||!active||!config.get().enabled)return@after
                    val info=instance?:return@after
                    val task=args(1).value as? android.app.ActivityManager.RunningTaskInfo?:return@after
                    if(Server.field(task,"displayId")!=1||!task.isVisible||Server.call(task,"getWindowingMode")!=5)return@after
                    if(task.topActivity?.packageName in setOf("com.android.camera","com.kobbin.leicaflavor","xyz.shuncongci.leica","com.android.calendar"))return@after
                    val bounds=Server.call(info,"getTaskBounds") as? android.graphics.Rect?:return@after
                    if(bounds.isEmpty||bounds==previous||Server.call(info,"isInAnimating")==true)return@after
                    val stack=Server.field(task,"miuiFreeFormStackInfo")?:return@after
                    val scale=Server.field(stack,"freeFormScale") as? Float?:return@after
                    if(scale<=0f)return@after
                    val root="com.android.wm.shell.dagger.MultiTaskingControllerImpl".toClass().getMethod("getInstance").invoke(null)
                    val animation=Server.call(root,"getMiuiFreeformModeAnimation")?:return@after
                    animation.javaClass.getDeclaredMethod("resetFolmeToFinalTarget",infoClass,android.graphics.Rect::class.java,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                        .apply { isAccessible=true }.invoke(animation,info,bounds,scale,scale)
                    val leash=Server.call(info,"getLeash") as? android.view.SurfaceControl?:return@after
                    android.view.SurfaceControl.Transaction().use { tx->
                        tx.setPosition(leash,bounds.left.toFloat(),bounds.top.toFloat())
                            .setScale(leash,scale,scale)
                        tx.javaClass.getMethod("setWindowCrop",android.view.SurfaceControl::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                            .invoke(tx,leash,bounds.width(),bounds.height())
                        tx.apply()
                    }
                }
            }
            infoClass.declaredMethods.filter { it.name=="getCornerRadius" }.forEach { method ->
                infoClass.resolve().firstMethod { name=method.name;parameters(*method.parameterTypes) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        val current=config.get()
                        if(!current.enabled)return@after
                        val task=instance?.let { Server.call(it,"getTaskInfo") }?:return@after
                        if(Server.field(task,"displayId")!=1)return@after
                        result=if(!current.rounded)0f else current.radius.takeIf { it>0f }?:hardwareRadius.takeIf { it>0f }?:return@after
                    }
                }
            }
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchDecorViewModel".toClass().resolve().firstMethod {
                name="shouldShowWindowDecor";parameterCount=1
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    if(hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)&&args(0).value?.let { Server.field(it,"displayId") }==1)result=false
                }
            }
            hk.uwu.reareye.hook.support.YLog.info("Rear native corner policy installed: hardwareRadius=$hardwareRadius")
            val controller="com.android.wm.shell.multitasking.common.corner.MultiTaskingCornerTipAndStrokeController".toClass()
            controller.declaredMethods.filter { it.name in setOf("startGuideCornerAnim","startShowCornerAnim","startShowCornerAnimWithInit") }.forEach { method ->
                controller.resolve().firstMethod { name=method.name;parameters(*method.parameterTypes) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    before {
                        if(!hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false))return@before
                        val task=args(0).value?.let { Server.call(it,"getTaskInfo") }?:return@before
                        if(Server.field(task,"displayId")==1)result=null
                    }
                }
            }
        }
    }
}
