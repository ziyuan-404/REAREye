package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Pair
import android.view.View
import android.widget.RemoteViews
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import org.json.JSONArray

/** Shared with notification cards: retain the framework's native launch animation and grants. */
internal object NativeRearAppLaunch {
    private val notificationOrigins=java.util.WeakHashMap<View,java.lang.ref.WeakReference<View>>()
    fun registerNotificationOrigin(root:View,icon:View) {
        notificationOrigins[root]=java.lang.ref.WeakReference(icon)
    }
    var onLaunched:(()->Unit)?=null
    var onRestoreOrigin:((Int)->Unit)?=null
    private var returnKind=RearLaunchOrigin.PICKER
    private var awaitingReturn=false
    fun restoreOriginIfNeeded() {
        if(!awaitingReturn)return
        awaitingReturn=false
        onRestoreOrigin?.invoke(returnKind)
    }
    fun canOpenNotification(sbn:android.service.notification.StatusBarNotification,prefs:HookPrefs,context:android.content.Context):Boolean {
        if(!prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)||sbn.packageName !in prefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS))return false
        return sbn.notification.contentIntent?.isActivity==true||
            sbn.user==android.os.Process.myUserHandle()&&context.packageManager.getLaunchIntentForPackage(sbn.packageName)!=null
    }
    fun openNotification(view:View,sbn:android.service.notification.StatusBarNotification,prefs:HookPrefs) {
        check(canOpenNotification(sbn,prefs,view.context)) { "Notification app is not enabled for rear launch" }
        val pending=sbn.notification.contentIntent
        if(pending?.isActivity==true)open(view,pending,prefs,sbn.packageName,RearLaunchOrigin.NOTIFICATION)
        else open(view,requireNotNull(view.context.packageManager.getLaunchIntentForPackage(sbn.packageName)),prefs,RearLaunchOrigin.NOTIFICATION)
    }
    fun open(view: View,intent: Intent,prefs: HookPrefs,kind:Int=RearLaunchOrigin.PICKER) {
        val pending=PendingIntent.getActivity(view.context,intent.component.hashCode(),intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        open(view,pending,prefs,intent.component?.packageName,kind)
    }
    fun open(view: View,pending: PendingIntent,prefs: HookPrefs,targetPackage:String?=pending.creatorPackage,kind:Int=RearLaunchOrigin.PICKER) {
        RearAppFaceAuth.authorize(view,prefs) {
            runCatching { openAuthenticated(view,pending,prefs,targetPackage,kind) }
                .onFailure { hk.uwu.reareye.hook.support.YLog.warn("Authenticated rear launch failed",it) }
        }
    }
    private fun openAuthenticated(view: View,pending: PendingIntent,prefs: HookPrefs,targetPackage:String?,kind:Int) {
        val responseClass=Class.forName("android.widget.RemoteViews\$RemoteResponse")
        val response=responseClass.getDeclaredMethod("fromPendingIntent",PendingIntent::class.java)
            .apply { isAccessible=true }.invoke(null,pending)
        @Suppress("UNCHECKED_CAST")
        val options=responseClass.getDeclaredMethod("getLaunchOptions",View::class.java)
            .apply { isAccessible=true }.invoke(response,view) as Pair<Intent,ActivityOptions>
        options.second.setLaunchDisplayId(1)
        var origin:RearLaunchOrigin?=null
        if(prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)) {
            val source=if(kind==RearLaunchOrigin.NOTIFICATION)
                notificationOrigins[view]?.get()?.takeIf { it.isAttachedToWindow }?:view
            else view
            origin=RearLaunchOrigin.capture(source,kind)
            origin?.let {
                ActivityOptions::class.java.getDeclaredMethod("setLaunchCookie",android.os.IBinder::class.java).invoke(options.second,it.cookie())
            }
            val display=view.context.getSystemService(DisplayManager::class.java).getDisplay(1)
            val size=android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val cutout=display.cutout
            val bounds=Rect(cutout?.safeInsetLeft?:0,cutout?.safeInsetTop?:0,
                size.x-(cutout?.safeInsetRight?:0),size.y-(cutout?.safeInsetBottom?:0))
            val raw=prefs.getString(ConfigKeys.REAR_APP_REGION,RearAppDefaults.REGION)
            if(raw.isNotBlank())runCatching {
                val region=JSONArray(raw)
                val custom=Rect((size.x*region.getDouble(0)).toInt(),(size.y*region.getDouble(1)).toInt(),
                    (size.x*region.getDouble(2)).toInt(),(size.y*region.getDouble(3)).toInt())
                if(!custom.isEmpty&&Rect(0,0,size.x,size.y).contains(custom))bounds.set(custom)
            }
            val launch=options.second
            val rearMetrics=android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            view.context.getSystemService(DisplayManager::class.java).getDisplay(1).getRealMetrics(rearMetrics)
            val primaryMetrics=android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            view.context.getSystemService(DisplayManager::class.java).getDisplay(0).getRealMetrics(primaryMetrics)
            val rearBaseDpi=rearMetrics.densityDpi.takeIf { it>0 }?:480
            val primaryBaseDpi=primaryMetrics.densityDpi.takeIf { it>0 }?:rearBaseDpi
            val scale=RearAppDefaults.contentScale(prefs.getInt(ConfigKeys.REAR_APP_DPI,RearAppDefaults.APP_DPI),primaryBaseDpi)
            val logical=Rect(bounds.left,bounds.top,bounds.left+kotlin.math.round(bounds.width()/scale).toInt(),bounds.top+kotlin.math.round(bounds.height()/scale).toInt())
            ActivityOptions::class.java.getDeclaredMethod("setLaunchWindowingMode",Int::class.javaPrimitiveType)
                .invoke(launch,5)
            launch.setLaunchBounds(logical)
            val injector=ActivityOptions::class.java.getDeclaredMethod("getActivityOptionsInjector").invoke(launch)
            injector.javaClass.getMethod("setFreeformScale",Float::class.javaPrimitiveType).invoke(injector,scale)
            injector.javaClass.getMethod("setNormalFreeForm",Boolean::class.javaPrimitiveType).invoke(injector,false)
            // Reuse the app's existing task, including its back stack and process, across displays.
            @Suppress("DEPRECATION")
            val existing=view.context.getSystemService(ActivityManager::class.java).getRunningTasks(100)
                .firstOrNull { task -> task.baseActivity?.packageName==targetPackage&&
                    runCatching { task.javaClass.getField("userId").getInt(task)==pending.creatorUid/100000 }.getOrDefault(false) }
            existing?.let {
                ActivityOptions::class.java.getDeclaredMethod("setLaunchTaskId",Int::class.javaPrimitiveType).invoke(launch,it.taskId)
            }
        }
        check(RemoteViews::class.java.getDeclaredMethod("startPendingIntent",View::class.java,PendingIntent::class.java,Pair::class.java)
            .apply { isAccessible=true }.invoke(null,view,pending,options)==true) { "Native rear launch rejected" }
        if(prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)) {
            origin?.let { returnKind=it.kind;awaitingReturn=true }
            val callback=onLaunched
            if(kind==RearLaunchOrigin.PICKER) {
                // Transfer focus is the native handoff point, including cold app
                // starts. A fixed delay could hide the source before Shell had a frame.
                val root=view.rootView
                if(!root.hasWindowFocus())root.postOnAnimation { callback?.invoke() }
                else {
                    val observer=root.viewTreeObserver
                    lateinit var listener:android.view.ViewTreeObserver.OnWindowFocusChangeListener
                    listener=android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused->
                        if(!focused) {
                            if(observer.isAlive)observer.removeOnWindowFocusChangeListener(listener)
                            root.postOnAnimation { callback?.invoke() }
                        }
                    }
                    observer.addOnWindowFocusChangeListener(listener)
                }
            } else view.postOnAnimation { view.postOnAnimation { callback?.invoke() } }
        }
    }
}
