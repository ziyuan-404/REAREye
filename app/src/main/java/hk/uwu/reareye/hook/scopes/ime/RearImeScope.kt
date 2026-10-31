package hk.uwu.reareye.hook.scopes.ime

import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Path
import android.inputmethodservice.InputMethodService
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowMetrics
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Outline
import android.graphics.Region
import android.view.View
import android.view.ViewOutlineProvider
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

/** Match the IME engine's default-display APIs to its actual rear window viewport. */
class RearImeScope : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadApp("com.baidu.input_mi") {
            if(processName!="com.baidu.input_mi")return@loadApp
            YLog.info("[REAREye-IME] scope loaded process=$processName")
            var active=true
            var currentIme=java.lang.ref.WeakReference<InputMethodService>(null)
            runtime.onClose { active=false }
            data class Viewport(val width:Int,val height:Int,val dpi:Int)
            val globalClass="android.hardware.display.DisplayManagerGlobal".toClass()
            val global=globalClass.getDeclaredMethod("getInstance").invoke(null)
            val displayInfo=globalClass.getDeclaredMethod("getDisplayInfo",Int::class.javaPrimitiveType).apply { isAccessible=true }
            var logged=false
            fun viewport():Viewport? {
                if(!active)return null
                // The rear display's cached info can outlive its IME token. Use the
                // actual keyboard window, so a main-screen session never inherits it.
                // Resources are queried while DecorView is being constructed.
                // getDecorView() would recursively construct it from this hook.
                if(currentIme.get()?.window?.window?.peekDecorView()?.display?.displayId!=1)return null
                return runCatching {
                    val info=displayInfo.invoke(global,1)?:return@runCatching null
                    // Only the active rear IME override has no physical camera cutout.
                    // Otherwise leave the main-screen keyboard's resource APIs alone.
                    if(Server.field(info,"displayCutout")!=null)return@runCatching null
                    val width=Server.field(info,"logicalWidth") as? Int?:return@runCatching null
                    val height=Server.field(info,"logicalHeight") as? Int?:return@runCatching null
                    val dpi=Server.field(info,"logicalDensityDpi") as? Int?:return@runCatching null
                    if(width<=0||height<=0||dpi !in 120..960)return@runCatching null
                    if(!logged) { logged=true;YLog.info("Rear IME engine viewport: ${width}x$height at $dpi DPI") }
                    Viewport(width,height,dpi)
                }.getOrNull()
            }
            fun adjust(metrics:DisplayMetrics,v:Viewport) {
                val fontScale=if(metrics.density>0f)metrics.scaledDensity/metrics.density else 1f
                metrics.widthPixels=v.width;metrics.heightPixels=v.height
                metrics.densityDpi=v.dpi;metrics.density=v.dpi/160f
                @Suppress("DEPRECATION")
                metrics.scaledDensity=metrics.density*fontScale
                listOf("noncompatWidthPixels" to v.width,"noncompatHeightPixels" to v.height,"noncompatDensityDpi" to v.dpi).forEach { (field,value) ->
                    runCatching { DisplayMetrics::class.java.getField(field).setInt(metrics,value) }
                }
                listOf("noncompatDensity" to metrics.density,"noncompatScaledDensity" to metrics.scaledDensity).forEach { (field,value) ->
                    runCatching { DisplayMetrics::class.java.getField(field).setFloat(metrics,value) }
                }
            }
            Resources::class.java.resolve().firstMethod { name="getDisplayMetrics";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    val v=viewport()?:return@after
                    val original=result<DisplayMetrics>()?:return@after
                    result=DisplayMetrics().apply { setTo(original);adjust(this,v) }
                }
            }
            Resources::class.java.resolve().firstMethod { name="getConfiguration";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after {
                    val v=viewport()?:return@after
                    val original=result<Configuration>()?:return@after
                    val copy=Configuration(original)
                    copy.densityDpi=v.dpi
                    copy.screenWidthDp=v.width*160/v.dpi;copy.screenHeightDp=v.height*160/v.dpi
                    copy.smallestScreenWidthDp=minOf(copy.screenWidthDp,copy.screenHeightDp)
                    // This near-square panel needs Baidu's full-width phone key layout,
                    // not the inset landscape layout intended for a wide main screen.
                    copy.orientation=Configuration.ORIENTATION_PORTRAIT
                    val window=Server.field(copy,"windowConfiguration")
                    if(window!=null)listOf("setBounds","setAppBounds","setMaxBounds").forEach {
                        window.javaClass.getMethod(it,Rect::class.java).invoke(window,Rect(0,0,v.width,v.height))
                    }
                    result=copy
                }
            }
            listOf("getMetrics","getRealMetrics").forEach { methodName ->
                Display::class.java.resolve().firstMethod { name=methodName;parameters(DisplayMetrics::class.java) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after { val v=viewport()?:return@after;(args(0).value as? DisplayMetrics)?.let { adjust(it,v) } }
                }
            }
            listOf("getSize","getRealSize").forEach { methodName ->
                Display::class.java.resolve().firstMethod { name=methodName;parameters(Point::class.java) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after { val v=viewport()?:return@after;(args(0).value as? Point)?.set(v.width,v.height) }
                }
            }
            WindowMetrics::class.java.resolve().firstMethod { name="getBounds";parameterCount=0 }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                after { val v=viewport()?:return@after;result=Rect(0,0,v.width,v.height) }
            }
            val imeServiceClass="com.baidu.input_mi.ImeService".toClass()
            listOf("onStartInput", "onStartInputView", "onConfigurationChanged", "onCreate").forEach { methodName ->
                imeServiceClass.declaredMethods.filter { it.name==methodName }.forEach { method ->
                    imeServiceClass.resolve().firstMethod { name=methodName;parameters(*method.parameterTypes) }.hook {
                        hotReloadPolicy=HotReloadPolicy.REINSTALL
                        before { (instance as? InputMethodService)?.let { currentIme=java.lang.ref.WeakReference(it) } }
                    }
                }
            }
            listOf(InputMethodService::class.java,imeServiceClass).forEach { service ->
                service.resolve().firstMethodOrNull { name="onEvaluateFullscreenMode";parameterCount=0 }?.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    before { if(viewport()!=null)result=false }
                }
            }
            // Keep Baidu's native standard keyboard layout on this small display.
            // Its saved primary-screen floating position otherwise leaves wide gutters.
            runCatching {
                // Never falsify isFloating(): the native state/model and saved
                // preference must agree or the toolbar and voice keys get disabled.
                val model="my.j".toClass()
                val standard=imeServiceClass.getMethod("getStandardCandState")
                val floating=imeServiceClass.getMethod("getFloatCandState")
                val change=imeServiceClass.getMethod("changeCandState",standard.returnType)
                val refresh="cw.v".toClass().getDeclaredMethod("h",Boolean::class.javaPrimitiveType)
                val metrics="dj0.p".toClass().getDeclaredMethod("j",android.content.Context::class.java,Resources::class.java)
                var lastRear=false
                var restoreMain=false
                fun standardMode(ime:Any) {
                    val current=Server.call(ime,"getCurentState")
                    val floatingVoice=Server.call(ime,"getTinyVoiceFloatCandState")
                    val voice=if(floatingVoice!=null&&current===floatingVoice)
                        Server.call(ime,"getTinyVoiceCandState") else null
                    val state=voice?:standard.invoke(ime)
                    model.getMethod("b",standard.returnType).invoke(null,state)
                    model.getMethod("n",Boolean::class.javaPrimitiveType).invoke(null,false)
                    refresh.invoke(null,true)
                    if(voice!=null||current===floating.invoke(ime)||
                        current===Server.call(ime,"getGameFloatCandState"))change.invoke(ime,state)
                }
                imeServiceClass.resolve().firstMethod { name="onStartInputView";parameterCount=1 }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    before {
                        val rear=viewport()!=null
                        val ime=instance as? InputMethodService
                        restoreMain=lastRear&&!rear
                        if((rear||lastRear)&&ime!=null) {
                            if(rear!=lastRear)standardMode(ime)
                            metrics.invoke(null,ime,ime.resources)
                            refresh.invoke(null,true)
                        }
                        lastRear=rear
                    }
                    after {
                        val ime=instance?:return@after
                        if(restoreMain) { standardMode(ime);restoreMain=false;return@after }
                        if(viewport()==null)return@after
                        val current=Server.call(ime,"getCurentState")
                        if(current===floating.invoke(ime))standardMode(ime)
                    }
                }
                imeServiceClass.resolve().firstMethod { name="onConfigurationChanged";parameterCount=1 }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        val rear=viewport()!=null
                        val ime=instance as? InputMethodService?:return@after
                        if(rear!=lastRear) {
                            standardMode(ime)
                            metrics.invoke(null,ime,ime.resources)
                            refresh.invoke(null,true)
                            lastRear=rear
                        }
                    }
                }
            }.onFailure { YLog.warn("Rear standard keyboard state unavailable",it) }
            // No navigation-bar window exists in the rear IME viewport. Do not
            // reserve the front display's gesture-navigation height on the bottom
            // or side of Baidu's standard layout.
            runCatching {
                val navigation="ch.a".toClass()
                navigation.declaredMethods.filter { it.name in setOf("d","e","f")&&it.returnType==Int::class.javaPrimitiveType&&it.parameterCount<=1 }.forEach { method->
                    navigation.resolve().firstMethod { name=method.name;parameters(*method.parameterTypes) }.hook {
                        hotReloadPolicy=HotReloadPolicy.REINSTALL
                        before { if(viewport()!=null)result=0 }
                    }
                }
            }.onFailure { YLog.warn("Rear keyboard navigation insets unavailable",it) }
            // These are Xiaomi's switch-keyboard/clipboard buttons, not Baidu
            // keys. Use the plugin's own disabled-bottom layout so its entire
            // row (including its reserved height/insets) disappears on the rear.
            runCatching {
                val injector="android.inputmethodservice.InputMethodServiceInjector".toClass()
                var bottomManager:Class<*>?=null
                fun installBottomPolicy() {
                    if(bottomManager!=null)return
                    val loader=injector.getDeclaredField("sClassLoader").apply { isAccessible=true }.get(null) as? ClassLoader?:return
                    val manager=Class.forName("com.miui.inputmethod.InputMethodBottomManager",false,loader)
                    val util=Class.forName("com.miui.inputmethod.InputMethodUtil",false,loader)
                    val canShow=util.getDeclaredField("sCanShowMiuiBottom").apply { isAccessible=true }
                    val handleInsets=manager.getDeclaredField("sNeedHandleComputeInsets").apply { isAccessible=true }
                    var savedHandleInsets:Boolean?=null
                    manager.resolve().firstMethod { name="updateMiuiBottomEnableValue";parameters(android.content.Context::class.java) }.hook {
                        hotReloadPolicy=HotReloadPolicy.REINSTALL
                        after {
                            if(viewport()!=null) {
                                if(savedHandleInsets==null)savedHandleInsets=handleInsets.getBoolean(null)
                                canShow.setBoolean(null,false)
                                handleInsets.setBoolean(null,false)
                            } else {
                                savedHandleInsets?.let { saved ->
                                    handleInsets.setBoolean(null,saved)
                                    savedHandleInsets=null
                                }
                            }
                        }
                    }
                    bottomManager=manager
                    // Suppressed flags are static in the plugin. Re-read its native
                    // settings on return, rather than leaking rear-only flags to main.
                    listOf("onStartInputView","onConfigurationChanged").forEach { methodName ->
                        imeServiceClass.resolve().firstMethod { name=methodName;parameterCount=1 }.hook {
                            hotReloadPolicy=HotReloadPolicy.REINSTALL
                            after {
                                if(viewport()==null) {
                                    val context=instance as? android.content.Context?:return@after
                                    runCatching {
                                        manager.getDeclaredMethod("updateMiuiBottomEnableValue",android.content.Context::class.java)
                                            .apply { isAccessible=true }.invoke(null,context)
                                    }.onFailure { YLog.warn("Main keyboard bottom-row restore unavailable",it) }
                                }
                            }
                        }
                    }
                }
                injector.resolve().firstMethod { name="reflectModule";parameterCount=4 }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    before { if(active)installBottomPolicy() }
                }
                installBottomPolicy()
                runtime.onClose {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        bottomManager?.let { manager->
                            val context=runCatching { manager.getDeclaredField("mContext").apply { isAccessible=true }.get(null) }.getOrNull()
                            if(context!=null)runCatching {
                                manager.getDeclaredMethod("updateMiuiBottomEnableValue",android.content.Context::class.java)
                                    .apply { isAccessible=true }.invoke(null,context)
                            }
                        }
                    }
                }
            }.onFailure { YLog.warn("Rear keyboard bottom-row policy unavailable",it) }
            data class OutlineState(val provider:ViewOutlineProvider?,val clipped:Boolean)
            val outlines=java.util.WeakHashMap<View,OutlineState>()
            // Leave a narrow physical edge for the existing 62px native Back
            // recognizer. Reserve only the initial touch edge, so the last key
            // column stays tappable across the rest of that native gesture strip.
            val rearBackGestureWidth=8
            // Baidu's transition can drive the IME DecorView alpha to zero while
            // leaving the input connection and hit region active. Guard only the
            // IME root on the rear display so its normal child animations remain
            // untouched.
            View::class.java.resolve().firstMethodOrNull {
                name="setAlpha";parameters(Float::class.javaPrimitiveType!!)
            }?.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before {
                    val view=instance as? View?:return@before
                    if(view.javaClass.name=="com.android.internal.policy.DecorView" &&
                        view.display?.displayId==1 && viewport()!=null && (args(0).value as? Float?:1f)<1f) {
                        args(0).value=1f
                    }
                }
            }
            fun restoreOutline(view:View) {
                val saved=outlines.remove(view)?:return
                view.outlineProvider=saved.provider;view.clipToOutline=saved.clipped
            }
            listOf(InputMethodService::class.java,imeServiceClass).forEach { service ->
                service.resolve().firstMethodOrNull {
                    name="onComputeInsets";parameters(InputMethodService.Insets::class.java)
                }?.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                    val ime=instance as? InputMethodService?:return@after
                    val decor=ime.window?.window?.decorView?:return@after
                    if(viewport()==null) { restoreOutline(decor);return@after }
                    val insets=args(0).value as? InputMethodService.Insets?:return@after
                    if(!outlines.containsKey(decor))outlines[decor]=OutlineState(decor.outlineProvider,decor.clipToOutline)
                    // Clip the full window, never contentTopInsets: that value can
                    // temporarily equal the entire height during a native rebuild.
                    val prefs=hookPrefs
                    val physical=ime.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(1)
                    val radius=if(!prefs.getBoolean(ConfigKeys.REAR_APP_ROUNDED,true))0f
                        else prefs.getFloat(ConfigKeys.REAR_APP_CORNER_RADIUS,RearAppDefaults.CORNER_RADIUS).takeIf { it>0f }
                            ?:physical?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)?.radius?.toFloat()?:0f
                    decor.outlineProvider=object:ViewOutlineProvider() {
                        override fun getOutline(view:View,outline:Outline) {
                            if(view.width<=0||view.height<=0)return
                            val r=minOf(radius,view.width/2f,view.height/2f)
                            val path=Path().apply { addRoundRect(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),
                                floatArrayOf(0f,0f,0f,0f,r,r,r,r),Path.Direction.CW) }
                            outline.setPath(path)
                        }
                    }
                    decor.clipToOutline=radius>0f
                    decor.invalidateOutline()

                    // Keep the physical rear gesture strip available while the
                    // keyboard remains touchable everywhere else. Baidu normally
                    // supplies a REGION inset, but its region covers the whole safe
                    // window on this display.
                    val right=(decor.width-rearBackGestureWidth).coerceAtLeast(1)
                    val bottom=(decor.height-8).coerceAtLeast(1)
                    val usable=Rect(0,0,right,bottom)
                    when(insets.touchableInsets) {
                        InputMethodService.Insets.TOUCHABLE_INSETS_REGION ->
                            insets.touchableRegion.op(usable,Region.Op.INTERSECT)
                        InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT ->
                            insets.touchableRegion.set(Rect(0,insets.contentTopInsets.coerceIn(0,bottom),right,bottom))
                        InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE ->
                            insets.touchableRegion.set(Rect(0,insets.visibleTopInsets.coerceIn(0,bottom),right,bottom))
                        else->insets.touchableRegion.set(usable)
                    }
                    insets.touchableInsets=InputMethodService.Insets.TOUCHABLE_INSETS_REGION
                }
            }
            }
            runtime.onClose {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    outlines.keys.toList().forEach(::restoreOutline)
                }
            }
        }
    }
}
