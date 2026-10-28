package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import java.util.concurrent.ConcurrentHashMap

class SystemUiNotificationBridgeHook : RoxyHooker() {
    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val TAG = "REAREye-NotifBridge"
    }

    private val activeSnapshots = ConcurrentHashMap<String, NotificationRouteSnapshot>()
    private val routeClient = NotificationRouteBridgeClient()
    private val main = Handler(Looper.getMainLooper())
    private var visibleKeys: Set<String>? = null
    private var syncEnabled: () -> Boolean = { false }
    private val visibilityHeartbeat = object : Runnable {
        override fun run() {
            dispatchVisibleKeys()
            main.postDelayed(this, 5_000L)
        }
    }

    @Volatile
    private var hostContext: Context? = null

    override fun onHotReloadQuiesce() {
        val unbound = routeClient.unbind()
        if (!unbound) {
            throw IllegalStateException("$TAG failed to unbind route bridge during reload")
        }
        activeSnapshots.clear()
        main.removeCallbacks(visibilityHeartbeat)
        visibleKeys = null
        syncEnabled = { false }
        hostContext = null
    }

    override fun PackageScope.onHook() {
        loadApp(SYSTEM_UI_PACKAGE) {
            syncEnabled = { hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, false) }
            debugLog("loadApp process=$processName package=$packageName")

            this.lifecycle {
                // Rebind from the restored attach event after the old generation unbinds.
                onAttach(replay = true) {
                    val context = hookAppContext ?: this.context
                    hostContext = context.applicationContext ?: context
                    debugLog(
                        "onAttach hostContext=${hostContext?.packageName} action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER} target=${NotificationRouteBridgeContract.HOOK_HOST_PACKAGE}"
                    )
                    bindRouteBridge("app_attach")
                    main.removeCallbacks(visibilityHeartbeat)
                    main.post(visibilityHeartbeat)
                }
            }

            runCatching {
                "com.android.systemui.statusbar.notification.collection.ShadeListBuilder".toClass().resolve()
                    .firstMethod { name = "buildList"; parameterCount = 0 }.hook {
                        after {
                            if (!hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, false)) return@after
                            runCatching {
                                val entries = instance!!.asResolver().firstField { name = "mReadOnlyNotifList" }.get<List<*>>()
                                    ?: return@runCatching
                                val keys = linkedSetOf<String>()
                                fun collect(entry: Any?, depth: Int) {
                                    if (entry == null || depth > 8) return
                                    val representative = entry.javaClass.methods.firstOrNull {
                                        it.name == "getRepresentativeEntry" && it.parameterCount == 0
                                    }?.invoke(entry)
                                    if (representative != null) {
                                        val field = representative.javaClass.getField("mSbn")
                                        val sbn = field.get(representative) as? StatusBarNotification
                                        if (sbn != null) keys.add(sbn.key)
                                    }
                                    // Group children survive summary filtering; walk those too.
                                    val children = (entry.javaClass.methods.firstOrNull {
                                        it.name == "getRawChildren" && it.parameterCount == 0
                                    }?.invoke(entry) ?: entry.javaClass.fields.firstOrNull {
                                        it.name == "rawChildren"
                                    }?.get(entry)) as? List<*>
                                    children?.forEach { collect(it, depth + 1) }
                                }
                                entries.forEach { collect(it, 0) }
                                val reasons = linkedMapOf<String, Int>()
                                val allEntries = instance!!.asResolver().firstField { name = "mAllEntries" }.get<Collection<*>>()
                                    .orEmpty()
                                allEntries.filterNotNull().forEach { entry ->
                                    val state = entry.javaClass.getField("attachState").get(entry)
                                    val filter = state.javaClass.getField("excludingFilter").get(state)
                                    val name = if (filter == null) "visible" else
                                        filter.javaClass.getField("mName").get(filter) as String
                                    reasons[name] = (reasons[name] ?: 0) + 1
                                    // Locking the front screen changes its shade filters. The
                                    // rear listener applies its own lock-screen privacy rules;
                                    // preserve notifications hidden only by keyguard recency.
                                    if (name == "KeyguardCoordinator" || name == "OriginalUnseenKeyguardCoordinator")
                                        collect(entry, 0)
                                }
                                visibleKeys = keys
                                if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false))
                                    YLog.debug("[REAREye-NotifBridge] visible shade keys=${keys.size} filters=$reasons")
                                dispatchVisibleKeys()
                            }.onFailure { YLog.warn("Rear visible notification snapshot unavailable: ${it.javaClass.simpleName}") }
                        }
                    }
                YLog.info("[REAREye-NotifBridge] shade visibility hook installed")
            }.onFailure { YLog.warn("Rear shade visibility hook unavailable: ${it.javaClass.simpleName}") }

            runCatching {
                val runnableClz =
                    $$$"com.android.systemui.statusbar.notification.MiuiNotificationListener$$ExternalSyntheticLambda2".toClass()
                        .resolve()
                runnableClz.firstMethod {
                    name = "run"
                }.hook {
                    after {
                        val sbn = instance!!.asResolver().firstField {
                            type(StatusBarNotification::class.java)
                        }.get<StatusBarNotification>()
                        handleNotificationPosted(sbn)
                    }
                }
            }.onFailure {
                debugLog("hook onNotificationPosted failed err=${it.message}")
            }.onSuccess {
                debugLog("hook onNotificationPosted installed")
            }

            runCatching {
                val runnableClz =
                    $$$"com.android.systemui.statusbar.notification.MiuiNotificationListener$$ExternalSyntheticLambda1".toClass()
                        .resolve()
                runnableClz.firstMethod {
                    name = "run"
                }.hook {
                    after {
                        val sbn = instance!!.asResolver().firstField {
                            type(StatusBarNotification::class.java)
                        }.get<StatusBarNotification>()
                        handleNotificationRemoved(
                            sbn = sbn,
                            removeReason = instance!!.asResolver().lastField {
                                type(Int::class.java)
                            }.get<Int>() ?: 1,
                        )
                    }
                }
            }.onFailure {
                debugLog("hook onNotificationRemoved failed err=${it.message}")
            }.onSuccess {
                debugLog("hook onNotificationRemoved installed")
            }
        }
    }

    private fun dispatchVisibleKeys() {
        if (!syncEnabled()) return
        val keys = visibleKeys ?: return
        routeClient.dispatch(NotificationRouteBridgeContract.Subchannel.VISIBLE_NOTIFICATION_KEYS, Bundle().apply {
            putStringArrayList(NotificationRouteBridgeContract.Keys.VISIBLE_KEYS, ArrayList(keys))
        })
    }

    private fun PackageScope.bindRouteBridge(reason: String): Boolean {
        val context = hostContext ?: run {
            debugLog("route bridge bind skipped reason=$reason hostContext=null")
            return false
        }
        debugLog(
            "route bridge bind start reason=$reason connected=${routeClient.isConnected()} action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER}"
        )
        val ok = routeClient.bind(
            context = context,
            onConnected = {
                debugLog("route bridge connected reason=$reason")
            },
            onClosed = {
                debugLog("route bridge closed reason=$it")
            },
        )
        if (!ok) {
            debugLog(
                "route bridge handshake pending reason=$reason action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER}"
            )
        }
        return ok
    }

    private fun PackageScope.handleNotificationPosted(sbn: StatusBarNotification?) {
        val current = sbn ?: return
        val snapshot = NotificationRouteSnapshot.fromStatusBarNotification(current)
        if (snapshot == null) {
            NotificationRouteSnapshot.identityKeyFor(current)?.let { key ->
                activeSnapshots.remove(key)?.let { removed ->
                    dispatchRemoved(removed, removeReason = 1, reason = "filtered_post")
                }
            }
            return
        }

        activeSnapshots[snapshot.stableKey()] = snapshot
        debugLog(
            "posted accepted key=${snapshot.stableKey()} cacheSize=${activeSnapshots.size} channel=${snapshot.channelId} pkg=${snapshot.packageName}"
        )
        dispatchPosted(snapshot, reason = "live_post")
    }

    private fun PackageScope.handleNotificationRemoved(
        sbn: StatusBarNotification?,
        removeReason: Int
    ) {
        val current = sbn ?: return
        val snapshot = NotificationRouteSnapshot.identityKeyFor(current)
            ?.let(activeSnapshots::remove)
            ?: NotificationRouteSnapshot.fromStatusBarNotification(
                current,
                requirePlainExtras = false,
            )
            ?: run {
                return
            }
        debugLog(
            "removed accepted key=${snapshot.stableKey()} cacheSize=${activeSnapshots.size} reason=$removeReason channel=${snapshot.channelId} pkg=${snapshot.packageName}"
        )
        dispatchRemoved(snapshot, removeReason, reason = "live_remove")
    }

    private fun PackageScope.dispatchPosted(snapshot: NotificationRouteSnapshot, reason: String) {
        val ok = routeClient.dispatch(
            NotificationRouteBridgeContract.Subchannel.NOTIFICATION_POSTED,
            snapshot.toBundle(),
        )
        if (!ok) {
            debugLog("dispatch posted failed key=${snapshot.stableKey()} reason=$reason")
        }
    }

    private fun PackageScope.dispatchRemoved(
        snapshot: NotificationRouteSnapshot,
        removeReason: Int,
        reason: String,
    ) {
        val ok = routeClient.dispatch(
            NotificationRouteBridgeContract.Subchannel.NOTIFICATION_REMOVED,
            snapshot.toRemovalBundle(removeReason),
        )
        if (!ok) {
            debugLog("dispatch removed failed key=${snapshot.stableKey()} reason=$reason")
        }
    }

    private fun PackageScope.debugLog(message: String) {
        if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            YLog.debug("[$TAG] $message")
        }
    }
}
