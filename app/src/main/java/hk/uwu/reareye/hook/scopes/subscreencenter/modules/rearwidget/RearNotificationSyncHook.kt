package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RemoteViews
import android.widget.TextView
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging.RearChargingHook
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Uses SubscreenCenter's already-authorized listener, not a second notification listener. */
class RearNotificationSyncHook : RoxyHooker() {
    private val main = Handler(Looper.getMainLooper())
    private var listener: NotificationListenerService? = null
    private var enabled: () -> Boolean = { false }
    private var apps: () -> Set<String> = { emptySet() }
    private val active = LinkedHashMap<String, StatusBarNotification>()
    private val tokens = LinkedHashMap<String, String>()
    private val shown = LinkedHashSet<String>()
    private var receiverContext: Context? = null
    private var screenLocked = false
    private val rankingRefresh = Runnable { reconcile() }
    private val privacyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) screenLocked = true
            if (intent.action == Intent.ACTION_USER_PRESENT) screenLocked = false
            main.post { reconcile() }
        }
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            enabled = { hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, false) }
            apps = { hookPrefs.getStringSet(ConfigKeys.REAR_NOTIFICATION_SYNC_APPS, emptySet()) }
            RearNotificationCards.onReady = { main.post { reconcile() } }
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
                        // Replace only a successfully accepted mirror; preserve the native
                        // focus path if the card host is unavailable or the scene is protected.
                        if (publish(sbn)) {
                            RearChargingHook.interruptForNotification?.invoke()
                            if (sbn.notification.extras.containsKey("miui.focus.param") ||
                                sbn.notification.extras.containsKey("miui.rear.param")) result = null
                        }
                    } else main.post {
                        attach(host)
                        active[sbn.key] = sbn
                        publish(sbn)
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

    private fun adopt(host: NotificationListenerService) { main.post { attach(host); reconcile() } }

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
        RearNotificationCards.reconcile?.invoke(keys)
        (active.keys.toList() + shown.toList()).filterNot { it in keys }.distinct().forEach(::remove)
        snapshot.forEach { active[it.key] = it; publish(it) }
    }

    private fun eligible(sbn: StatusBarNotification): Boolean {
        if (!enabled() || sbn.user != Process.myUserHandle()) return false
        val selected = apps()
        if (selected.isNotEmpty() && sbn.packageName !in selected) return false
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

    private fun publish(sbn: StatusBarNotification): Boolean {
        if (!eligible(sbn)) { hide(sbn.key); return false }
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
        val redacted = locked && visibility != Notification.VISIBILITY_PUBLIC
        return runCatching {
            val views = buildViews(host, sbn, token, redacted)
            val accepted = RearNotificationCards.post?.invoke(sbn.key, views) == true
            if (accepted) shown.add(sbn.key)
            accepted
        }.onFailure { YLog.warn("Rear notification unavailable: ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    private fun buildViews(context: Context, sbn: StatusBarNotification, token: String, redacted: Boolean): RemoteViews {
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
        return RemoteViews("hk.uwu.reareye", R.layout.rear_notification_card).apply {
            setTextViewText(R.id.rear_notification_token, token)
            setTextViewText(R.id.rear_notification_app, app)
            n.smallIcon?.let { setImageViewIcon(R.id.rear_notification_icon, it) }
            setTextViewText(R.id.rear_notification_title, if (redacted) app else title.ifBlank { app }.take(160))
            setTextViewText(R.id.rear_notification_text, if (redacted) "有一条新消息" else body.take(1200))
            setTextViewText(R.id.rear_notification_hint, if (sbn.isClearable) "左右划掉 · 与主屏同步" else "持续通知 · 由原应用结束")
            setViewVisibility(R.id.rear_notification_clear, if (sbn.isClearable) View.VISIBLE else View.GONE)
            if (!redacted) {
                n.contentIntent?.let { setOnClickPendingIntent(R.id.rear_notification_card, it) }
                val maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                if (maximum > 0 || indeterminate) {
                    setViewVisibility(R.id.rear_notification_progress, View.VISIBLE)
                    setProgressBar(R.id.rear_notification_progress, maximum.coerceAtLeast(1), extras.getInt(Notification.EXTRA_PROGRESS, 0), indeterminate)
                }
                if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0L) {
                    setViewVisibility(R.id.rear_notification_chronometer, View.VISIBLE)
                    setChronometer(R.id.rear_notification_chronometer, SystemClock.elapsedRealtime() + n.`when` - System.currentTimeMillis(), null, true)
                    setChronometerCountDown(R.id.rear_notification_chronometer, extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false))
                }
                val actions = n.actions.orEmpty().filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }.take(2)
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
        val token = view.findViewById<TextView>(R.id.rear_notification_token)?.text?.toString() ?: return
        val key = tokens.entries.firstOrNull { it.value == token }?.key ?: return
        val sbn = active[key] ?: return
        if (!sbn.isClearable) return
        view.findViewById<View>(R.id.rear_notification_clear)?.setOnClickListener { dismissSource(key) }
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
        view.setOnTouchListener { v, event ->
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
                receiverContext?.unregisterReceiver(privacyReceiver)
                receiverContext = null
                listener = null
                RearNotificationCards.onReady = null
            } finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else { main.post(cleanup); check(done.await(3, TimeUnit.SECONDS)) { "Notification cleanup timed out" } }
    }
}
