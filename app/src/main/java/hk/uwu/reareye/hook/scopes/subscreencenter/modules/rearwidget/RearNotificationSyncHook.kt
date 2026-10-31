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
import android.view.VelocityTracker
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Display
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.ImageView
import android.widget.FrameLayout
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
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.NativeRearAppLaunch
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Uses SubscreenCenter's already-authorized listener, not a second notification listener. */
@OptIn(DexKitExperimentalApi::class)
class RearNotificationSyncHook : RoxyHooker() {
    private var launchPrefs: hk.uwu.reareye.hook.support.HookPrefs?=null
    private var preferencesSubscription:AutoCloseable?=null
    private val main = Handler(Looper.getMainLooper())
    private var listener: NotificationListenerService? = null
    private var enabled: () -> Boolean = { false }
    private var apps: () -> Set<String> = { emptySet() }
    private var debugEnabled: () -> Boolean = { false }
    private val active = LinkedHashMap<String, StatusBarNotification>()
    private val tokens = LinkedHashMap<String, String>()
    private val shown = LinkedHashSet<String>()
    private data class RenderVersion(val postTime: Long, val redacted: Boolean, val group: String)
    private val rendered = LinkedHashMap<String, RenderVersion>()
    private var receiverContext: Context? = null
    private var screenLocked = false
    private var visibleSourceKeys: Set<String>? = null
    private var lastSyncSummary = ""
    private var sourceGroups: Map<String, String> = emptyMap()
    private var groupedSources: Map<String, List<StatusBarNotification>> = emptyMap()
    private val expandedGroups = linkedSetOf<String>()
    private val dismissing = linkedSetOf<String>()
    private var rearInAod = false
    private var aodChangedAt = 0L
    private val groupReconciles = mutableMapOf<String, Runnable>()
    private var panelOwnsGesture = false
    private var continueOnFront: (StatusBarNotification) -> Boolean = { false }
    private val frontContinuations = linkedSetOf<String>()
    private val rankingRefresh = Runnable { reconcile() }
    private val privacyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                RearNotificationGestures.cancelTap()
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
            launchPrefs=hookPrefs
            enabled = { hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, false) }
            apps = { hookPrefs.getStringSet(ConfigKeys.REAR_NOTIFICATION_SYNC_APPS, emptySet()) }
            debugEnabled = { hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false) }
            continueOnFront = { sbn ->
                !NativeRearAppLaunch.canOpenNotification(sbn,launchPrefs?:hookPrefs,hookSystemContext)
            }
            preferencesSubscription=launchPrefs?.observe { key ->
                if(key==null||key==ConfigKeys.ACTIVITIES_WHITELIST_APPS||key==ConfigKeys.REAR_APP_ENABLED)
                    main.post { if(listener!=null) { rendered.clear();reconcile() } }
            }
            runCatching { installNativePanelBehavior() }.onFailure {
                YLog.warn("Rear native panel behavior unavailable: ${it.javaClass.simpleName}")
            }
            YLog.info("[REAREye-NotificationSync] installed enabled=${enabled()} selectedApps=${apps().size}")
            RearNotificationCards.onReady = { main.post { reconcile() } }
            RearNotificationCards.setVisibleSourceKeys = { keys, groups -> main.post {
                if (visibleSourceKeys != keys || sourceGroups != groups) {
                    visibleSourceKeys = keys
                    sourceGroups = groups
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
                        reconcile()
                        if (sbn.key in shown) RearChargingHook.notificationAvailable?.invoke()
                    } else main.post {
                        attach(host)
                        reconcile()
                        if (sbn.key in shown) RearChargingHook.notificationAvailable?.invoke()
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
                        if ((active[sbn.key]?.postTime ?: Long.MIN_VALUE) <= sbn.postTime) {
                            remove(sbn.key)
                            reconcile()
                        }
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
            RemoteViews::class.java.resolve().firstMethod { name = "reapply"; parameterCount = 2 }.hook {
                after {
                    val view = args.getOrNull(1) as? View ?: return@after
                    if (view.id == R.id.rear_notification_card) bindCard(view)
                }
            }
            View::class.java.resolve().firstMethod { name = "onAttachedToWindow"; parameterCount = 0 }.hook {
                after {
                    val view = instance as? View ?: return@after
                    if (view.id == R.id.rear_notification_card) bindCard(view)
                }
            }
            this.lifecycle {
                onAttach(replay = true) {
                    // Hot reload does not reconnect an already-bound NotificationListener.
                    // Adopt the live service instead of waiting for the next notification.
                    main.post {
                        runCatching {
                            val threadType = Class.forName("android.app.ActivityThread")
                            val thread = threadType.getDeclaredMethod("currentActivityThread")
                                .apply { isAccessible = true }.invoke(null)
                            val services = threadType.getDeclaredField("mServices")
                                .apply { isAccessible = true }.get(thread) as? Map<*, *>
                            services?.values?.filterIsInstance<NotificationListenerService>()
                                ?.firstOrNull { it.javaClass.name ==
                                    "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService" }
                                ?.let(::adopt)
                        }.onFailure { YLog.warn("Live rear notification listener recovery unavailable", it) }
                    }
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
        NativeRearMotion.hostLoader = host.classLoader
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
        snapshot.forEach { active[it.key] = it }
        groupedSources = snapshot.filter(::sourceEligible).groupBy(::groupId)
        expandedGroups.retainAll(groupedSources.keys)
        // Also discard restored mirror records that are outside the current
        // whitelist, even before this process has populated its `shown` set.
        RearNotificationCards.reconcile?.invoke(snapshot.filter(::eligible).mapTo(HashSet()) { it.key })
        (active.keys.toList() + shown.toList()).filterNot { it in keys }.distinct().forEach(::remove)
        snapshot.forEach { publish(it) }
        val candidates = snapshot.filter(::eligible)
        val summary = "source=${snapshot.size} visible=${visibleSourceKeys?.size} eligible=${candidates.size}" +
            " native=${candidates.count { RearNotificationCards.hasNativeCard?.invoke(it.key) == true }}" +
            " privateBlocked=${candidates.count { privacy(it) == null }} mirror=${shown.size}"
        if (lastSyncSummary != summary) {
            lastSyncSummary = summary
            YLog.info("[REAREye-NotificationSync] $summary")
        }
        if (debugEnabled()) YLog.debug("[REAREye-NotificationSync] reconciled sourceCount=${snapshot.size} mirrorCount=${shown.size} selectedApps=${apps().size} sourceUsers=${snapshot.groupingBy { it.uid / 100_000 }.eachCount()}")
    }

    private fun eligible(sbn: StatusBarNotification): Boolean {
        if (!sourceEligible(sbn)) return false
        val group = groupId(sbn)
        val members = groupedSources[group].orEmpty()
        return group in expandedGroups || members.maxWithOrNull(compareBy<StatusBarNotification> { it.postTime }.thenBy { it.key })?.key == sbn.key
    }

    private fun groupId(sbn: StatusBarNotification): String = sourceGroups[sbn.key]
        ?: if (sbn.isGroup) sbn.groupKey else sbn.key

    private fun sourceEligible(sbn: StatusBarNotification): Boolean {
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

    private fun privacy(sbn: StatusBarNotification): Boolean? {
        val host = listener ?: return null
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
            return null
        }
        val allowPrivate = Settings.Secure.getInt(host.contentResolver,
            "lock_screen_allow_private_notifications", 0) != 0
        return locked && visibility != Notification.VISIBILITY_PUBLIC &&
            (override == Notification.VISIBILITY_PRIVATE || !allowPrivate)
    }

    private fun publish(sbn: StatusBarNotification, force: Boolean = false): Boolean {
        if (sbn.key in dismissing) return false
        if (!eligible(sbn)) { hide(sbn.key); return false }
        if (RearNotificationCards.hasNativeCard?.invoke(sbn.key) == true) {
            hide(sbn.key)
            return false
        }
        val host = listener ?: return false
        val redacted = privacy(sbn) ?: run { hide(sbn.key); return false }
        val token = tokens.getOrPut(sbn.key) { UUID.randomUUID().toString() }
        // Progress/ranking refreshes must not restart a settled handoff cue.
        // Keep recording the latest source in `active`; returning to the card
        // publishes that fresh content. Privacy changes still rebuild the view.
        if (!force && sbn.key in frontContinuations && rendered[sbn.key]?.redacted == redacted &&
            sbn.key in shown && RearNotificationCards.hasMirrorCard?.invoke(sbn.key) == true) return true
        val group = groupId(sbn)
        val version = RenderVersion(sbn.postTime, redacted,
            "${group in expandedGroups}:" + groupedSources[group].orEmpty().joinToString { "${it.key}:${it.postTime}" })
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
            listOf(R.id.rear_notification_footer, R.id.rear_notification_progress,
                R.id.rear_notification_subtext, R.id.rear_notification_chronometer,
                R.id.rear_notification_actions, R.id.rear_notification_action_one,
                R.id.rear_notification_action_two).forEach { setViewVisibility(it, View.GONE) }
            if (!redacted) {
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

    private fun populateNativeContent(view: View, sbn: StatusBarNotification, redacted: Boolean, animateContent: Boolean = false) {
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
        val group = groupId(sbn)
        val members = groupedSources[group].orEmpty()
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
            NativeRearAppLaunch.registerNotificationOrigin(view,this)
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
        // Visibility includes our group toggle after an earlier bind. Derive the
        // layout from notification data so rebinding cannot change its geometry.
        val hasTaskControls = !redacted && (
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ||
                extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0L ||
                n.actions.orEmpty().any { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() })
        view.findViewById<View>(R.id.rear_notification_footer)?.let {
            it.setPadding(cameraWidth + margin, it.paddingTop, margin, it.paddingBottom)
            if (hasTaskControls) {
                // Keep the native notification layout; compact only task cards
                // that have actual progress or action controls.
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
        nativeHost.layoutParams = (nativeHost.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
        nativeHost.removeAllViews()
        nativeHost.addView(nativeRoot)
        val footer = view.findViewById<LinearLayout>(R.id.rear_notification_footer)
        footer.visibility = if (hasTaskControls || members.size > 1) View.VISIBLE else View.GONE
        footer.findViewWithTag<View>("rear_notification_group_toggle")?.let { footer.removeView(it) }
        if (members.size > 1) {
            footer.visibility = View.VISIBLE
            val toggle = NativeGroupToggle(context, members.size, group in expandedGroups)
            toggle.setOnClickListener {
                if (NativeRearMotion.isFlipping(view)) return@setOnClickListener
                val expanded = if (expandedGroups.remove(group)) false else { expandedGroups.add(group); true }
                toggle.setExpanded(expanded)
                groupReconciles.remove(group)?.let(main::removeCallbacks)
                val job = Runnable {
                    groupReconciles.remove(group)
                    // Registry updates reapply content themselves. Rebuilding a
                    // retained root again here interrupts the label transition.
                    reconcile()
                    if (view.isAttachedToWindow)
                        view.findViewWithTag<NativeGroupToggle>("rear_notification_group_toggle")
                            ?.setExpanded(group in expandedGroups)
                }
                groupReconciles[group] = job
                main.postDelayed(job, if (android.animation.ValueAnimator.areAnimatorsEnabled()) 220L else 0L)
            }
            footer.addView(toggle, LinearLayout.LayoutParams(-1, (36 * context.resources.displayMetrics.density).toInt()))
        }

        // SmartAssistantPanel owns the stock background and wake/AOD transitions.
        // Keep mirror content transparent; do not add nested blur or blend layers.
    }

    private fun PackageScope.installNativePanelBehavior() {
        View::class.java.resolve().firstMethod { name = "setBackgroundColor"; parameterCount = 1 }.hook {
            before {
                if (!enabled() || !hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_GLASS, true)) return@before
                val view = instance as? View ?: return@before
                val colorId = view.resources.getIdentifier("assistant_container_background_color", "color", "com.xiaomi.subscreencenter")
                if (colorId == 0 || args[0] != view.context.getColor(colorId)) return@before
                var node: View? = view
                var inPanel = false
                repeat(8) {
                    if (node?.javaClass?.name == "com.xiaomi.subscreencenter.SmartAssistantPanel") inPanel = true
                    node = node?.parent as? View
                }
                if (inPanel) args[0] = 0x44505C70
            }
        }
        val launcherClass = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass()
        launcherClass.resolve().firstMethod { name = "dispatchTouchEvent"; parameterCount = 1 }.hook {
            before {
                val event = args.firstOrNull() as? MotionEvent ?: return@before
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val activity = instance as? android.app.Activity ?: return@before
                    NativeRearReply.activity = java.lang.ref.WeakReference(activity)
                    val containerId = activity.resources.getIdentifier("assistant_container", "id", activity.packageName)
                    val container = activity.findViewById<View>(containerId)
                    panelOwnsGesture = enabled() && container?.isShown == true && container.getGlobalVisibleRect(Rect())
                }
                val activity = instance as? android.app.Activity ?: return@before
                if (enabled() && RearNotificationGestures.dispatch(activity, event)) result = true
            }
        }
        val bridge = runtime.manage(createDexKitCacheBridge(
            packageName = hookAppInfo.packageName,
            packageVersionCode = resolveHookPackageVersionCode(hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir),
            sourceDir = hookAppInfo.sourceDir,
            dataDir = hookAppInfo.dataDir,
        ))
        runCatching {
            val point = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_NOTIFICATION_NATIVE_DOUBLE_TAP") {
                findMethod { matcher { paramTypes("android.view.MotionEvent"); returnType = "void";
                    usingStrings("onGesturePerformed: double click subscreen go to sleep") } }.singleOrNull()
            })
            val type = point.className.toClass()
            val method = type.getDeclaredMethod(point.methodName, MotionEvent::class.java).apply { isAccessible = true }
            type.resolve().firstMethod { name = point.methodName; parameterCount = 1 }.hook {
                before {
                    val event = args.firstOrNull() as? MotionEvent ?: return@before
                    if (enabled() && RearNotificationGestures.suppressNativeDoubleTap && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            try { method.invoke(instance, cancel) } finally { cancel.recycle() }
                        }
                        // Explicit footer controls behave as controls; rapid label
                        // reversals must not become the page's sleep gesture.
                        result = null
                    }
                }
            }
        }.onFailure { YLog.warn("Native notification control gesture unavailable: ${it.javaClass.simpleName}") }
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
            val createView = widgetClass.declaredMethods.single {
                it.parameterTypes.contentEquals(arrayOf(Context::class.java)) && it.returnType == View::class.java
            }
            widgetClass.resolve().firstMethod { name = createView.name; parameterCount = 1 }.hook {
                after {
                    val widget = instance ?: return@after
                    if ((payload.get(widget) as? android.os.Bundle)?.getString("business") != RearNotificationCards.BUSINESS) return@after
                    (result as? View)?.let { NativeRearCardHost.register(widget, it) }
                }
            }
            widgetClass.resolve().firstMethod { name = "c"; parameterCount = 0 }.hook {
                after {
                    val widget = instance ?: return@after
                    if (enabled() && (payload.get(widget) as? android.os.Bundle)?.getString("business") == RearNotificationCards.BUSINESS)
                        result = true
                }
            }
            val contentField = widgetClass.declaredFields.single { it.type == View::class.java }
                .apply { isAccessible = true }
            widgetClass.resolve().firstMethod { name = aodChanged.name; parameterCount = 1 }.hook {
                before {
                    if (!enabled()) return@before
                    val widget = instance ?: return@before
                    val bundle = payload.get(widget) as? android.os.Bundle ?: return@before
                    if (bundle.getString("business") != RearNotificationCards.BUSINESS) return@before
                    rearInAod = args[0] as Boolean
                    aodChangedAt = SystemClock.uptimeMillis()
                    // Retain the existing root, including a visible handoff face.
                    // The stock RemoteViews AOD path adds a duplicate text layer.
                    val root = contentField.get(widget) as? ViewGroup
                    if (root?.id == R.id.rear_notification_card) {
                        root.getChildAt(2)?.let { NativeRearMotion.aod(it, rearInAod, true) }
                    } else replace.invoke(widget, false)
                    result = null
                }
            }
            YLog.info("[REAREye-NotificationSync] native AOD content transition installed")
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
        val context = listener?.createDisplayContext(view.display) ?: return
        runCatching {
            host.layoutParams = (host.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
            val prompt = NativeRearContinuation.create(context, "请翻转手机\n到主屏继续使用")
            prompt.setOnClickListener { view.performClick() }
            NativeRearContinuation.show(host, prompt, view.findViewById(R.id.rear_notification_footer), view)
        }.onFailure { YLog.warn("Native front continuation unavailable: ${it.javaClass.simpleName}") }
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

    private fun bindCard(view: View, animateContent: Boolean = false) {
        // Camera spacing belongs to the stock notification layout. Changing this
        // outer padding during page translation/scaling causes text/icon jumps.
        view.setPadding(0, 0, 0, 0)
        val token = view.findViewById<TextView>(R.id.rear_notification_token)?.text?.toString() ?: return
        val key = tokens.entries.firstOrNull { it.value == token }?.key ?: return
        val sbn = active[key] ?: return
        val redacted = view.findViewById<TextView>(R.id.rear_notification_redacted)?.text?.toString() == "1"
        if (redacted) view.setOnClickListener(null)
        runCatching { populateNativeContent(view, sbn, redacted, animateContent) }.onFailure {
            YLog.warn("Native rear notification layout unavailable: ${it.javaClass.simpleName}")
            main.post { hide(key) }
            return
        }
        if (!redacted) {
            view.isClickable = true
            view.setOnClickListener {
                RearNotificationGestures.singleTap(view) {
                    if (active[key]?.postTime != sbn.postTime || privacy(active[key] ?: return@singleTap) != false) return@singleTap
                    if (NativeRearMotion.isFlipping(view)) return@singleTap
                    if(!continueOnFront(sbn)) {
                        runCatching { NativeRearAppLaunch.openNotification(view,sbn,launchPrefs?:return@singleTap) }
                            .onFailure { YLog.warn("Native rear notification launch failed",it) }
                        return@singleTap
                    }
                    if (key in frontContinuations || view.findViewWithTag<View>(NativeRearContinuation.VIEW_TAG) != null) {
                        NativeRearMotion.flip(view, returning = true) {
                            frontContinuations.remove(key)
                            val current = active[key]
                            val host = listener
                            val currentRedacted = rendered[key]?.redacted
                            if (current != null && host != null && currentRedacted != null) {
                                buildViews(host, current, token, currentRedacted).reapply(view.context, view)
                            }
                        }
                    } else runCatching {
                        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
                        sbn.notification.contentIntent?.send(view.context, 0, null, null, null, null, options)
                        frontContinuations.add(key)
                        showFrontContinuation(view)
                    }.onFailure { YLog.warn("Front notification open unavailable: ${it.javaClass.simpleName}") }
                }
            }
            if (key in frontContinuations) showFrontContinuation(view)
        }
        val content = (view as? ViewGroup)?.getChildAt(2)
        if (content != null) {
            val now = SystemClock.uptimeMillis()
            if (now - aodChangedAt < 1600L) {
                NativeRearMotion.aod(content, !rearInAod, false)
                NativeRearMotion.aod(content, rearInAod, true)
            } else NativeRearMotion.aod(content, rearInAod, false)
        }
        RearNotificationGestures.register(view, sbn.isClearable) { dismissSource(key, sbn.postTime) }
    }

    private fun dismissSource(key: String, expectedPostTime: Long): Boolean {
        val host = listener ?: return false
        if (!enabled()) return false
        // Resolve against NMS again: never cancel a non-clearable or already-replaced entry.
        val snapshot = runCatching { host.activeNotifications?.toList() }.getOrNull() ?: return false
        val current = snapshot.singleOrNull { it.key == key } ?: return false
        if (current.postTime != expectedPostTime || !current.isClearable || !eligible(current)) return false
        val group = groupId(current)
        val targets = if (group !in expandedGroups && groupedSources[group].orEmpty().size > 1)
            snapshot.filter { groupId(it) == group && it.isClearable &&
                (sourceEligible(it) || (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) }
        else listOf(current)
        val targetKeys = targets.map { it.key }
        dismissing.addAll(targetKeys)
        val accepted = runCatching { host.cancelNotifications(targetKeys.toTypedArray()); true }.onFailure {
            YLog.warn("Rear notification dismissal rejected: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        // NMS removal is authoritative. If cancellation was refused, retain the rear card.
        main.postDelayed({
            dismissing.removeAll(targetKeys.toSet())
            targetKeys.forEach { rendered.remove(it) }
            reconcile()
        }, 600L)
        return accepted
    }

    private fun hide(key: String) {
        frontContinuations.remove(key)
        rendered.remove(key)
        if (shown.remove(key)) RearNotificationCards.remove?.invoke(key)
    }

    private fun remove(key: String) { hide(key); active.remove(key); tokens.remove(key) }
    private fun clear() {
        shown.toList().forEach(::hide); active.clear(); tokens.clear()
        groupedSources = emptyMap(); expandedGroups.clear(); dismissing.clear()
        groupReconciles.values.forEach(main::removeCallbacks); groupReconciles.clear()
        RearNotificationGestures.cancelTap()
    }

    override fun onHotReloadQuiesce() {
        val done = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                preferencesSubscription?.close();preferencesSubscription=null
                main.removeCallbacksAndMessages(null)
                clear()
                receiverContext?.unregisterReceiver(privacyReceiver)
                receiverContext = null
                listener = null
                RearNotificationCards.onReady = null
                RearNotificationCards.setVisibleSourceKeys = null
                visibleSourceKeys = null
                sourceGroups = emptyMap()
                continueOnFront = { false }
                panelOwnsGesture = false
            } finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else { main.post(cleanup); check(done.await(3, TimeUnit.SECONDS)) { "Notification cleanup timed out" } }
    }
}
