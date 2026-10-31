package hk.uwu.reareye.hook.scopes.system.modules

import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import org.json.JSONArray
import android.os.Binder
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import java.util.Collections
import java.util.WeakHashMap

/** Final app configuration only: the physical display and rear system UI retain their density. */
class RearAppDensityHook : RoxyHooker() {
    /** The camera port owns this process' rear-screen Resources and menu geometry. */
    private val excludedPackages = setOf(
        "com.android.camera",
        "com.kobbin.leicaflavor",
        "xyz.shuncongci.leica",
    )
    // Calendar uses the native MIUI freeform resize path. Reapplying the rear
    // compatibility scale on every setFreeformScale/geometry callback pins its
    // small-window ratio and makes the resize handle report unsupported sizing.
    private val userResizableRearPackages = setOf("com.android.calendar")
    private var reloading=false
    override fun onHotReloadQuiesce() { reloading=true }
    companion object {
        @Volatile private var current:((Any,Configuration)->Unit)?=null
        fun applyConfiguration(record:Any,parent:Configuration) { current?.invoke(record,parent) }
    }
    override fun PackageScope.onHook() {
        reloading=false
        loadSystem {
            val tasks=Collections.newSetFromMap(WeakHashMap<Any,Boolean>())
            val minimizingTasks=Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any,Boolean>()))
            val main=Handler(Looper.getMainLooper())
            var active=true
            var warned=false
            data class Settings(val enabled:Boolean=false,val dpi:Int=300,val keyboard:Boolean=true,
                                val region:String=RearAppDefaults.REGION,val apps:Set<String> = emptySet(),val imeUid:Int = -1,
                                val rounded:Boolean=true,val radius:Float=RearAppDefaults.CORNER_RADIUS)
            fun readSettings():Settings? {
                // WM callbacks may retain the app's Binder identity. Read module preferences
                // as system_server, outside the WM lock, and publish one immutable snapshot.
                val identity=Binder.clearCallingIdentity()
                return try {
                    val prefs=hookPrefs
                    val imeUid=runCatching {
                        val context=hookSystemContext
                        val raw=android.provider.Settings.Secure.getString(context.contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                        val pkg=android.content.ComponentName.unflattenFromString(raw.orEmpty())?.packageName?:return@runCatching -1
                        context.packageManager.getApplicationInfo(pkg,0).uid
                    }.getOrDefault(-1)
                    if(!prefs.isRemoteReady())null else Settings(
                        prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false),prefs.getInt(ConfigKeys.REAR_APP_DPI,RearAppDefaults.APP_DPI),
                        prefs.getBoolean(ConfigKeys.REAR_APP_KEYBOARD,true),prefs.getString(ConfigKeys.REAR_APP_REGION,RearAppDefaults.REGION),
                        prefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS).toSet(),imeUid,
                        prefs.getBoolean(ConfigKeys.REAR_APP_ROUNDED,true),
                        prefs.getFloat(ConfigKeys.REAR_APP_CORNER_RADIUS,RearAppDefaults.CORNER_RADIUS))
                } finally { Binder.restoreCallingIdentity(identity) }
            }
            val settings=java.util.concurrent.atomic.AtomicReference(Settings())
            // Keep the historical primary-display density basis. This hook is installed
            // while system_server is bootstrapping, so querying DisplayManager here can
            // recurse through PackageManager/SystemService initialization and crash
            // system_server before the rear launcher is available. Display 1 is queried
            // lazily below, only from task/display callbacks after boot.
            val primaryBaseDpi=hookSystemContext.resources.configuration.densityDpi
                .takeIf { it > 0 } ?: 480
            val rearBaseDpi=primaryBaseDpi
            fun contentScale():Float=RearAppDefaults.contentScale(settings.get().dpi,primaryBaseDpi)
            fun safeBounds(physical:Rect):Rect {
                val safe=Rect(physical)
                hookSystemContext.getSystemService(DisplayManager::class.java)?.getDisplay(1)?.cutout?.let {
                    safe.inset(it.safeInsetLeft,it.safeInsetTop,it.safeInsetRight,it.safeInsetBottom)
                }
                settings.get().region.takeIf { it.isNotBlank() }?.let { raw -> runCatching {
                    val r=JSONArray(raw)
                    val custom=Rect((physical.width()*r.getDouble(0)).toInt(),(physical.height()*r.getDouble(1)).toInt(),
                        (physical.width()*r.getDouble(2)).toInt(),(physical.height()*r.getDouble(3)).toInt())
                    if(!custom.isEmpty&&physical.contains(custom))safe.set(custom)
                } }
                return safe
            }
            fun logicalAppBounds(physical:Rect):Rect {
                val safe=safeBounds(physical)
                val scale=contentScale()
                return Rect(safe.left,safe.top,safe.left+kotlin.math.round(safe.width()/scale).toInt(),safe.top+kotlin.math.round(safe.height()/scale).toInt())
            }
            val imeViewport=java.util.concurrent.atomic.AtomicReference<Pair<Rect,Int>?>(null)
            val appUids=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val rearTaskIds=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val freeformClass="com.android.server.wm.MiuiFreeFormActivityStack".toClass()
            freeformClass.resolve().firstMethod { name="setFreeformScale";parameterCount=1 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    val task=instance?.let { Server.field(it,"mTask") }?:return@before
                    val pkg=Server.call(task,"getTopMostActivity")?.let { Server.component(it)?.substringBefore('/') }
                    if(active&&settings.get().enabled&&Server.displayId(task)==1&&
                        pkg !in userResizableRearPackages&&synchronized(tasks) { task in tasks })
                        args(0).value=contentScale()
                }
            }
            // HyperOS 4.0.5 reports Calendar's activity as resizable, but its
            // task-level freeform/split capability is still false on the phone
            // build. That makes SystemUI show "split screen unsupported" even
            // though the same Calendar APK works on the tablet. Restore the
            // native capability bits only for Calendar; do not change any other
            // application's multi-window policy.
            fun calendarPackage(value:Any?):Boolean {
                val component=when {
                    value==null -> null
                    value.javaClass.name.contains("ActivityRecord") -> Server.component(value)
                    else -> Server.call(value,"getTopMostActivity")?.let { Server.component(it) }
                }
                return component?.substringBefore('/')=="com.android.calendar"
            }
            fun hookCalendarCapability(typeName:String) {
                runCatching {
                    val type=typeName.toClass()
                    type.declaredMethods.filter { method ->
                        method.parameterCount==0&&method.returnType==Boolean::class.javaPrimitiveType&&
                            method.name in setOf("isResizeable","supportsResizeableMultiWindow",
                                "supportsSplitScreenWindowingMode","supportsMultiWindow",
                                "supportsFreeform","supportsSizeChanges","isResizeableForFreeform")
                    }.distinctBy { it.name }.forEach { method ->
                        type.resolve().firstMethod { name=method.name;parameterCount=0 }.hook {
                            hotReloadPolicy=HotReloadPolicy.REINSTALL
                            after { if(calendarPackage(instance))result=true }
                        }
                    }
                }.onFailure { YLog.warn("Calendar multi-window capability hook unavailable for $typeName",it) }
            }
            hookCalendarCapability("com.android.server.wm.ActivityRecord")
            hookCalendarCapability("com.android.server.wm.Task")
            fun nativeFreeform(task:Any):Any? {
                val service=Server.field(task,"mAtmService")?.let { Server.field(it,"mMiuiFreeFormManagerService") }?:return null
                return service.javaClass.getMethod("getMiuiFreeFormActivityStackForMiuiFB",Int::class.javaPrimitiveType)
                    .invoke(service,Server.field(task,"mTaskId") as? Int?:return null)
            }
            fun restoreRearScale(task:Any) {
                if(Server.displayId(task)!=1||Server.call(task,"getWindowingMode")!=5)return
                val pkg=Server.call(task,"getTopMostActivity")?.let { Server.component(it)?.substringBefore('/') }
                if(pkg in userResizableRearPackages)return
                val stack=nativeFreeform(task)?:return
                val scale=contentScale()
                if(Server.call(stack,"getFreeFormScale")!=scale)
                    stack.javaClass.getMethod("setFreeformScale",Float::class.javaPrimitiveType).invoke(stack,scale)
            }
            fun refreshRearTaskGeometry(task:Any) {
                val service=Server.field(task,"mAtmService")?:return
                val lock=Server.field(service,"mGlobalLock")?:return
                synchronized(lock) {
                    if(Server.displayId(task)!=1||Server.call(task,"getWindowingMode")!=5)return@synchronized
                    val physical=Server.call(task,"getDisplayContent")?.let { Server.call(it,"getBounds") } as? Rect?:return@synchronized
                    val logical=logicalAppBounds(physical)
                    if(Server.call(task,"getBounds")!=logical) {
                        task.javaClass.getDeclaredMethod("resize",Rect::class.java,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
                            .apply { isAccessible=true }.invoke(task,logical,2,true)
                    }
                    restoreRearScale(task)
                    Server.call(task,"resetScaleChangeReported")
                    task.javaClass.getDeclaredMethod("dispatchTaskInfoChangedIfNeeded",Boolean::class.javaPrimitiveType)
                        .apply { isAccessible=true }.invoke(task,true)
                }
            }
            "com.android.server.wm.Task".toClass().resolve().firstMethod {
                name="onRequestedOverrideConfigurationChanged";parameters(Configuration::class.java)
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    val task=instance?:return@before
                    if(!active||!settings.get().enabled||Server.displayId(task)!=1||!synchronized(tasks) { task in tasks })return@before
                    val original=args(0).value as? Configuration?:return@before
                    val originalWindow=Server.field(original,"windowConfiguration")?:return@before
                    val requested=Server.call(originalWindow,"getWindowingMode") as? Int?:0
                    val mode=if(requested==0)Server.call(task,"getParent")?.let { Server.call(it,"getWindowingMode") as? Int } else requested
                    if(mode!=5||Server.call(task,"getTopMostActivity")==null)return@before
                    restoreRearScale(task)
                    val display=hookSystemContext.getSystemService(DisplayManager::class.java)?.getDisplay(1)?:return@before
                    val size=android.graphics.Point()
                    @Suppress("DEPRECATION")
                    display.getRealSize(size)
                    val copy=Configuration(original)
                    val window=Server.field(copy,"windowConfiguration")?:return@before
                    window.javaClass.getMethod("setBounds",Rect::class.java).invoke(window,logicalAppBounds(Rect(0,0,size.x,size.y)))
                    args(0).value=copy
                }
            }
            fun restoreMainTask(task:Any) {
                task.javaClass.getMethod("setWindowingMode",Int::class.javaPrimitiveType).invoke(task,1)
                task.javaClass.getMethod("setBounds",Rect::class.java).invoke(task,null)
            }
            val taskClass="com.android.server.wm.Task".toClass()
            val moveTaskToBack=taskClass.getDeclaredMethod("moveTaskToBack",taskClass).apply { isAccessible=true }
            fun parkRearTasks(exceptPackage:String?=null) {
                val candidates=synchronized(tasks) { tasks.toList() }.filter { task ->
                    Server.displayId(task)==1&&Server.call(task,"isActivityTypeHome")!=true&&
                        Server.call(task,"isVisibleRequested")==true&&
                        Server.call(task,"getTopMostActivity")?.let { Server.component(it)?.substringBefore('/')!=exceptPackage }==true
                }
                candidates.forEach { task ->
                    val identity=Binder.clearCallingIdentity()
                    try { moveTaskToBack.invoke(task,task) }
                    finally { Binder.restoreCallingIdentity(identity) }
                }
            }
            // Stock rear Home is normally brought forward from recents. Native freeform
            // tasks must first follow the framework's own minimize path, which retains
            // their processes and back stacks while removing always-on-top state.
            "com.android.server.wm.ActivityTaskManagerService".toClass().resolve().firstMethod {
                name="startActivityFromRecents";parameterCount=2
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    if(!active||!settings.get().enabled)return@before
                    val service=instance?:return@before
                    val root=Server.field(service,"mRootWindowContainer")?:return@before
                    val lock=Server.field(service,"mGlobalLock")?:return@before
                    synchronized(lock) {
                        val task=root.javaClass.getDeclaredMethod("anyTaskForId",Int::class.javaPrimitiveType)
                            .apply { isAccessible=true }.invoke(root,args(0).int())?:return@synchronized
                        if(Server.displayId(task)==1&&(Server.call(task,"isActivityTypeHome")==true||
                                Server.call(task,"getTopMostActivity")?.let { Server.component(it)?.startsWith("com.xiaomi.subscreencenter/") }==true))parkRearTasks()
                    }
                }
            }
            "com.android.server.wm.Task".toClass().resolve().firstMethod { name="onDisplayChanged";parameterCount=1 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    val task=instance?:return@after
                    val id=Server.field(task,"mTaskId") as? Int?:return@after
                    if(active&&Server.displayId(task)==0&&rearTaskIds.remove(id))restoreMainTask(task)
                }
            }
            val clippedTasks=Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any,Boolean>()))
            fun clipTask(task:Any,area:Rect?,radius:Float?) {
                val surface=Server.call(task,"getSurfaceControl")?:return
                if(Server.call(surface,"isValid")!=true)return
                val transaction=Server.call(task,"getSyncTransaction")?:return
                val surfaceClass="android.view.SurfaceControl".toClass()
                transaction.javaClass.getMethod("setWindowCrop",surfaceClass,Rect::class.java).invoke(transaction,surface,area)
                // Auto uses Xiaomi's own freeform corner policy. Only explicit
                // overrides change the radius; do not replace native rounding.
                if(radius!=null)transaction.javaClass.getMethod("setCornerRadius",surfaceClass,Float::class.javaPrimitiveType).invoke(transaction,surface,radius)
            }
            "com.android.server.wm.WindowContainer".toClass().resolve().firstMethod { name="prepareSurfaces";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    val task=instance?:return@before
                    if(!taskClass.isInstance(task))return@before
                    val config=settings.get()
                    if(config.rounded&&config.radius<=0f&&clippedTasks.isEmpty())return@before
                    if(active&&config.enabled&&Server.displayId(task)==1&&synchronized(tasks) { task in tasks }&&Server.call(task,"isActivityTypeHome")!=true) {
                        val record=Server.call(task,"getTopMostActivity")?:return@before
                        val bounds=Server.call(record,"getBounds") as? Rect?:return@before
                        val parent=Server.call(task,"getBounds") as? Rect?:return@before
                        val crop=Rect(bounds).apply { offset(-parent.left,-parent.top) }
                        if(crop.isEmpty)return@before
                        clipTask(task,crop,if(!config.rounded)0f else config.radius.takeIf { it>0f }?.let { minOf(it/contentScale(),crop.width()/2f,crop.height()/2f) })
                        clippedTasks.add(task)
                    } else if(clippedTasks.remove(task))clipTask(task,null,0f)
                }
            }
            "com.android.server.wm.ActivityStarter".toClass().resolve().firstMethod {
                name="setInitialState";parameterCount=10
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    if(!active||!settings.get().enabled)return@before
                    val record=args(0).value?:return@before
                    val intent=Server.field(record,"intent") as? android.content.Intent?:return@before
                    val options=args(1).value as? android.app.ActivityOptions
                    val caller=Server.field(record,"launchedFromPackage") as? String
                    val requested=options?.let { Server.call(it,"getLaunchDisplayId") as? Int }
                    val pkg=Server.component(record)?.substringBefore('/')?:return@before
                    if(requested==1) {
                        synchronized(tasks) { tasks.filter { task ->
                            (Server.field(task,"realActivity") as? android.content.ComponentName)?.packageName==pkg
                        }.forEach(minimizingTasks::remove) }
                        parkRearTasks(pkg.takeUnless { it=="com.xiaomi.subscreencenter" })
                        return@before
                    }
                    if(intent.action!=android.content.Intent.ACTION_MAIN||!intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER))return@before
                    if(caller!="com.miui.home"&&!(caller=="com.android.shell"&&requested==0))return@before
                    val user=Server.field(record,"mUserId")
                    val existing=synchronized(tasks) { tasks.firstOrNull { task ->
                        (Server.displayId(task)==1||rearTaskIds.contains(Server.field(task,"mTaskId")))&&Server.call(task,"isActivityTypeHome")!=true&&Server.field(task,"mUserId")==user&&
                            ((Server.field(task,"realActivity") as? android.content.ComponentName)?.packageName==pkg||
                                Server.call(task,"getTopMostActivity")?.let { Server.component(it)?.substringBefore('/')==pkg }==true)
                    } }?:return@before
                    val target=options?:android.app.ActivityOptions.makeBasic()
                    target.setLaunchDisplayId(0);target.setLaunchBounds(null)
                    val mode=Server.call(target,"getLaunchWindowingMode") as? Int?:0
                    android.app.ActivityOptions::class.java.getDeclaredMethod("setLaunchWindowingMode",Int::class.javaPrimitiveType)
                        .invoke(target,if(mode==5)5 else 1)
                    android.app.ActivityOptions::class.java.getDeclaredMethod("setLaunchTaskId",Int::class.javaPrimitiveType)
                        .invoke(target,Server.field(existing,"mTaskId") as Int)
                    if(mode!=5) {
                        restoreMainTask(existing)
                    }
                    rearTaskIds.remove(Server.field(existing,"mTaskId"))
                    args(1).value=target
                }
            }
            fun invalidateDisplayInfo() {
                runCatching {
                    "android.hardware.display.DisplayManagerGlobal".toClass()
                        .getDeclaredMethod("invalidateLocalDisplayInfoCaches").apply { isAccessible=true }.invoke(null)
                }.onFailure { YLog.warn("Rear app display-info cache invalidation unavailable",it) }
            }
            val imeBounds=Collections.synchronizedMap(WeakHashMap<android.view.WindowManager.LayoutParams,Rect>())
            val imeLayoutPolicies=Collections.synchronizedMap(WeakHashMap<android.view.WindowManager.LayoutParams,Pair<Int,Int>>())
            val appImeResize=Collections.synchronizedMap(WeakHashMap<android.view.WindowManager.LayoutParams,Boolean>())
            val appBarInsets=Collections.synchronizedMap(WeakHashMap<android.view.WindowManager.LayoutParams,Int>())
            val privateFlags=android.view.WindowManager.LayoutParams::class.java.getField("privateFlags")
            val getFitTypes=android.view.WindowManager.LayoutParams::class.java.getMethod("getFitInsetsTypes")
            val setFitTypes=android.view.WindowManager.LayoutParams::class.java.getMethod("setFitInsetsTypes",Int::class.javaPrimitiveType)
            val insetParentByIme=0x40000000
            val nativeImeAvoidance=Collections.synchronizedMap(WeakHashMap<Any,Boolean>())
            val blurredWindows=Collections.synchronizedMap(WeakHashMap<android.view.WindowManager.LayoutParams,Pair<Boolean,Int>>())
            val blurRadius by lazy { runCatching {
                val display=hookSystemContext.getSystemService(DisplayManager::class.java).getDisplay(1)
                val resources=hookSystemContext.createPackageContext("com.xiaomi.subscreencenter",0).createDisplayContext(display).resources
                resources.getDimensionPixelSize(resources.getIdentifier("notification_panel_blur_radius","dimen","com.xiaomi.subscreencenter"))
            }.getOrDefault(0) }
            fun rearBlurWindow(window:Any):Boolean {
                val attrs=Server.field(window,"mAttrs") as? android.view.WindowManager.LayoutParams?:return false
                return active&&settings.get().enabled&&Server.displayId(window)==1&&blurredWindows.containsKey(attrs)
            }
            fun restoreAppBlur(attrs:android.view.WindowManager.LayoutParams) {
                blurredWindows.remove(attrs)?.let { (original,radius) ->
                    if(!original)attrs.flags=attrs.flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    attrs.setBlurBehindRadius(radius)
                }
            }
            taskClass.resolve().firstMethod { name="moveTaskToBack";parameters(taskClass) }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    val task=args(0).value?:return@before
                    if(!active||Server.displayId(task)!=1||!synchronized(tasks) { task in tasks })return@before
                    minimizingTasks.add(task)
                    // Withdraw our blur request at native minimize start, rather than
                    // keeping it until the closing app surface is finally destroyed.
                    val visit=java.util.function.Consumer<Any> { window ->
                        (Server.field(window,"mAttrs") as? android.view.WindowManager.LayoutParams)?.let(::restoreAppBlur)
                    }
                    generateSequence<Class<*>>(task.javaClass) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
                        .first { it.name=="forAllWindows"&&it.parameterCount==2&&it.parameterTypes[0]==java.util.function.Consumer::class.java }
                        .apply { isAccessible=true }.invoke(task,visit,true)
                }
            }
            "com.android.server.wm.Dimmer".toClass().declaredMethods.filter { it.name=="adjustAppearance"&&it.parameterCount in 3..4 }.forEach { method ->
                "com.android.server.wm.Dimmer".toClass().resolve().firstMethod { name=method.name;parameters(*method.parameterTypes) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    before {
                        val window=args(0).value?:return@before
                        if(Server.displayId(window)!=1)return@before
                        val task=Server.field(window,"mActivityRecord")?.let(Server::task)?:return@before
                        if(task in minimizingTasks)args(2).value=0
                    }
                }
            }
            // Retain the window manager's native blur animation and lifecycle. Put
            // its existing backdrop in the rear display area so camera avoidance
            // clips the app, while the backdrop still covers the whole background.
            "com.android.server.wm.Dimmer".toClass().resolve().firstMethod { name="adjustPosition";parameterCount=2 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    val window=args(1).value?:return@before
                    if(!rearBlurWindow(window))return@before
                    val display=Server.call(window,"getDisplayContent")?:return@before
                    Server.call(display,"getDefaultTaskDisplayArea")?.let { args(0).value=it }
                }
            }
            "com.android.server.wm.DimmerAnimationHelper".toClass().resolve().firstMethod { name="applyChanges";parameterCount=2 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    val dim=args(1).value?:return@before
                    val window=Server.field(dim,"mLastDimmingWindow")?:return@before
                    if(!rearBlurWindow(window))return@before
                    val display=Server.call(window,"getDisplayContent")?:return@before
                    val area=Server.call(display,"getDefaultTaskDisplayArea")?:return@before
                    dim.javaClass.getDeclaredMethod("prepareReparent","com.android.server.wm.WindowContainer".toClass(),window.javaClass)
                        .apply { isAccessible=true }.invoke(dim,area,window)
                }
            }
            "com.android.server.wm.DimmerAnimationHelper".toClass().resolve().firstMethod { name="setBounds";parameterCount=3 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    val window=args(1).value?:return@after
                    if(!rearBlurWindow(window))return@after
                    val dim=args(0).value?:return@after
                    val surface=Server.field(dim,"mDimSurface")?:return@after
                    if(Server.call(surface,"isValid")!=true)return@after
                    val area=Server.call(window,"getDisplayContent")?.let { Server.call(it,"getBounds") } as? Rect?:return@after
                    (Server.field(dim,"mDimBounds") as? Rect)?.set(area)
                    val transaction=args(2).value?:return@after
                    transaction.javaClass.getMethod("setCrop",surface.javaClass,Rect::class.java).invoke(transaction,surface,area)
                    transaction.javaClass.getMethod("setPosition",surface.javaClass,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                        .invoke(transaction,surface,0f,0f)
                }
            }
            "com.android.server.wm.WindowToken".toClass().resolve().firstMethod {
                name="resolveOverrideConfiguration";parameterCount=1
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    if(!active||!settings.get().enabled||!settings.get().keyboard)return@after
                    val token=instance?:return@after
                    val display=Server.call(token,"getDisplayContent")?:return@after
                    if(Server.field(token,"windowType")!=2011)return@after
                    if(Server.displayId(display)!=1) {
                        if(imeViewport.getAndSet(null)!=null)invalidateDisplayInfo()
                        return@after
                    }
                    val target=Server.call(display,"getImeInputTarget")?:Server.field(display,"mCurrentFocus")?:return@after
                    val record=Server.field(target,"mActivityRecord")?:return@after
                    val safe=(Server.call(display,"getBounds") as? Rect)?.let(::safeBounds)?:return@after
                    if(safe.isEmpty)return@after
                    val resolved=Server.resolved(token)?:return@after
                    val parent=args(0).value as? Configuration?:return@after
                    val window=Server.field(resolved,"windowConfiguration")?:return@after
                    window.javaClass.getMethod("setBounds",Rect::class.java).invoke(window,safe)
                    window.javaClass.getMethod("setAppBounds",Rect::class.java).invoke(window,safe)
                    // Keep the app density setting independent from the keyboard. The
                    // physical rear panel is only 680px wide; Baidu's 450dpi phone layout
                    // collapses its keys into the center. Give IME its own usable baseline.
                    val dpi=RearAppDefaults.IME_DPI
                    Server.apply(parent,resolved,dpi)
                    val next=Rect(safe) to dpi
                    if(next!=imeViewport.getAndSet(next))invalidateDisplayInfo()
                }
            }
            "android.view.WindowLayout".toClass().resolve().firstMethod {
                name="computeFrames";parameterCount=10
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    if(!active||!settings.get().enabled||!settings.get().keyboard)return@before
                    val attrs=args(0).value as? android.view.WindowManager.LayoutParams?:return@before
                    val safe=imeBounds[attrs]?:return@before
                    // The native layout routine prefers requested size over LayoutParams.
                    // Keep the measured IME frame consistent with its safe window context.
                    args(5).value=safe.width();args(6).value=safe.height()
                }
            }
            // Give Google toolbars a small native status-bar inset. No status-bar
            // window is created and the physical task/display area does not move.
            val googleTopPadding by lazy {
                val rearDisplay=hookSystemContext.getSystemService(DisplayManager::class.java)?.getDisplay(1)
                val corner=rearDisplay?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)?.radius?:0
                kotlin.math.ceil(corner*(1.0-1.0/kotlin.math.sqrt(2.0))).toInt()
            }
            val insetClass="android.view.InsetsState".toClass()
            val copyInsets=insetClass.getConstructor(insetClass,Boolean::class.javaPrimitiveType)
            val getSource=insetClass.getMethod("getOrCreateSource",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
            val windowClass="com.android.server.wm.WindowState".toClass()
            windowClass.declaredMethods.filter { it.name=="getInsetsState"&&it.returnType==insetClass }.forEach { method ->
                windowClass.resolve().firstMethod { name=method.name;parameters(*method.parameterTypes) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        if(!active||!settings.get().enabled)return@after
                        val window=instance?:return@after
                        if(Server.displayId(window)!=1)return@after
                        val attrs=Server.field(window,"mAttrs") as? android.view.WindowManager.LayoutParams
                        if(attrs?.type==android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD&&settings.get().keyboard) {
                            // The shared safeBounds already includes the selected camera
                            // avoidance. Do not inset the keyboard a second time at x=296.
                            val original=result?:return@after
                            val copy=copyInsets.newInstance(original,true)
                            insetClass.getMethod("setDisplayCutout",android.view.DisplayCutout::class.java)
                                .invoke(copy,android.view.DisplayCutout::class.java.getField("NO_CUTOUT").get(null))
                            val count=Server.call(copy,"sourceSize") as? Int?:0
                            val sourceAt=insetClass.getMethod("sourceAt",Int::class.javaPrimitiveType)
                            val remove=insetClass.getMethod("removeSource",Int::class.javaPrimitiveType)
                            for(index in count-1 downTo 0) {
                                val source=sourceAt.invoke(copy,index)
                                if(Server.call(source,"getType")==128)remove.invoke(copy,Server.call(source,"getId"))
                            }
                            result=copy;return@after
                        }
                        val record=Server.field(window,"mActivityRecord")?:return@after
                        val info=Server.field(record,"info") as? ActivityInfo?:return@after
                        if(googleTopPadding<=0||info.packageName!="com.android.chrome"&&!info.packageName.startsWith("com.google.android."))return@after
                        val bounds=Server.call(record,"getBounds") as? Rect?:return@after
                        val original=result?:return@after
                        val copy=copyInsets.newInstance(original,true)
                        val source=getSource.invoke(copy,0x48525301,1)
                        source.javaClass.getMethod("setFrame",Rect::class.java).invoke(source,
                            Rect(bounds.left,bounds.top,bounds.right,bounds.top+kotlin.math.ceil(googleTopPadding/contentScale()).toInt()))
                        source.javaClass.getMethod("setVisible",Boolean::class.javaPrimitiveType).invoke(source,true)
                        result=copy
                    }
                }
            }
            "com.android.server.wm.DisplayPolicy".toClass().resolve().firstMethod {
                name="layoutWindowLw";parameterCount=3
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    if(!active||!settings.get().enabled)return@before
                    val window=args(0).value?:return@before
                    val attrs=Server.field(window,"mAttrs") as? android.view.WindowManager.LayoutParams?:return@before
                    if(Server.displayId(window)!=1) {
                        imeLayoutPolicies.remove(attrs)?.let { (fit,cutoutMode)->
                            setFitTypes.invoke(attrs,fit);attrs.layoutInDisplayCutoutMode=cutoutMode
                        }
                        imeBounds.remove(attrs)
                        appImeResize.remove(attrs)?.let { original ->
                            if(!original)privateFlags.setInt(attrs,privateFlags.getInt(attrs) and insetParentByIme.inv())
                        }
                        appBarInsets.remove(attrs)?.let { setFitTypes.invoke(attrs,it) }
                        blurredWindows.remove(attrs)?.let { (original,radius) ->
                            if(!original)attrs.flags=attrs.flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                            attrs.setBlurBehindRadius(radius)
                        }
                        Server.field(window,"mActivityRecord")?.let { Server.task(it) }?.let { nativeFreeform(it) }?.let { stack ->
                            nativeImeAvoidance.remove(stack)?.let { original ->
                                stack.javaClass.getDeclaredField("mNeedAvoidFrameForIme").apply { isAccessible=true }.setBoolean(stack,original)
                            }
                        }
                        return@before
                    }
                    if(attrs.type in 1..99) {
                        val record=Server.field(window,"mActivityRecord")?:return@before
                        val info=Server.field(record,"info") as? ActivityInfo?:return@before
                        val adjust=attrs.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
                        if(info.applicationInfo.uid%100000>=10000&&info.packageName !in setOf("com.xiaomi.subscreencenter","com.android.systemui","hk.uwu.reareye")) {
                            val fit=getFitTypes.invoke(attrs) as Int
                            if(!appBarInsets.containsKey(attrs))appBarInsets[attrs]=fit
                            setFitTypes.invoke(attrs,fit and 7.inv())
                            val appTask=Server.task(record)
                            val blurActive=appTask!=null&&appTask !in minimizingTasks&&Server.call(appTask,"getWindowingMode")==5&&Server.call(appTask,"isVisibleRequested")==true
                            // Native WM blur covers the camera strip while the app is
                            // foreground. Minimize withdraws this request before Shell
                            // returns to the icon, retaining the factory fade-out path.
                            if(blurActive&&blurRadius>0) {
                                if(!blurredWindows.containsKey(attrs))blurredWindows[attrs]=
                                    (attrs.flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND!=0) to attrs.blurBehindRadius
                                attrs.flags=attrs.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                                attrs.setBlurBehindRadius(blurRadius)
                            } else restoreAppBlur(attrs)
                            if(settings.get().keyboard&&adjust!=android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
                                Server.task(record)?.let { nativeFreeform(it) }?.let { stack ->
                                    val field=stack.javaClass.getDeclaredField("mNeedAvoidFrameForIme").apply { isAccessible=true }
                                    if(!nativeImeAvoidance.containsKey(stack))nativeImeAvoidance[stack]=field.getBoolean(stack)
                                    field.setBoolean(stack,true)
                                }
                            }
                        }
                        return@before
                    }
                    if(!settings.get().keyboard)return@before
                    if(attrs.type!=android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD)return@before
                    val display=Server.call(window,"getDisplayContent")?:return@before
                    val target=Server.call(display,"getImeInputTarget")?:return@before
                    val record=Server.field(target,"mActivityRecord")?:return@before
                    val physical=Server.call(display,"getBounds") as? Rect?:return@before
                    val safe=safeBounds(physical)
                    if(safe.isEmpty||!physical.contains(safe))return@before
                    imeBounds[attrs]=Rect(safe)
                    if(!imeLayoutPolicies.containsKey(attrs))imeLayoutPolicies[attrs]=
                        (getFitTypes.invoke(attrs) as Int) to attrs.layoutInDisplayCutoutMode
                    setFitTypes.invoke(attrs,0)
                    attrs.layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    // Give the native IME window exactly the
                    // app's safe area, including user-defined bounds and camera avoidance.
                    attrs.width=safe.width();attrs.height=safe.height()
                    attrs.gravity=android.view.Gravity.BOTTOM or android.view.Gravity.RIGHT
                    attrs.x=physical.right-safe.right;attrs.y=physical.bottom-safe.bottom
                }
                after {
                    if(!active||!settings.get().enabled||!settings.get().keyboard)return@after
                    val window=args(0).value?:return@after
                    if(Server.displayId(window)!=1)return@after
                    val record=Server.field(window,"mActivityRecord")?:return@after
                    val task=Server.task(record)?:return@after
                    val stack=nativeFreeform(task)?:return@after
                    if(!nativeImeAvoidance.containsKey(stack))return@after
                    val frames=Server.field(window,"mWindowFrames")?:return@after
                    val manager=Server.field(task,"mAtmService")?.let { Server.field(it,"mMiuiFreeFormManagerService") }?:return@after
                    manager.javaClass.getMethod("adjustFrameForImeIfNeed",window.javaClass,frames.javaClass,Int::class.javaPrimitiveType)
                        .invoke(manager,window,frames,Server.field(task,"mTaskId"))
                }
            }
            "com.android.server.wm.WindowManagerServiceImpl".toClass().resolve().firstMethod {
                name="hasPermission";parameterCount=4
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    // MIUI rejects every third-party system-window type on the rear screen.
                    // Admit only the native IME type; WMS still validates its IMMS-owned token.
                    if(active&&settings.get().enabled&&settings.get().keyboard&&args(2).int()==1&&
                        args(1).int()==android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD)result=true
                }
            }
            var imeWindowDiagnostics=3
            "com.android.server.wm.WindowManagerService".toClass().resolve().firstMethod {
                name="addWindow"
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    val attrs=args(2).value as? android.view.WindowManager.LayoutParams?:return@after
                    if(attrs.type==android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD&&imeWindowDiagnostics>0) {
                        imeWindowDiagnostics--
                        YLog.info("IME addWindow: display=${args(4).int()} result=${result<Int>()}")
                    }
                }
            }
            // Applications also query Display.getRealMetrics(), independently of Resources.
            // Copy the Binder result per caller; never mutate the physical display's info.
            "com.android.server.display.DisplayManagerService".toClass().resolve().firstMethod {
                name="getDisplayInfoInternal";parameterCount=2
            }.hook {
                id="reareye.rear.app-display-info"
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    if(!active||args(0).int()!=1||!settings.get().enabled)return@after
                    val ime=imeViewport.get()?.takeIf { settings.get().keyboard&&args(1).int()==settings.get().imeUid }
                    if(ime==null&&!appUids.contains(args(1).int()))return@after
                    val dpi=ime?.second?:primaryBaseDpi
                    if(dpi !in 120..960)return@after
                    val original=result<Any>()?:return@after
                    val copy=original.javaClass.getConstructor(original.javaClass).newInstance(original)
                    original.javaClass.getField("logicalDensityDpi").setInt(copy,dpi)
                    if(ime!=null) {
                        // The IME is already inside the camera-safe viewport. Report that
                        // viewport to its process so it does not reserve the cutout twice.
                        listOf("logicalWidth","appWidth").forEach { original.javaClass.getField(it).setInt(copy,ime.first.width()) }
                        listOf("logicalHeight","appHeight").forEach { original.javaClass.getField(it).setInt(copy,ime.first.height()) }
                        original.javaClass.getField("displayCutout").set(copy,null)
                    }
                    result=copy
                }
            }
            // MIUI otherwise rejects the rear window before ordinary IME routing runs.
            // Keep its checks for native panels and every other display unchanged.
            runCatching {
                "com.android.server.wm.MiuiSplitInputMethodImpl".toClass().resolve().firstMethod {
                    name="shouldUpdateImeTargetForBackScreen";parameterCount=1
                }.hook {
                    id="reareye.rear.app-ime-target"
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        if(!active||!settings.get().enabled||!settings.get().keyboard)return@after
                        val target=args(0).value?:return@after
                        if(Server.displayId(target)!=1)return@after
                        val window=Server.call(target,"getWindowState")?:return@after
                        val record=Server.field(window,"mActivityRecord")?:return@after
                        val info=Server.field(record,"info") as? ActivityInfo?:return@after
                        if(info.applicationInfo.uid%100000<10000||info.packageName in setOf(
                                "com.xiaomi.subscreencenter","com.android.systemui","hk.uwu.reareye"))return@after
                        // The stock rear display never created an IME source while routing was
                        // blocked. Bootstrap the normal provider before its first show request.
                        Server.call(target,"getDisplayContent")?.let { display ->
                            Server.call(display,"getInsetsStateController")?.let { Server.call(it,"getImeSourceProvider") }
                        }
                        result=true
                    }
                }
            }.onFailure { YLog.warn("Native rear IME target hook unavailable",it) }
            runCatching {
                "com.android.server.wm.InsetsStateController".toClass().resolve().firstMethod {
                    name="onRequestedVisibleTypesChanged";parameterCount=3
                }.hook {
                    id="reareye.rear.app-ime-first-show"
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        if(!active||!settings.get().enabled||!settings.get().keyboard||args(1).int() and 8==0)return@after
                        val target=args(0).value?:return@after
                        if(Server.displayId(target)!=1||(Server.field(target,"mRequestedVisibleTypes") as? Int?:0) and 8==0)return@after
                        val record=Server.field(target,"mActivityRecord")?:return@after
                        val info=Server.field(record,"info") as? ActivityInfo?:return@after
                        if(info.applicationInfo.uid%100000<10000||info.packageName in setOf(
                                "com.xiaomi.subscreencenter","com.android.systemui","hk.uwu.reareye"))return@after
                        val display=Server.call(target,"getDisplayContent")?:return@after
                        if(Server.field(display,"mImeWindow")!=null||Server.call(display,"getImeInputTarget")!==target)return@after
                        val provider=Server.call(instance?:return@after,"getImeSourceProvider")?:return@after
                        // No old IME surface exists on the stock rear display. Forward the
                        // editor's first request through the provider's normal IMMS callback.
                        provider.javaClass.declaredMethods.first {
                            it.name=="invokeOnImeRequestedChangedListener"&&it.parameterCount==2
                        }.apply { isAccessible=true }.invoke(provider,target,args(2).value)
                    }
                }
            }.onFailure { YLog.warn("Native rear IME first-show hook unavailable",it) }
            var configDiagnostics=3
            val configure:(Any,Configuration)->Unit=configure@ { record,parent ->
                if(configDiagnostics>0&&Server.component(record)?.startsWith("com.tencent.mobileqq/")==true) {
                    configDiagnostics--
                    YLog.info("Rear QQ config callback: active=$active enabled=${settings.get().enabled} display=${Server.displayId(record)} caller=${Binder.getCallingUid()}")
                }
                if(!active||!settings.get().enabled)return@configure
                if(Server.displayId(record)!=1||ActivityHostTaskConfigurationHook.isOwnedTask(record))return@configure
                val pkg=Server.component(record)?.substringBefore('/')?:return@configure
                if(pkg in excludedPackages || pkg in setOf("android","com.android.systemui","com.xiaomi.subscreencenter","com.miui.miwallpaper","com.miui.aod","hk.uwu.reareye"))return@configure
                val info=Server.field(record,"info") as? ActivityInfo?:return@configure
                if(info.applicationInfo.uid%100000<10000)return@configure
                if(appUids.add(info.applicationInfo.uid))invalidateDisplayInfo()
                // Keep app resource density aligned with the compatibility scale. A
                // fixed physical density leaves controls oversized even when the task
                // surface itself is scaled down.
                val dpi=primaryBaseDpi
                Server.task(record)?.let {
                    synchronized(tasks) { tasks.add(it) }
                    (Server.field(it,"mTaskId") as? Int)?.let(rearTaskIds::add)
                }
                val resolved=Server.resolved(record)?:return@configure
                val window=Server.field(resolved,"windowConfiguration")?:return@configure
                val parentWindow=Server.field(parent,"windowConfiguration")?:return@configure
                val physical=(Server.call(parentWindow,"getMaxBounds") as? Rect)?.takeUnless { it.isEmpty }
                    ?:Server.call(parentWindow,"getBounds") as? Rect?:return@configure
                val safe=logicalAppBounds(physical)
                // Native minimize clears the task's freeform bounds before Shell
                // animates its leash. Keep the buffer size, but remove the old
                // display offset from the child so it is not applied twice.
                val task=Server.task(record)
                if(task!=null&&Server.call(task,"getWindowingMode")!=5&&rearTaskIds.contains(Server.field(task,"mTaskId")))
                    safe.offsetTo(0,0)
                // Activity bounds own the app viewport. Task/display bounds and native panels stay unchanged.
                window.javaClass.getMethod("setBounds",Rect::class.java).invoke(window,safe)
                window.javaClass.getMethod("setAppBounds",Rect::class.java).invoke(window,safe)
                if(dpi in 120..960&&!Server.apply(parent,resolved,dpi)&&!warned) {
                    warned=true;YLog.warn("Native rear application density unavailable")
                }
                // Use the ROM's native IME on this physical display; do not resize its UI.
                runCatching {
                    val service=Server.task(record)?.let { Server.field(it,"mAtmService") }?:return@runCatching
                    val window=Server.field(service,"mWindowManager")?:return@runCatching
                    val desired=if(settings.get().keyboard)0 else 1
                    val get=window.javaClass.getMethod("getDisplayImePolicy",Int::class.javaPrimitiveType)
                    if(get.invoke(window,1)==desired)return@runCatching
                    val identity=Binder.clearCallingIdentity()
                    try { window.javaClass.getMethod("setDisplayImePolicy",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(window,1,desired) }
                    finally { Binder.restoreCallingIdentity(identity) }
                }.onFailure { if(!warned) { warned=true;YLog.warn("Native rear IME policy unavailable",it) } }
            }
            current=configure
            main.post {
                if(!active)return@post
                // The system looper starts after display/package services. Also
                // initialize before adopting an IME token after a hot update.
                readSettings()?.let(settings::set)
                runCatching {
                    val service=Server.service(javaClass.classLoader)?:return@runCatching
                    val root=Server.field(service,"mRootWindowContainer")?:return@runCatching
                    val lock=Server.field(service,"mGlobalLock")?:return@runCatching
                    val visit=java.util.function.Consumer<Any> { task ->
                        if(Server.displayId(task)==1&&Server.call(task,"isActivityTypeHome")!=true)
                            synchronized(tasks) { tasks.add(task) }
                    }
                    val walk=generateSequence<Class<*>>(root.javaClass) { it.superclass }
                        .flatMap { it.declaredMethods.asSequence() }.first {
                            it.name=="forAllLeafTasks"&&it.parameterCount in 1..2&&it.parameterTypes[0]==java.util.function.Consumer::class.java
                        }.apply { isAccessible=true }
                    synchronized(lock) {
                        if(walk.parameterCount==1)walk.invoke(root,visit) else walk.invoke(root,visit,true)
                        // Preserve an already visible IME across a module update. Its token
                        // may otherwise have no reason to resolve again at the same app DPI.
                        val display=root.javaClass.getDeclaredMethod("getDisplayContent",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(root,1)
                        val ime=display?.let { Server.field(it,"mImeWindow") }
                        val token=ime?.let { Server.field(it,"mToken") }
                        val parent=token?.let { Server.call(it,"getParent") }
                        val config=parent?.let { Server.call(it,"getConfiguration") } as? Configuration
                        if(token!=null&&config!=null)token.javaClass.getMethod("onConfigurationChanged",Configuration::class.java).invoke(token,config)
                    }
                    // The retired generation restores old overrides during hot reload. Adopt
                    // existing rear tasks and re-resolve them through the newly installed hook.
                    Server.restore(synchronized(tasks) { tasks.toList() }) { YLog.warn("Rear app adoption refresh unavailable") }
                    synchronized(tasks) { tasks.toList() }.forEach(::refreshRearTaskGeometry)
                }.onFailure { YLog.warn("Existing rear tasks unavailable",it) }
            }
            fun refreshImeGeometry() {
                val service=Server.service(javaClass.classLoader)?:return
                val lock=Server.field(service,"mGlobalLock")?:return
                val root=Server.field(service,"mRootWindowContainer")?:return
                synchronized(lock) {
                    val display=root.javaClass.getDeclaredMethod("getDisplayContent",Int::class.javaPrimitiveType)
                        .apply { isAccessible=true }.invoke(root,1)?:return@synchronized
                    val token=Server.field(display,"mImeWindow")?.let { Server.field(it,"mToken") }?:return@synchronized
                    val parent=Server.call(token,"getParent")?:return@synchronized
                    val configuration=Server.call(parent,"getConfiguration") as? Configuration?:return@synchronized
                    token.javaClass.getMethod("onConfigurationChanged",Configuration::class.java).invoke(token,configuration)
                    Server.call(display,"setLayoutNeeded")
                    val wm=Server.field(service,"mWindowManager")
                    wm?.let { Server.field(it,"mWindowPlacerLocked") }?.let { Server.call(it,"requestTraversal") }
                }
            }
            var preferencesSubscription:AutoCloseable?=null
            val settingKeys=setOf(ConfigKeys.REAR_APP_ENABLED,ConfigKeys.REAR_APP_DPI,ConfigKeys.REAR_APP_KEYBOARD,
                ConfigKeys.REAR_APP_REGION,ConfigKeys.ACTIVITIES_WHITELIST_APPS,ConfigKeys.REAR_APP_ROUNDED,ConfigKeys.REAR_APP_CORNER_RADIUS)
            val refresh=object:Runnable {
                override fun run() {
                    if(!active||reloading)return
                    val next=readSettings()
                    val previous=if(next!=null)settings.getAndSet(next) else null
                    if(next!=null&&next!=previous) {
                        invalidateDisplayInfo()
                        if(previous==null||next.copy(imeUid=previous.imeUid)!=previous) {
                            synchronized(tasks) { tasks.toList() }.forEach(::refreshRearTaskGeometry)
                            Server.restore(synchronized(tasks) { tasks.toList() }) { YLog.warn("Rear app density refresh unavailable") }
                            refreshImeGeometry()
                        }
                    }
                    if(preferencesSubscription==null)preferencesSubscription=hookPrefs.observe { key ->
                        if(key==null||key in settingKeys) { main.removeCallbacks(this);main.postDelayed(this,100) }
                    }
                    if(preferencesSubscription==null)main.postDelayed(this,3000)
                }
            }
            val imeSettingsObserver=object:android.database.ContentObserver(main) {
                override fun onChange(selfChange:Boolean) { main.removeCallbacks(refresh);main.post(refresh) }
            }
            val contentResolver=hookSystemContext.contentResolver
            main.post {
                if(active) {
                    contentResolver.registerContentObserver(
                        android.provider.Settings.Secure.getUriFor(android.provider.Settings.Secure.DEFAULT_INPUT_METHOD),false,imeSettingsObserver)
                    main.post(refresh)
                }
            }
            runtime.onClose {
                active=false;main.removeCallbacks(refresh)
                preferencesSubscription?.close();preferencesSubscription=null
                contentResolver.unregisterContentObserver(imeSettingsObserver)
                if(current===configure)current=null
                imeBounds.clear()
                imeLayoutPolicies.forEach { (attrs,policy)->setFitTypes.invoke(attrs,policy.first);attrs.layoutInDisplayCutoutMode=policy.second }
                imeLayoutPolicies.clear()
                appImeResize.forEach { (attrs,original) ->
                    if(!original)privateFlags.setInt(attrs,privateFlags.getInt(attrs) and insetParentByIme.inv())
                }
                appImeResize.clear()
                appBarInsets.forEach { (attrs,types)->setFitTypes.invoke(attrs,types) };appBarInsets.clear()
                blurredWindows.forEach { (attrs,original) ->
                    if(!original.first)attrs.flags=attrs.flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    attrs.setBlurBehindRadius(original.second)
                };blurredWindows.clear()
                nativeImeAvoidance.forEach { (stack,original) ->
                    stack.javaClass.getDeclaredField("mNeedAvoidFrameForIme").apply { isAccessible=true }.setBoolean(stack,original)
                };nativeImeAvoidance.clear()
                minimizingTasks.clear()
                appUids.clear();invalidateDisplayInfo()
                clippedTasks.toList().forEach { clipTask(it,null,0f) };clippedTasks.clear()
                val previousTasks=synchronized(tasks) { tasks.toList().also { tasks.clear() } }
                // Keep app sizes stable while the next generation adopts the tasks.
                // A real unload still restores the original configuration.
                if(!reloading)Server.restore(previousTasks) {}
            }
            YLog.info("Native rear app density hook installed: enabled=${settings.get().enabled}")
        }
    }
}
