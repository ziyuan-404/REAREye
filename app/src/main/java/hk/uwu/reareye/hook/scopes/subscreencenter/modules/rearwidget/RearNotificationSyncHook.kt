package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.KeyguardManager
import android.app.Notification
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Display
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging.RearChargingHook
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Uses SubscreenCenter's already-authorized listener, not a second notification listener. */
@OptIn(DexKitExperimentalApi::class)
class RearNotificationSyncHook : RoxyHooker() {
    private val main = Handler(Looper.getMainLooper())
    private var listener: NotificationListenerService? = null
    private var enabled: () -> Boolean = { false }
    private var apps: () -> Set<String> = { emptySet() }
    private var debugEnabled: () -> Boolean = { false }
    private val active = LinkedHashMap<String, StatusBarNotification>()
    private val tokens = LinkedHashMap<String, String>()
    private val shown = LinkedHashSet<String>()
    private data class RenderVersion(val postTime: Long, val redacted: Boolean)
    private val rendered = LinkedHashMap<String, RenderVersion>()
    private var receiverContext: Context? = null
    private var screenLocked = false
    private var visibleSourceKeys: Set<String>? = null
    private var panelOwnsGesture = false
    private var continueOnFront: (StatusBarNotification) -> Boolean = { false }
    private val frontContinuations = linkedSetOf<String>()
    private var flipIcon: Bitmap? = null
    private val rankingRefresh = Runnable { reconcile() }
    private val privacyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                screenLocked = true
            }
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                screenLocked = false
                frontContinuations.forEach { rendered.remove(it) }
                frontContinuations.clear()
            }
            main.post { reconcile() }
        }
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            enabled = { hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, false) }
            apps = { hookPrefs.getStringSet(ConfigKeys.REAR_NOTIFICATION_SYNC_APPS, emptySet()) }
            debugEnabled = { hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false) }
            continueOnFront = { sbn ->
                // Adapt a known detail flow, rather than treating every message
                // as a front-screen handoff. Other applications are opt-in.
                (sbn.packageName == "com.android.chrome" && sbn.notification.channelId == "downloads") ||
                    sbn.packageName in hookPrefs.getStringSet(ConfigKeys.REAR_NOTIFICATION_FRONT_APPS, emptySet())
            }
            runCatching { installNativePanelBehavior() }.onFailure {
                YLog.warn("Rear native panel behavior unavailable: ${it.javaClass.simpleName}")
            }
            YLog.info("[REAREye-NotificationSync] installed enabled=${enabled()} selectedApps=${apps().size}")
            RearNotificationCards.onReady = { main.post { reconcile() } }
            RearNotificationCards.setVisibleSourceKeys = { keys -> main.post {
                if (visibleSourceKeys != keys) {
                    visibleSourceKeys = keys
                    reconcile()
                }
            } }
            val service = "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService".toClass().resolve()
            service.firstMethod { name = "onListenerConnected"; parameterCount = 0 }.hook {
                after { (instance as? NotificationListenerService)?.let(::adopt) }
            }
            service.firstMethod { name = "onNotificationPosted"; parameterCount = 1 }.hook {
                before {
                    if (!enabled()) return@before
                    val host = instance as? NotificationListenerService ?: return@before
                    val sbn = args.firstOrNull() as? StatusBarNotification ?: return@before
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        attach(host)
                        active[sbn.key] = sbn
                        if (publish(sbn, force = true)) RearChargingHook.notificationAvailable?.invoke()
                    } else main.post {
                        attach(host)
                        active[sbn.key] = sbn
                        if (publish(sbn, force = true)) RearChargingHook.notificationAvailable?.invoke()
                    }
                }
                after {
                    val sbn = args.firstOrNull() as? StatusBarNotification ?: return@after
                    if (enabled() && (sbn.notification.extras.containsKey("miui.focus.param") ||
                                sbn.notification.extras.containsKey("miui.rear.param"))) {
                        // Let the stock handler finish its queued insertion. If it supplied
                        // a real persistent native card, remove our redundant fallback.
                        main.postDelayed({ active[sbn.key]?.let(::publish) }, 150L)
                    }
                }
            }
            service.firstMethod { name = "onNotificationRemoved"; parameterCount = 1 }.hook {
                after {
                    val sbn = args.firstOrNull() as? StatusBarNotification ?: return@after
                    main.post {
                        // A late removal must not remove a newer replacement with the same key.
                        if ((active[sbn.key]?.postTime ?: Long.MIN_VALUE) <= sbn.postTime) remove(sbn.key)
                    }
                }
            }
            service.firstMethod { name = "onListenerDisconnected"; parameterCount = 0 }.hook {
                after { main.post { clear(); listener = null } }
            }
            NotificationListenerService::class.java.resolve().firstMethod {
                name = "onNotificationRankingUpdate"; parameterCount = 1
            }.hook {
                after {
                    if (instance === listener && enabled()) {
                        main.removeCallbacks(rankingRefresh)
                        main.postDelayed(rankingRefresh, 150L)
                    }
                }
            }
            View::class.java.resolve().firstMethod { name = "onAttachedToWindow"; parameterCount = 0 }.hook {
                after {
                    val view = instance as? View ?: return@after
                    if (view.id == R.id.rear_notification_card) bindCard(view)
                }
            }
        }
    }

    private fun adopt(host: NotificationListenerService) { main.post {
        YLog.info("[REAREye-NotificationSync] listener connected enabled=${enabled()}")
        attach(host); reconcile()
    } }

    private fun attach(host: NotificationListenerService) {
        listener = host
        if (receiverContext != null) return
        val ctx = host.applicationContext
        ctx.registerReceiver(privacyReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction("android.intent.action.USER_SWITCHED")
        }, Context.RECEIVER_NOT_EXPORTED)
        receiverContext = ctx
    }

    private fun reconcile() {
        if (!enabled()) {
            clear()
            RearNotificationCards.reconcile?.invoke(emptySet())
            return
        }
        val host = listener ?: return
        val snapshot = runCatching { host.activeNotifications?.toList() }.getOrNull() ?: return
        val keys = snapshot.mapTo(HashSet()) { it.key }
        // Also discard restored mirror records that are outside the current
        // whitelist, even before this process has populated its `shown` set.
        RearNotificationCards.reconcile?.invoke(snapshot.filter(::eligible).mapTo(HashSet()) { it.key })
        (active.keys.toList() + shown.toList()).filterNot { it in keys }.distinct().forEach(::remove)
        snapshot.forEach { active[it.key] = it; publish(it) }
        if (debugEnabled()) YLog.debug("[REAREye-NotificationSync] reconciled sourceCount=${snapshot.size} mirrorCount=${shown.size} selectedApps=${apps().size} sourceUsers=${snapshot.groupingBy { it.uid / 100_000 }.eachCount()}")
    }

    private fun eligible(sbn: StatusBarNotification): Boolean {
        if (!enabled()) return false
        val selected = apps()
        if (sbn.packageName !in selected) return false
        // Only mirror keys admitted by the current user's SystemUI, from the
        // native listener's authorized snapshot. Visible cloned/work-profile
        // messages can belong to another UserHandle and must not be dropped.
        if (visibleSourceKeys?.contains(sbn.key) != true) return false
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        // Keep the native call, alarm, countdown, and media controls.
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM) return false
        val params = runCatching {
            val raw = n.extras.getString("miui.rear.param") ?: n.extras.getString("miui.focus.param")
            raw?.let(::JSONObject)?.let { it.optJSONObject("rear_param_v1") ?: it.optJSONObject("param_v2") ?: it }
        }.getOrNull()
        val business = params?.optString("business").orEmpty().ifBlank {
            params?.optJSONObject("baseInfo")?.optString("business").orEmpty()
        }
        return business !in setOf("incall", "alarm", "countdown", "music")
    }

    private fun publish(sbn: StatusBarNotification, force: Boolean = false): Boolean {
        if (!eligible(sbn)) { hide(sbn.key); return false }
        if (RearNotificationCards.hasNativeCard?.invoke(sbn.key) == true) {
            hide(sbn.key)
            return false
        }
        val host = listener ?: return false
        val locked = screenLocked || host.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val n = sbn.notification
        val ranking = NotificationListenerService.Ranking()
        val hasRanking = host.currentRanking?.getRanking(sbn.key, ranking) == true
        val override = if (hasRanking) runCatching {
            ranking.javaClass.getMethod("getLockscreenVisibilityOverride").invoke(ranking) as Int
        }.getOrDefault(-1000) else -1000
        val visibility = if (override != -1000) override else n.visibility
        if ((hasRanking && ranking.isSuspended) || locked && (visibility == Notification.VISIBILITY_SECRET ||
                    Settings.Secure.getInt(host.contentResolver, "lock_screen_show_notifications", 1) == 0)) {
            hide(sbn.key)
            return false
        }
        val token = tokens.getOrPut(sbn.key) { UUID.randomUUID().toString() }
        val allowPrivate = Settings.Secure.getInt(host.contentResolver,
            "lock_screen_allow_private_notifications", 0) != 0
        val redacted = locked && visibility != Notification.VISIBILITY_PUBLIC &&
            (override == Notification.VISIBILITY_PRIVATE || !allowPrivate)
        val version = RenderVersion(sbn.postTime, redacted)
        if (!force && rendered[sbn.key] == version && sbn.key in shown &&
            RearNotificationCards.hasMirrorCard?.invoke(sbn.key) == true) return true
        return runCatching {
            val views = buildViews(host, sbn, token, redacted)
            val accepted = RearNotificationCards.post?.invoke(sbn.key, views) == true
            if (debugEnabled())
                YLog.debug("[REAREye-NotificationSync] publish accepted=$accepted redacted=$redacted")
            if (accepted) { shown.add(sbn.key); rendered[sbn.key] = version }
            accepted
        }.onFailure { YLog.warn("Rear notification unavailable: ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    private fun buildViews(context: Context, sbn: StatusBarNotification, token: String, redacted: Boolean): RemoteViews {
        val n = sbn.notification
        val extras = n.extras
        require(context.resources.getIdentifier("notification_panel", "layout", context.packageName) != 0) {
            "Native rear notification layout unavailable"
        }
        return RemoteViews("hk.uwu.reareye", R.layout.rear_notification_card).apply {
            setTextViewText(R.id.rear_notification_token, token)
            setTextViewText(R.id.rear_notification_redacted, if (redacted) "1" else "0")
            if (!redacted) {
                n.contentIntent?.takeUnless { it.isActivity && continueOnFront(sbn) }
                    ?.let { setOnClickPendingIntent(R.id.rear_notification_card, it) }
                val maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                val hasProgress = maximum > 0 || indeterminate
                val hasChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0L
                val actions = n.actions.orEmpty().filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }.take(2)
                if (hasProgress || hasChronometer || actions.isNotEmpty()) {
                    setViewVisibility(R.id.rear_notification_footer, View.VISIBLE)
                    extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.takeIf { it.isNotBlank() }?.let {
                        setViewVisibility(R.id.rear_notification_subtext, View.VISIBLE)
                        setTextViewText(R.id.rear_notification_subtext, it)
                    }
                }
                if (hasProgress) {
                    setViewVisibility(R.id.rear_notification_progress, View.VISIBLE)
                    setProgressBar(R.id.rear_notification_progress, maximum.coerceAtLeast(1), extras.getInt(Notification.EXTRA_PROGRESS, 0), indeterminate)
                }
                if (hasChronometer) {
                    setViewVisibility(R.id.rear_notification_chronometer, View.VISIBLE)
                    setChronometer(R.id.rear_notification_chronometer, SystemClock.elapsedRealtime() + n.`when` - System.currentTimeMillis(), null, true)
                    setChronometerCountDown(R.id.rear_notification_chronometer, extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false))
                }
                if (actions.isNotEmpty()) setViewVisibility(R.id.rear_notification_actions, View.VISIBLE)
                val ids = listOf(R.id.rear_notification_action_one, R.id.rear_notification_action_two)
                ids.forEachIndexed { index, id ->
                    val action = actions.getOrNull(index)
                    setViewVisibility(id, if (action == null) View.GONE else View.VISIBLE)
                    if (action != null) { setTextViewText(id, action.title); setOnClickPendingIntent(id, action.actionIntent) }
                }
            }
        }
    }

    private fun populateNativeContent(view: View, sbn: StatusBarNotification, redacted: Boolean) {
        val host = listener ?: return
        val context = host.createDisplayContext(view.display)
        val nativeHost = view.findViewById<ViewGroup>(R.id.rear_notification_native_host) ?: return
        fun hostId(name: String, type: String = "id"): Int =
            context.resources.getIdentifier(name, type, context.packageName).also {
                require(it != 0) { "Native rear notification resource unavailable: $name" }
            }
        // notification_panel contains an ordinary View camera placeholder, which Android
        // forbids in RemoteViews. Inflate it normally inside the authorized host process;
        // never loosen RemoteViews' framework-wide class filter.
        val nativeRoot = LayoutInflater.from(context).inflate(hostId("notification_panel", "layout"), nativeHost, false)
        val n = sbn.notification
        val extras = n.extras
        val app = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: focusText(n, listOf("title", "titleText", "primaryText")).orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: focusText(n, listOf("text", "content", "subText", "description")).orEmpty()
        nativeRoot.findViewById<TextView>(hostId("notification_title")).text =
            if (redacted) app else title.ifBlank { app }.take(160)
        nativeRoot.findViewById<TextView>(hostId("notification_content")).text =
            if (redacted) "有一条新消息" else body.take(1200)
        val appIcon = runCatching {
            val icon = context.packageManager.getApplicationIcon(sbn.packageName)
            if (sbn.user == Process.myUserHandle()) icon else context.packageManager.getUserBadgedIcon(icon, sbn.user)
        }.getOrNull()
            ?: n.smallIcon?.loadDrawable(context)
        val avatar = if (redacted) null else n.getLargeIcon()?.loadDrawable(context)
        nativeRoot.findViewById<ImageView>(hostId("notification_icon")).apply {
            setImageDrawable(avatar ?: appIcon)
            if (avatar != null) {
                val radius = context.resources.getDimension(hostId("notification_large_icon_radius", "dimen"))
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                clipToOutline = true
            }
        }
        nativeRoot.findViewById<ImageView>(hostId("notification_small_icon")).apply {
            visibility = if (avatar == null) View.GONE else View.VISIBLE
            setImageDrawable(if (avatar == null) null else appIcon)
        }
        // Follow NotificationPanel's own constructor: reserve the camera strip in
        // its XML placeholder once, then let the native pager transform the card.
        val cameraId = hostId("camera_place_holder")
        val factoryPanel = view.rootView.findViewById<ViewGroup>(hostId("notification_panel"))
        val cameraWidth = factoryPanel?.findViewById<View>(cameraId)?.layoutParams?.width
            ?.takeIf { it >= 0 } ?: (view.display?.cutout?.safeInsetLeft ?: 0)
        nativeRoot.findViewById<View>(cameraId).let {
            it.layoutParams = it.layoutParams.apply { width = cameraWidth }
        }
        val margin = context.resources.getDimensionPixelSize(hostId("notification_container_margin_horizontal", "dimen"))
        view.findViewById<View>(R.id.rear_notification_footer)?.let {
            it.setPadding(cameraWidth + margin, it.paddingTop, margin, it.paddingBottom)
            if (it.visibility == View.VISIBLE) {
                // Task notifications need space for live progress and actions.
                // The footer is measured separately; compact only the content
                // above it, leaving ordinary QQ/WeChat cards at stock sizes.
                fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
                val icon = nativeRoot.findViewById<ImageView>(hostId("notification_icon"))
                icon.apply {
                    layoutParams = layoutParams.apply { width = dp(40); height = dp(40) }
                }
                nativeRoot.findViewById<ImageView>(hostId("notification_small_icon")).apply {
                    layoutParams = (layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                        width = dp(14); height = dp(14); topMargin = dp(28); marginStart = dp(16)
                    }
                }
                val titleView = nativeRoot.findViewById<TextView>(hostId("notification_title")).apply {
                    maxLines = 2
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(16).toFloat())
                    setLineHeight(dp(20))
                    setLineSpacing(0f, 1f)
                    gravity = Gravity.START
                }
                val bodyView = nativeRoot.findViewById<TextView>(hostId("notification_content")).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(13).toFloat())
                    setLineHeight(dp(18))
                    gravity = Gravity.START
                    val hasProgress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                        extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                    maxLines = if (hasProgress) 1 else 2
                    if (hasProgress) {
                        val parts = Regex("^已下载\\s*(.+?)[,，]\\s*共\\s*(.+)$").matchEntire(body)
                        if (parts != null) text = "${parts.groupValues[1]} / ${parts.groupValues[2]}"
                        contentDescription = body
                    }
                }
                val container = nativeRoot.findViewById<LinearLayout>(hostId("notification_container"))
                val imageFrame = icon.parent as View
                container.removeAllViews()
                val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                header.addView(imageFrame, LinearLayout.LayoutParams(dp(42), dp(42)))
                val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(TextView(context).apply {
                    text = app; setTextColor(0xCCFFFFFF.toInt()); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(12).toFloat())
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                labels.addView(titleView, LinearLayout.LayoutParams(-1, -2))
                header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                container.addView(header, LinearLayout.LayoutParams(-1, -2))
                container.addView(bodyView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
        }
        nativeRoot.setOnClickListener { view.performClick() }
        nativeRoot.findViewById<View>(hostId("notification_container")).setOnClickListener { view.performClick() }
        nativeHost.removeAllViews()
        nativeHost.addView(nativeRoot)
        // SmartAssistantPanel owns the stock background and wake/AOD transitions.
        // Keep mirror content transparent; do not add nested blur or blend layers.
    }

    private fun PackageScope.installNativePanelBehavior() {
        val launcherClass = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass()
        launcherClass.resolve().firstMethod { name = "dispatchTouchEvent"; parameterCount = 1 }.hook {
            before {
                val event = args.firstOrNull() as? MotionEvent ?: return@before
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val activity = instance as? android.app.Activity ?: return@before
                    val containerId = activity.resources.getIdentifier("assistant_container", "id", activity.packageName)
                    val container = activity.findViewById<View>(containerId)
                    panelOwnsGesture = enabled() && container?.isShown == true && container.getGlobalVisibleRect(Rect())
                }
            }
        }
        val bridge = runtime.manage(createDexKitCacheBridge(
            packageName = hookAppInfo.packageName,
            packageVersionCode = resolveHookPackageVersionCode(hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir),
            sourceDir = hookAppInfo.sourceDir,
            dataDir = hookAppInfo.dataDir,
        ))
        runCatching {
            val update = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_MIRROR_AOD_NATIVE_UPDATE") {
                findMethod { matcher { paramTypes("boolean"); returnType = "void";
                    usingStrings("updateViews: mInAod = ") } }.singleOrNull()
            })
            val widgetClass = update.className.toClass()
            val replace = widgetClass.getDeclaredMethod(update.methodName, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val aodChanged = widgetClass.declaredMethods.single {
                it.name != update.methodName && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }
            val payload = widgetClass.fields.single { it.type == android.os.Bundle::class.java }
            widgetClass.resolve().firstMethod { name = aodChanged.name; parameterCount = 1 }.hook {
                before {
                    val widget = instance ?: return@before
                    val bundle = payload.get(widget) as? android.os.Bundle ?: return@before
                    if (bundle.getString("business") != RearNotificationCards.BUSINESS) return@before
                    // Use the native initial/update path without stacking an
                    // identical second text layer during the system power fade.
                    replace.invoke(widget, false)
                    result = null
                }
            }
            YLog.info("[REAREye-NotificationSync] native AOD single-view update installed")
        }.onFailure { YLog.warn("Native rear AOD update unavailable: ${it.javaClass.simpleName}") }
        runCatching {
            val point = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_CAMERA_GESTURE_PRECHECK") {
                findMethod { matcher { paramTypes("android.view.MotionEvent"); returnType = "boolean";
                    usingStrings("preCheck: app card opened, skip camera gesture") } }.singleOrNull()
            })
            point.className.toClass().resolve().firstMethod { name = point.methodName; parameterCount = 1 }.hook {
                before { if (enabled() && panelOwnsGesture) result = false }
            }
            YLog.info("[REAREye-NotificationSync] panel camera gesture guard installed")
        }.onFailure { YLog.warn("Rear camera gesture guard unavailable: ${it.javaClass.simpleName}") }
    }

    private fun showFrontContinuation(view: View) {
        val host = view.findViewById<ViewGroup>(R.id.rear_notification_native_host) ?: return
        val context = view.context
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        val prompt = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((view.display?.cutout?.safeInsetLeft ?: 0) + dp(30), 0, dp(30), 0)
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            loadNativeFlipIcon()?.let(::setImageBitmap)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        prompt.addView(image, LinearLayout.LayoutParams(dp(32), dp(32)).apply { bottomMargin = dp(24) })
        prompt.addView(TextView(context).apply {
            text = "请翻转手机，到主屏继续使用"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(16).toFloat())
            setTextColor(0xF2FFFFFF.toInt())
            gravity = Gravity.CENTER
            maxLines = 2
            setLineHeight(dp(22))
        }, LinearLayout.LayoutParams(-1, -2))
        prompt.setOnClickListener { view.performClick() }
        host.removeAllViews()
        host.addView(prompt, ViewGroup.LayoutParams(-1, -1))
        view.findViewById<View>(R.id.rear_notification_footer)?.visibility = View.GONE
    }

    private fun loadNativeFlipIcon(): Bitmap? {
        flipIcon?.let { return it }
        return runCatching {
            val root = "/data/system/theme_magic/users/${Process.myUid() / 100_000}"
            val config = File("$root/subscreencenter/config/appInfo.json")
            require(config.length() in 1..2_000_000L)
            val entries = JSONArray(config.readText())
            val archive = (0 until entries.length()).asSequence().map { entries.getJSONObject(it) }
                .first { it.optString("bindApp") == "com.xiaomi.smarthome" }.getString("mtzPath")
            val path = File(archive).canonicalPath
            require(path.startsWith("$root/back_screen_card/") || path.startsWith("/system/media/rearscreen/"))
            ZipFile(path).use { zip ->
                val entry = requireNotNull(zip.getEntry("assets/flip_icon.png"))
                require(entry.size in 1..256_000L)
                val bytes = zip.getInputStream(entry).use { it.readNBytes(256_001) }
                require(bytes.size <= 256_000)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { flipIcon = it }
            }
        }.onFailure { YLog.warn("Native flip icon unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun focusText(notification: Notification, names: List<String>): String? = runCatching {
        val raw = notification.extras.getString("miui.rear.param")
            ?: notification.extras.getString("miui.focus.param") ?: return@runCatching null
        if (raw.length > 128_000) return@runCatching null
        var nodes = listOf(JSONObject(raw))
        repeat(5) {
            nodes.forEach { node ->
                names.forEach { name ->
                    val value = node.opt(name)
                    if (value is String && value.isNotBlank()) return@runCatching value.take(1200)
                }
            }
            nodes = nodes.flatMap { node ->
                node.keys().asSequence().mapNotNull { node.optJSONObject(it) }.take(24).toList()
            }.take(64)
        }
        null
    }.getOrNull()

    private fun bindCard(view: View) {
        // Camera spacing belongs to the stock notification layout. Changing this
        // outer padding during page translation/scaling causes text/icon jumps.
        view.setPadding(0, 0, 0, 0)
        val token = view.findViewById<TextView>(R.id.rear_notification_token)?.text?.toString() ?: return
        val key = tokens.entries.firstOrNull { it.value == token }?.key ?: return
        val sbn = active[key] ?: return
        val redacted = view.findViewById<TextView>(R.id.rear_notification_redacted)?.text?.toString() == "1"
        runCatching { populateNativeContent(view, sbn, redacted) }.onFailure {
            YLog.warn("Native rear notification layout unavailable: ${it.javaClass.simpleName}")
            main.post { hide(key) }
            return
        }
        if (!redacted && sbn.notification.contentIntent?.isActivity == true && continueOnFront(sbn)) {
            view.isClickable = true
            view.setOnClickListener {
                if (key in frontContinuations) {
                    frontContinuations.remove(key)
                    active[key]?.let { publish(it, force = true) }
                } else runCatching {
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
                    sbn.notification.contentIntent.send(view.context, 0, null, null, null, null, options)
                    frontContinuations.add(key)
                    showFrontContinuation(view)
                }.onFailure { YLog.warn("Front notification open unavailable: ${it.javaClass.simpleName}") }
            }
            if (key in frontContinuations) showFrontContinuation(view)
        }
        if (!sbn.isClearable) return
        view.isClickable = true
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: android.os.Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_DISMISS) { dismissSource(key); return true }
                return super.performAccessibilityAction(host, action, arguments)
            }
        }
        var startX = 0f
        var startY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        val touch = View.OnTouchListener { v, event ->
            val dx = event.rawX - startX
            val dy = event.rawY - startY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; dragging = false; false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        v.translationX = dx
                        v.alpha = (1f - abs(dx) / v.width.coerceAtLeast(1)).coerceIn(0.5f, 1f)
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val consumed = dragging
                    if (dragging && event.actionMasked == MotionEvent.ACTION_UP && abs(dx) > v.width * 0.32f) dismissSource(key)
                    dragging = false
                    v.animate().translationX(0f).alpha(1f).setDuration(150L).start()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    consumed
                }
                else -> false
            }
        }
        view.setOnTouchListener(touch)
        val nativeRoot = view.findViewById<ViewGroup>(R.id.rear_notification_native_host)?.getChildAt(0)
        nativeRoot?.setOnTouchListener(touch)
        val containerId = nativeRoot?.resources?.getIdentifier("notification_container", "id", "com.xiaomi.subscreencenter") ?: 0
        nativeRoot?.findViewById<View>(containerId)?.setOnTouchListener(touch)
    }

    private fun dismissSource(key: String) {
        val host = listener ?: return
        if (!enabled()) return
        // Resolve against NMS again: never cancel a non-clearable or already-replaced entry.
        val current = runCatching { host.getActiveNotifications(arrayOf(key))?.singleOrNull() }.getOrNull() ?: return
        if (!current.isClearable || !eligible(current)) return
        runCatching { host.cancelNotification(key) }.onFailure {
            YLog.warn("Rear notification dismissal rejected: ${it.javaClass.simpleName}")
        }
        // NMS removal is authoritative. If cancellation was refused, retain the rear card.
        main.postDelayed({ reconcile() }, 250L)
    }

    private fun hide(key: String) {
        frontContinuations.remove(key)
        rendered.remove(key)
        if (shown.remove(key)) RearNotificationCards.remove?.invoke(key)
    }

    private fun remove(key: String) { hide(key); active.remove(key); tokens.remove(key) }
    private fun clear() { shown.toList().forEach(::hide); active.clear(); tokens.clear() }

    override fun onHotReloadQuiesce() {
        val done = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                main.removeCallbacksAndMessages(null)
                clear()
                flipIcon = null
                receiverContext?.unregisterReceiver(privacyReceiver)
                receiverContext = null
                listener = null
                RearNotificationCards.onReady = null
                RearNotificationCards.setVisibleSourceKeys = null
                visibleSourceKeys = null
                continueOnFront = { false }
                panelOwnsGesture = false
            } finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else { main.post(cleanup); check(done.await(3, TimeUnit.SECONDS)) { "Notification cleanup timed out" } }
    }
}
