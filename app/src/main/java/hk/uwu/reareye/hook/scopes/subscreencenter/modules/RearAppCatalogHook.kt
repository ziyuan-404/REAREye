package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.graphics.drawable.Drawable
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import org.json.JSONObject
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.Consumer

/** Adds launch shortcuts to copies delivered to the stock picker, never its saved card list. */
@OptIn(DexKitExperimentalApi::class)
class RearAppCatalogHook : RoxyHooker() {
    @Volatile private var quiescing=false
    override fun onHotReloadQuiesce() { quiescing=true }

    override fun PackageScope.onHook() {
        quiescing=false
        loadApp("com.xiaomi.subscreencenter") {
            val scope=this
            var active = true
            runtime.onClose { active = false }
            val appDrawables=Collections.synchronizedMap(WeakHashMap<ImageView,Drawable>())
            ImageView::class.java.resolve().firstMethod { name="setImageDrawable";parameters(Drawable::class.java) }.hook {
                hotReloadPolicy=HotReloadPolicy.REINSTALL
                before { if(active)appDrawables[instance as? ImageView]?.let { args(0).value=it } }
            }
            runtime.onClose { appDrawables.clear() }
            runCatching {
                val bridge = runtime.manage(createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = resolveHookPackageVersionCode(
                        hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir,
                    ),
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                ))
                val managerName = resolveDexKitClassValue(bridge, "SSC_REAR_CATALOG_MANAGER") {
                    findClass { matcher { usingStrings(
                        "LauncherDataManager", "convertWidgetToLauncherInfo: widget = ",
                        "saveAppInfoList: from: ",
                    ) } }.singleOrNull()
                } ?: error("Stock launcher data manager missing")
                val modelName = resolveDexKitClassValue(bridge, "SSC_REAR_CATALOG_MODEL") {
                    findClass { matcher { usingStrings(
                        "LauncherAppInfo{mAppName='", "mMamlPath='", "mAppCardId='", "mBindApp='",
                    ) } }.singleOrNull()
                } ?: error("Stock launcher shortcut model missing")
                val widgetName = resolveDexKitClassValue(bridge, "SSC_REAR_CATALOG_WIDGET") {
                    findClass { matcher { usingStrings(
                        "__PIN_CONTENT_IMAGE__", "__PIN_CONTENT_TEXT__", "mtzSnapshotPath",
                    ) } }.singleOrNull()
                } ?: error("Stock launcher widget model missing")
                val factoryPoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_CATALOG_FACTORY") {
                    findMethod { matcher {
                        declaredClass = widgetName
                        paramTypes("java.lang.String", "java.lang.String", "int", "java.lang.String",
                            "java.lang.String", "java.lang.String", "android.os.Bundle")
                        returnType = widgetName
                        usingStrings("resId", "appName", "appCardType", "appIconPath")
                    } }.singleOrNull()
                } ?: error("Stock launcher widget factory missing")
                val convertPoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_CATALOG_CONVERT") {
                    findMethod { matcher {
                        declaredClass = managerName
                        paramTypes(widgetName, modelName)
                        returnType = modelName
                        usingStrings("resId", "bindApp", "appCardType", "appName")
                    } }.singleOrNull()
                } ?: error("Stock launcher shortcut converter missing")
                val loadPoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_CATALOG_LOAD") {
                    findMethod { matcher {
                        declaredClass = managerName
                        paramTypes("java.util.function.Consumer")
                        returnType = "void"
                    } }.singleOrNull()
                } ?: error("Stock launcher initial callback missing")
                val updatePoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_CATALOG_UPDATE") {
                    findMethod { matcher {
                        paramTypes("java.util.ArrayList", "java.lang.String", modelName)
                        returnType = "void"
                        usingStrings("updateApps: size=", "onLauncherDataChanged: size=")
                    } }.singleOrNull()
                } ?: error("Stock launcher update callback missing")
                val controllerName = resolveDexKitClassValue(bridge, "SSC_REAR_CATALOG_CONTROLLER") {
                    findClass { matcher { usingStrings(
                        "LauncherController", "isChangBaAppSupportRearScreen support = ",
                    ) } }.singleOrNull()
                } ?: error("Stock launcher controller missing")
                val controllerClass = controllerName.toClass()

                fun isPickerCallback(callback: Any): Boolean = callback.javaClass.declaredFields.any {
                    if (it.type.isPrimitive) false else runCatching {
                        it.isAccessible = true
                        controllerClass.isInstance(it.get(callback))
                    }.getOrDefault(false)
                }
                val modelClass = modelName.toClass()
                val constructor = modelClass.getDeclaredConstructor().apply { isAccessible = true }
                val intentField = modelClass.declaredFields.single { it.type == Intent::class.java }
                    .apply { isAccessible = true }
                val factory = widgetName.toClass().declaredMethods.single {
                    it.name == factoryPoint.methodName && it.parameterCount == 7
                }.apply { isAccessible = true }
                val convert = managerName.toClass().getDeclaredMethod(
                    convertPoint.methodName, widgetName.toClass(), modelClass,
                ).apply { isAccessible = true }

                val stringFields=modelClass.declaredFields.filter { it.type==String::class.java }
                    .onEach { it.isAccessible=true }
                fun shortcutPackage(model:Any):String? = if(!modelClass.isInstance(model))null else
                    stringFields.firstNotNullOfOrNull { field ->
                        (field.get(model) as? String)?.takeIf { it.startsWith("reareye_shortcut_") }
                            ?.removePrefix("reareye_shortcut_")
                    }

                fun append(source: Collection<*>): ArrayList<Any?> {
                    // Filter our transient rows, including ones left by the previous hot-loaded
                    // classloader. This never removes native/user-saved app cards.
                    val result = ArrayList<Any?>(source.filterNot { item ->
                        item != null && shortcutPackage(item)!=null
                    })
                    if (!active || !hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, false)) return result
                    val context = hookSystemContext
                    val pm = context.packageManager
                    val selectedSet = hookPrefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)
                    val savedOrder = hookPrefs.getString(ConfigKeys.REAR_APP_ORDER_DATA)
                        .split('\n').map(String::trim).filter { it.isNotBlank() }
                    val selectionOrder = savedOrder.filter { it in selectedSet } +
                        selectedSet.filter { it !in savedOrder }.sorted()
                    val selected = when (hookPrefs.getInt(ConfigKeys.REAR_APP_ORDER_MODE, 0)) {
                        1 -> selectionOrder.sortedBy {
                            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }
                                .getOrDefault(it).lowercase()
                        }
                        2 -> selectionOrder.sorted()
                        else -> selectionOrder
                    }
                    for (pkg in selected) {
                        runCatching {
                            // A previously injected UI copy must never accumulate duplicate shortcuts.
                            if (result.any { item -> item != null && (shortcutPackage(item) == pkg ||
                                (modelClass.isInstance(item) &&
                                    (intentField.get(item) as? Intent)?.component?.packageName == pkg))
                            }) return@runCatching
                            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@runCatching
                            launch.putExtra("android.intent.extra.xspace_userid_selected", true)
                            val app = pm.getApplicationInfo(pkg, 0)
                            val extras = Bundle().apply { putString("bindApp", pkg) }
                            val widget = factory.invoke(null, "reareye_shortcut_$pkg",
                                JSONObject().put("fallback", app.loadLabel(pm).toString()).toString(),
                                3, "", null, null, extras)
                            val model = convert.invoke(null, widget, constructor.newInstance())
                                ?: return@runCatching
                            intentField.set(model,launch)
                            // Preserve the stock cards and their native order before shortcuts.
                            result.add(model)
                        }.onFailure { YLog.warn("Stock rear shortcut unavailable for $pkg", it) }
                    }
                    return result
                }

                managerName.toClass().resolve().firstMethod {
                    name = loadPoint.methodName
                    parameters(Consumer::class.java)
                }.hook { before {
                    @Suppress("UNCHECKED_CAST")
                    val original = args(0).value as? Consumer<Any?> ?: return@before
                    if (!isPickerCallback(original)) return@before
                    args(0).value = Consumer<Any?> { value ->
                        original.accept(if (value is Collection<*>) append(value) else value)
                    }
                }; id = "reareye.rear.catalog.initial"; hotReloadPolicy = HotReloadPolicy.REINSTALL }
                updatePoint.className.toClass().resolve().firstMethod {
                    name = updatePoint.methodName
                    parameterCount = 3
                }.hook { before {
                    // This shared callback also handles the primary-screen card editor. Only
                    // the branch capturing the actual rear launcher controller receives shortcuts.
                    val callback = instance ?: return@before
                    if (!isPickerCallback(callback)) return@before
                    val source = args(0).value as? Collection<*> ?: return@before
                    args(0).value = append(source)
                }; id = "reareye.rear.catalog.update"; hotReloadPolicy = HotReloadPolicy.REINSTALL }

                // The native Intent-row renderer accepts host resource icons only. Bind selected
                // application drawables after that renderer; no icon files or card records are made.
                run {
                    val adapterClass = controllerClass.declaredFields.map { it.type }.singleOrNull { type ->
                        type.declaredFields.any { it.type == ArrayList::class.java } &&
                            type.declaredMethods.any { it.parameterCount == 2 && it.parameterTypes.last() == Int::class.javaPrimitiveType }
                    }
                    val bind = adapterClass?.declaredMethods?.singleOrNull {
                        it.parameterCount == 2 && it.parameterTypes.last() == Int::class.javaPrimitiveType &&
                            it.returnType == Void.TYPE && !it.isBridge
                    }
                    if (adapterClass != null && bind != null) {
                        val listField = adapterClass.declaredFields.single { it.type == ArrayList::class.java }
                            .apply { isAccessible = true }
                        data class RowState(val height: Int,val visibility: Int,val children: List<Pair<View,Int>>,val material: Any?)
                        val modified=WeakHashMap<FrameLayout,RowState>()
                        var buildingIcons=false
                        val createHolder=adapterClass.declaredMethods.single {
                            it.parameterCount==2&&ViewGroup::class.java.isAssignableFrom(it.parameterTypes[0])&&it.parameterTypes[1]==Int::class.javaPrimitiveType&&!it.isBridge
                        }.apply { isAccessible=true }
                        val bindHolder=bind.apply { isAccessible=true }
                        fun honeycomb()=active&&hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false)&&hookPrefs.getInt(ConfigKeys.REAR_APP_HOME_STYLE,0)==1
                        fun groups(list: List<*>):List<IntRange> {
                            val result=ArrayList<IntRange>()
                            var start=0
                            while(start<list.size) {
                                val count=if(result.size%2==0)3 else 4
                                val end=minOf(start+count,list.size)
                                result.add(start until end);start=end
                            }
                            return result
                        }
                        adapterClass.resolve().firstMethod {
                            name="a";parameterCount=0;returnType=Int::class.javaPrimitiveType!!
                        }.hook {
                            id="reareye.rear.catalog.row-count"
                            hotReloadPolicy=HotReloadPolicy.REINSTALL
                            after {
                                if(honeycomb()&&!buildingIcons) {
                                    val list=listField.get(instance) as? List<*>?:return@after
                                    result=groups(list).size
                                }
                            }
                        }
                        val material="G3.c".toClass()
                        val setMaterial=material.declaredMethods.first { it.name=="a"&&it.parameterCount==2 }.apply { isAccessible=true }
                        fun materialConfig(root: FrameLayout): Any? = runCatching {
                            val value=root.javaClass.declaredFields.first {
                                java.lang.reflect.Modifier.isStatic(it.modifiers)&&android.os.Parcelable::class.java.isAssignableFrom(it.type)
                            }.apply { isAccessible=true }.get(null) as android.os.Parcelable
                            val parcel=android.os.Parcel.obtain()
                            try {
                                value.writeToParcel(parcel,0);parcel.setDataPosition(0)
                                setMaterial.parameterTypes[1].getConstructor(android.os.Parcel::class.java).newInstance(parcel)
                            } finally { parcel.recycle() }
                        }.getOrNull()
                        fun holderView(holder: Any): View? = generateSequence<Class<*>>(holder.javaClass) { it.superclass }
                            .flatMap { it.declaredFields.asSequence() }
                            .firstOrNull { it.type == View::class.java }?.let { it.isAccessible=true;it.get(holder) as? View }
                        fun restore(root: FrameLayout) {
                            firstImage(root)?.let { appDrawables.remove(it) }
                            val saved=modified.remove(root)?:return
                            (0 until root.childCount).map(root::getChildAt).filter { it is NativeRearAppIcons }.forEach(root::removeView)
                            saved.children.forEach { (child,visibility)->child.visibility=visibility }
                            root.layoutParams=root.layoutParams.apply { height=saved.height }
                            root.visibility=saved.visibility
                            root.clipToOutline=true
                            saved.material?.let { setMaterial.invoke(null,root,it) }
                        }
                        runtime.onClose { android.os.Handler(android.os.Looper.getMainLooper()).post { modified.keys.toList().forEach(::restore) } }
                        adapterClass.resolve().firstMethod { name = bind.name; parameterCount = 2 }.hook {
                            id = "reareye.rear.catalog.bind"
                            hotReloadPolicy = HotReloadPolicy.REINSTALL
                            before {
                                if(!buildingIcons) {
                                    args(0).value?.let(::holderView)?.let { root ->
                                        if(root is FrameLayout)restore(root)
                                        // Recycled stock holders must not retain a shortcut
                                        // drawable when they are rebound to an OEM card.
                                        fun clearImages(view:View) {
                                            if(view is ImageView)appDrawables.remove(view)
                                            if(view is ViewGroup)for(i in 0 until view.childCount)clearImages(view.getChildAt(i))
                                        }
                                        clearImages(root)
                                    }
                                    if(honeycomb()) {
                                        val list=listField.get(instance) as? List<*>?:return@before
                                        val range=groups(list).getOrNull(args(1).int())?:return@before
                                        args(1).value=range.first
                                    }
                                }
                            }
                            after {
                                if(buildingIcons)return@after
                                val list = listField.get(instance) as? List<*> ?: return@after
                                val model = list.getOrNull(args(1).int()) ?: return@after
                                val honeycomb=honeycomb()
                                val pkg = shortcutPackage(model)
                                val holder = args(0).value ?: return@after
                                val view = holderView(holder) ?: return@after
                                val rowRanges=if(honeycomb)groups(list) else emptyList()
                                val rowIndex=rowRanges.indexOfFirst { it.first==args(1).int() }
                                val group=rowRanges.getOrNull(rowIndex)?.mapNotNull { list[it] }
                                if(group!=null&&view is FrameLayout) {
                                    modified[view]=RowState(view.layoutParams.height,view.visibility,(0 until view.childCount).map { view.getChildAt(it).let { child->child to child.visibility } },materialConfig(view))
                                    for(i in 0 until view.childCount)view.getChildAt(i).visibility=View.GONE
                                    val originalRows=listField.get(instance)
                                    val images=ArrayList<View>()
                                    buildingIcons=true
                                    try {
                                        group.forEachIndexed { index,item ->
                                            val nativeHolder=createHolder.invoke(instance,view,0)
                                            bindHolder.invoke(instance,nativeHolder,args(1).int()+index)
                                            val nativeView=holderView(nativeHolder)?:return@forEachIndexed
                                            val image=firstImage(nativeView)?:return@forEachIndexed
                                            item?.let { shortcutPackage(it) }?.let { appPackage ->
                                                val tagId=image.resources.getIdentifier("glide_custom_view_target_tag","id",hookAppInfo.packageName)
                                                if(tagId!=0) {
                                                    image.getTag(tagId)?.let { request->request.javaClass.methods.firstOrNull { it.name=="clear"&&it.parameterCount==0 }?.apply { isAccessible=true }?.invoke(request) }
                                                    image.setTag(tagId,null)
                                                }
                                                val drawable=image.context.packageManager.getApplicationIcon(appPackage)
                                                appDrawables[image]=drawable
                                                image.setImageDrawable(drawable)
                                            }
                                            val info=View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible=true }.get(nativeView)
                                            val click=info?.javaClass?.getDeclaredField("mOnClickListener")?.apply { isAccessible=true }?.get(info) as? View.OnClickListener
                                            val launch=intentField.get(item) as? Intent
                                            val appShortcut=shortcutPackage(item)!=null
                                            nativeView.setOnClickListener { actual ->
                                                runCatching {
                                                    if(appShortcut&&launch!=null)NativeRearAppLaunch.open(actual,launch,hookPrefs)
                                                    else click?.onClick(actual)
                                                }.onFailure { YLog.warn("Native rear app launch failed",it) }
                                            }
                                            // Each native item keeps its own material background.
                                            // Only replace the horizontal label row with a centered icon.
                                            val container=nativeView as FrameLayout
                                            (image.parent as? ViewGroup)?.removeView(image)
                                            container.removeAllViews()
                                            image.scaleType=ImageView.ScaleType.FIT_CENTER
                                            val inset=12
                                            container.setPadding(inset,inset,inset,inset)
                                            container.addView(image,FrameLayout.LayoutParams(-1,-1))
                                            images.add(container)
                                        }
                                    } finally {
                                        listField.set(instance,originalRows)
                                        buildingIcons=false
                                    }
                                    val icons=NativeRearAppIcons(view.context,images,hookPrefs.getInt(ConfigKeys.REAR_APP_HOME_STYLE,1),rowIndex)
                                    view.setOnClickListener(null)
                                    // The grouping row is transparent; the individual native items
                                    // above retain the original material backgrounds.
                                    setMaterial.invoke(null,view,null)
                                    view.clipToOutline=false
                                    view.layoutParams=view.layoutParams.apply { height=ViewGroup.LayoutParams.WRAP_CONTENT }
                                    view.addView(icons,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
                                    return@after
                                }
                                if(pkg==null)return@after
                                val launch=intentField.get(model) as? Intent
                                if(launch!=null)view.setOnClickListener { actual ->
                                    runCatching { NativeRearAppLaunch.open(actual,launch,hookPrefs) }.onFailure { YLog.warn("Native rear app launch failed",it) }
                                }
                                val image = firstImage(view) ?: return@after
                                image.tag = model
                                runCatching {
                                    // Cancel the stock Glide resource-0 request before setting the
                                    // application drawable, so its asynchronous error cannot erase it.
                                    val tagId = image.resources.getIdentifier("glide_custom_view_target_tag", "id", hookAppInfo.packageName)
                                    val request = if (tagId != 0) image.getTag(tagId) else null
                                    request?.javaClass?.methods?.firstOrNull {
                                        it.name == "clear" && it.parameterCount == 0
                                    }?.apply { isAccessible = true }?.invoke(request)
                                    if (tagId != 0) image.setTag(tagId, null)
                                    val drawable=image.context.packageManager.getApplicationIcon(pkg)
                                    appDrawables[image]=drawable
                                    image.setImageDrawable(drawable)
                                }
                            }
                        }
                        val main=android.os.Handler(android.os.Looper.getMainLooper())
                        fun refreshAdapters() {
                            runCatching {
                                val windowClass=Class.forName("android.view.WindowManagerGlobal")
                                val windows=windowClass.getDeclaredMethod("getInstance").invoke(null)
                                val roots=windowClass.getDeclaredField("mViews").apply { isAccessible=true }.get(windows) as? List<*>
                                fun visit(node: View) {
                                    if(node.javaClass.name=="androidx.recyclerview.widget.RecyclerView") {
                                        val adapter=node.javaClass.getMethod("getAdapter").invoke(node)
                                        if(adapterClass.isInstance(adapter)) {
                                            val source=listField.get(adapter) as? Collection<*>?:return
                                            listField.set(adapter,append(source))
                                            adapterClass.getMethod("e").invoke(adapter)
                                        }
                                    } else if(node is ViewGroup)for(i in 0 until node.childCount)visit(node.getChildAt(i))
                                }
                                roots?.filterIsInstance<View>()?.toList()?.forEach(::visit)
                            }.onFailure { YLog.warn("Refreshing native rear icons failed",it) }
                        }
                        var last: List<Any?>?=null
                        var preferenceSubscription:AutoCloseable?=null
                        var hostContext:android.content.Context?=null
                        var receiverContext:android.content.Context?=null
                        val refresh=object:Runnable {
                            override fun run() {
                                if(!active||quiescing)return
                                // Before the first unlock PackageManager cannot resolve ordinary
                                // launch activities. Rebuild once they become available.
                                val context=hostContext?:return
                                val unlocked=context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked==true
                                val next=listOf(hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED,false),hookPrefs.getInt(ConfigKeys.REAR_APP_HOME_STYLE,0),hookPrefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS),hookPrefs.getInt(ConfigKeys.REAR_APP_ORDER_MODE,0),hookPrefs.getString(ConfigKeys.REAR_APP_ORDER_DATA),unlocked)
                                if(next!=last) { last=next;refreshAdapters() }
                                if(preferenceSubscription==null)preferenceSubscription=hookPrefs.observe { key ->
                                    if(key==null||key in setOf(ConfigKeys.REAR_APP_ENABLED,ConfigKeys.REAR_APP_HOME_STYLE,ConfigKeys.ACTIVITIES_WHITELIST_APPS,ConfigKeys.REAR_APP_ORDER_MODE,ConfigKeys.REAR_APP_ORDER_DATA)) {
                                        main.removeCallbacks(this);main.postDelayed(this,100)
                                    }
                                }
                                if(preferenceSubscription==null)main.postDelayed(this,3000)
                            }
                        }
                        val unlockReceiver=object:android.content.BroadcastReceiver() {
                            override fun onReceive(context:android.content.Context,intent:Intent) { main.removeCallbacks(refresh);main.post(refresh) }
                        }
                        scope.lifecycle {
                            onAttach(replay=true) {
                                if(active&&hostContext==null) {
                                    val context=this.context
                                    hostContext=context.applicationContext?:context
                                    hostContext!!.registerReceiver(unlockReceiver,android.content.IntentFilter(Intent.ACTION_USER_UNLOCKED),android.content.Context.RECEIVER_NOT_EXPORTED)
                                    receiverContext=hostContext
                                    main.post(refresh)
                                }
                            }
                        }
                        runtime.onClose {
                            main.removeCallbacks(refresh);preferenceSubscription?.close()
                            receiverContext?.let { context->
                                try { context.unregisterReceiver(unlockReceiver) }
                                catch(_:IllegalArgumentException) { /* Already released with the host lifecycle. */ }
                            }
                            receiverContext=null;hostContext=null
                        }
                    }
                }
                YLog.info("Selected rear apps added to stock picker UI callbacks")
            }.onFailure { YLog.warn("Stock rear app shortcuts unavailable", it) }
        }
    }

    private fun firstImage(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            firstImage(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
