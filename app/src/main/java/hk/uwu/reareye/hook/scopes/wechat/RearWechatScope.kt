package hk.uwu.reareye.hook.scopes.wechat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ListView
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.HotReloadPolicy
import java.util.WeakHashMap

/** Collapse the duplicate taskbar header; let WeChat manage its own ActionBar. */
class RearWechatScope : RoxyHooker() {
    @Volatile private var quiescing=false
    override fun onHotReloadQuiesce() { quiescing=true }
    override fun PackageScope.onHook() {
        quiescing=false
        loadApp("com.tencent.mm") {
            val main=Handler(Looper.getMainLooper())
            val attached=WeakHashMap<Activity,ViewTreeObserver.OnPreDrawListener>()
            val adjusted=WeakHashMap<Activity,Pair<ListView,Int>>()
            var closed=false
            fun conversations(view: View): ListView? {
                if(view is ListView&&view.javaClass.name=="com.tencent.mm.ui.conversation.ConversationListView")return view
                if(view is ViewGroup)for(i in 0 until view.childCount)conversations(view.getChildAt(i))?.let { return it }
                return null
            }
            fun restore(activity: Activity) {
                adjusted.remove(activity)?.let { (list,amount)->if(amount>0)list.scrollListBy(-amount) }
            }
            fun update(activity: Activity) {
                val rear=!closed&&!quiescing&&hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)&&activity.display?.displayId==1
                if(!rear) { restore(activity);return }
                val decor=activity.window.decorView
                val barId=activity.resources.getIdentifier("ei","id","com.tencent.mm")
                val bar=decor.findViewById<View>(barId)?:return
                if(bar.javaClass.name!="androidx.appcompat.widget.ActionBarContainer"||bar.visibility!=View.VISIBLE||bar.height==0)return
                val list=conversations(decor)?:return
                val first=list.getChildAt(0)?:return
                if(first.javaClass.name!="com.tencent.mm.plugin.taskbar.ui.TaskBarContainer")return
                val upId=activity.resources.getIdentifier("actionbar_up_indicator","id","com.tencent.mm")
                val header=first.findViewById<View>(upId)?:return
                val top=IntArray(2);val origin=IntArray(2)
                header.getLocationOnScreen(top);list.getLocationOnScreen(origin)
                val amount=(top[1]-origin[1]).coerceIn(0,bar.height)
                if(amount>0) {
                    // WeChat can reset its header after resume or a display resize.
                    // Recheck before drawing instead of marking an early zero offset done.
                    adjusted[activity]=list to amount
                    list.scrollListBy(amount)
                    YLog.info("Rear WeChat duplicate taskbar header collapsed")
                }
            }
            fun attach(activity: Activity) {
                if(activity.javaClass.name!="com.tencent.mm.ui.LauncherUI")return
                if(!attached.containsKey(activity)) {
                    val listener=ViewTreeObserver.OnPreDrawListener { update(activity);true }
                    attached[activity]=listener
                    activity.window.decorView.viewTreeObserver.addOnPreDrawListener(listener)
                }
                main.post { update(activity) }
            }
            Activity::class.java.resolve().firstMethod { name="onResume";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after { (instance as? Activity)?.let(::attach) }
            }
            Activity::class.java.resolve().firstMethod { name="onDestroy";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    val activity=instance as? Activity?:return@before
                    attached.remove(activity)?.let { activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(it) }
                    adjusted.remove(activity)
                }
            }
            main.post {
                runCatching {
                    val thread=Class.forName("android.app.ActivityThread")
                    val current=thread.getDeclaredMethod("currentActivityThread").invoke(null)
                    val records=thread.getDeclaredField("mActivities").apply { isAccessible=true }.get(current) as Map<*,*>
                    records.values.filterNotNull().forEach { record->
                        (record.javaClass.getDeclaredField("activity").apply { isAccessible=true }.get(record) as? Activity)?.let {
                            attach(it)
                        }
                    }
                }.onFailure { YLog.warn("Existing WeChat activity unavailable",it) }
            }
            runtime.onClose {
                closed=true
                main.post {
                    attached.forEach { (activity,listener)->activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(listener) }
                    attached.clear()
                    adjusted.values.forEach { (list,amount)->if(amount>0)list.scrollListBy(-amount) }
                    adjusted.clear()
                }
            }
        }
    }
}
