package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.graphics.Rect
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys

/** Supply origins to Xiaomi's existing Folme states; the system renders and owns the animation. */
class RearSourceAnimationHook:RoxyHooker() {
    private val excludedPackages = setOf("com.android.camera", "com.kobbin.leicaflavor", "xyz.shuncongci.leica")
    override fun PackageScope.onHook() {
        loadApp("com.android.systemui") {
            var active=true
            runtime.onClose { active=false }
            val prefs=hookPrefs
            data class Frame(val task:Int,val origin:RearLaunchOrigin?,val opening:Boolean,val transaction:Any?,val leash:Any?,val bounds:Rect?)
            val frames=ThreadLocal<kotlin.collections.ArrayDeque<Frame>>()
            val taskClass="android.app.TaskInfo".toClass()
            val animation="com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAnimation".toClass()
            val openingMethods=setOf("startMoveToFrontAnimation","startFullScreenToFreeformAnimation")
            val closingMethods=setOf("startMoveToBackCloseAnimation","startCloseAnimation")
            animation.declaredMethods.filter { it.name in openingMethods||it.name in closingMethods }.forEach { method ->
                animation.resolve().firstMethod { name=method.name;parameters(*method.parameterTypes) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    before {
                        val info=args.filterNotNull().firstNotNullOfOrNull { arg ->
                            when {
                                taskClass.isInstance(arg)->arg
                                arg.javaClass.simpleName=="MiuiFreeformModeTaskInfo"->Server.call(arg,"getTaskInfo")
                                else->null
                            }
                        }
                        val packageName = info?.let { Server.component(it)?.substringBefore('/') }
                        val eligible=active&&info!=null&&packageName !in excludedPackages&&
                            Server.field(info,"displayId")==1&&prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)
                        val origin=if(eligible)RearLaunchOrigin.fromTask(info!!) else null
                        val change=args.filterNotNull().firstOrNull { it.javaClass.name=="android.window.TransitionInfo\$Change" }
                        val transaction=args.filterNotNull().firstOrNull { it.javaClass.name=="android.view.SurfaceControl\$Transaction" }
                        val nativeInfo=args.filterNotNull().firstOrNull { it.javaClass.simpleName=="MiuiFreeformModeTaskInfo" }
                        val bounds=(nativeInfo?.let { Server.call(it,"getTaskBounds") } as? Rect)
                            ?:change?.let { Server.call(it,if(method.name in openingMethods)"getEndAbsBounds" else "getStartAbsBounds") } as? Rect
                        val stack=frames.get()?:kotlin.collections.ArrayDeque<Frame>().also { frames.set(it) }
                        stack.addLast(Frame(if(info!=null)Server.field(info,"taskId") as? Int?:-1 else -1,origin,method.name in openingMethods,transaction,change?.let { Server.call(it,"getLeash") },bounds?.let(::Rect)))
                    }
                    after {
                        frames.get()?.let { if(it.isNotEmpty())it.removeLast();if(it.isEmpty())frames.remove() }
                    }
                }
            }
            val control="com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeControl".toClass()
            val cornerProperties=listOf("TL_X","TL_Y","TR_X","TR_Y","BR_X","BR_Y","BL_X","BL_Y")
                .map { "CORNER_RADII_$it" }
            val properties=(listOf("CENTER_X","ELEGANT_Y","ANCHOR_Y","WIDTH","HEIGHT","RADIUS","ALPHA","POSITION_Z","ROTATE_X")+cornerProperties)
                .associateWith { control.getField("FOLME_$it").get(null) }
            val property="com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeProperty".toClass()
            val valueProperty=property.getField("valueProperty")
            val start=property.getField("startValue")
            val end=property.getField("endValue")
            val propertyConfig=property.getField("animConfig")
            val nativeEase="com.android.wm.shell.multitasking.common.animation.MultiTaskingEaseManager".toClass()
            val iconEase=nativeEase.getField("APP_ICON_EASE").get(null)
            val alphaEase=nativeEase.getField("PIP_OUT_ALPHA_EASE").get(null)
            val stateClass="com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeState".toClass()
            val easeType="com.android.miui.miuixx.animation.utils.EaseManager\$EaseStyle".toClass()
            val addProperty=stateClass.getMethod("addProperty",
                control.getField("FOLME_ALPHA").type,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,easeType)
            val setEase=stateClass.getMethod("setEase",easeType)
            var receipts=4
            "com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeAnimExecutor".toClass().resolve().firstMethod {
                name="executeFolmeAnimation";parameterCount=1
            }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    if(!active)return@before
                    val frame=frames.get()?.lastOrNull()?:return@before
                    val origin=frame.origin?:return@before
                    val state=args(0).value?:return@before
                    if(frame.opening&&Server.call(state,"getAnimationType")!=2)return@before
                    if(Server.call(state,"getTaskId")!=frame.task)return@before
                    val type=Server.call(state,"getMultiTaskingAnimationType") as? Int?:return@before
                    if(type !in setOf(2,13,16,20))return@before
                    val map=Server.call(state,"getFolmeProperty") as? Map<*,*>?:return@before
                    val entries=map.values.filterIsInstance<List<*>>().flatten().filterNotNull()
                    fun entry(name:String)=entries.firstOrNull { valueProperty.get(it)===properties[name] }
                    fun from(name:String)=entry(name)?.let(start::getFloat)?:0f
                    fun to(name:String)=entry(name)?.let(end::getFloat)?:0f
                    val originalStartY=from("ELEGANT_Y")+from("HEIGHT")*(.5f-from("ANCHOR_Y")/10f)
                    val originalEndY=to("ELEGANT_Y")+to("HEIGHT")*(.5f-to("ANCHOR_Y")/10f)
                    fun set(name:String,a:Float,b:Float) { entry(name)?.let { start.setFloat(it,a);end.setFloat(it,b) } }
                    fun geometryEase(name:String) { entry(name)?.let { propertyConfig.set(it,null) } }
                    val area=origin.bounds
                    if(frame.opening) {
                        set("CENTER_X",area.exactCenterX(),to("CENTER_X"))
                        set("ELEGANT_Y",area.exactCenterY(),originalEndY)
                        set("WIDTH",area.width().toFloat(),to("WIDTH"));set("HEIGHT",area.height().toFloat(),to("HEIGHT"))
                        set("RADIUS",origin.radius,to("RADIUS"))
                        // A fullscreen-to-freeform state already contains eight
                        // independent corners. Those override FOLME_RADIUS in Shell.
                        cornerProperties.forEach { name->set(name,origin.radius,to(name));geometryEase(name) }
                        listOf("CENTER_X","ELEGANT_Y","WIDTH","HEIGHT","RADIUS").forEach(::geometryEase)
                        setEase.invoke(state,iconEase)
                        // Let the ROM create its own AnimSpecialConfig. Its optimized
                        // subclass has no reflectively callable constructor on this ROM.
                        entry("ALPHA")?.let { old->map.values.forEach { values->
                            @Suppress("UNCHECKED_CAST")
                            (values as? MutableList<Any?>)?.remove(old)
                        } }
                        addProperty.invoke(state,properties["ALPHA"],0f,1f,alphaEase)
                        // Keep the native independent-corner control consistent
                        // before its first Folme update, as well as the state values.
                        if(cornerProperties.any { entry(it)!=null }) {
                            Server.call(state,"getFolmeControl")?.let { fc->
                                control.getMethod("setFolmeCornerRadii",FloatArray::class.java)
                                    .invoke(fc,FloatArray(8) { origin.radius })
                            }
                        }
                    } else {
                        set("CENTER_X",from("CENTER_X"),area.exactCenterX())
                        set("ELEGANT_Y",originalStartY,area.exactCenterY())
                        set("WIDTH",from("WIDTH"),area.width().toFloat());set("HEIGHT",from("HEIGHT"),area.height().toFloat())
                        set("RADIUS",from("RADIUS"),origin.radius)
                    }
                    set("ANCHOR_Y",5f,5f);set("ROTATE_X",0f,0f);set("POSITION_Z",0f,0f)
                    // Shell applies its initial transaction before Folme's first
                    // frame. It must use the same origin, scale and rotation.
                    val tx=frame.transaction
                    val leash=frame.leash
                    val bounds=frame.bounds
                    if(tx!=null&&leash!=null&&bounds!=null&&!bounds.isEmpty) {
                        val surface=leash.javaClass
                        val width=from("WIDTH");val height=from("HEIGHT")
                        val scaleX=width/bounds.width();val scaleY=height/bounds.height()
                        tx.javaClass.getMethod("setPosition",surface,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                            .invoke(tx,leash,from("CENTER_X")-width/2f,from("ELEGANT_Y")-height/2f)
                        tx.javaClass.getMethod("setScale",surface,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                            .invoke(tx,leash,scaleX,scaleY)
                        tx.javaClass.getMethod("setCornerRadius",surface,Float::class.javaPrimitiveType)
                            .invoke(tx,leash,from("RADIUS")/scaleX.coerceAtLeast(.001f))
                        if(frame.opening)tx.javaClass.getMethod("setAlpha",surface,Float::class.javaPrimitiveType)
                            .invoke(tx,leash,0f)
                        tx.javaClass.getMethod("setSurfaceRotation",surface,FloatArray::class.java,FloatArray::class.java,FloatArray::class.java,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                            .invoke(tx,leash,floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f),floatArrayOf(0f,0f,0f),30f,5500f)
                    }
                    if(receipts>0) { receipts--;YLog.info("Rear native origin animation: task=${frame.task} open=${frame.opening} source=$area") }
                    else if(prefs.getBoolean(ConfigKeys.MORE_DEBUG,false))YLog.debug("Rear native origin animation: task=${frame.task} open=${frame.opening} source=$area")
                }
            }
            YLog.info("Rear native origin animation hook installed")
        }
    }
}
