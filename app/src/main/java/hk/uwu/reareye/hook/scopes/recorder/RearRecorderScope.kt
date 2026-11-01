package hk.uwu.reareye.hook.scopes.recorder

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

/** Uses the stock focus protocol for playback on both displays. */
@OptIn(DexKitExperimentalApi::class)
class RearRecorderScope : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadApp("com.android.soundrecorder") {
            if (!processName.endsWith(":remote")) return@loadApp
            runCatching {
                val bridge = runtime.manage(createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = resolveHookPackageVersionCode(hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir),
                    sourceDir = hookAppInfo.sourceDir, dataDir = hookAppInfo.dataDir,
                ))
                val seek = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "RECORDER_REAR_SEEK_PLAYBACK") {
                    findMethod { matcher { paramTypes("int", "boolean"); returnType = "void"; usingStrings("changePosRelative seek to: ") } }.singleOrNull()
                })
                val binderType = seek.className.toClass()
                val pause = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "RECORDER_REAR_PAUSE_PLAYBACK") {
                    findMethod { matcher { declaredClass = seek.className; paramCount(0); returnType = "void"; usingNumbers(2) } }.singleOrNull()
                })
                val resumeMethod = binderType.getDeclaredMethod(seek.methodName, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                val pauseMethod = binderType.getDeclaredMethod(pause.methodName).apply { isAccessible = true }
                val stop = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "RECORDER_REAR_STOP_PLAYBACK") {
                    findMethod { matcher { declaredClass = "com.android.soundrecorder.RecorderService"; paramTypes("boolean"); returnType = "void"; usingStrings("sendMessageToStopPlayback send message") } }.singleOrNull()
                })
                val serviceType = "com.android.soundrecorder.RecorderService".toClass()
                val stopMethod = serviceType.getDeclaredMethod(stop.methodName, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                val serviceSingleton = serviceType.declaredFields.single { it.type == serviceType && java.lang.reflect.Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
                val binderField = serviceType.declaredFields.single { it.type == binderType }.apply { isAccessible = true }
                val playerField = serviceType.declaredFields.single { it.type.name == "org.videolan.libvlc.VlcMediaPlayer" }.apply { isAccessible = true }
                val playerType = playerField.type
                val isPlaying = playerType.getMethod("isPlaying")
                val position = playerType.getMethod("getCurrentPosition")
                var registered: Service? = null
                var currentPlayer: Any? = null
                var session = UUID.randomUUID().toString()
                val actionName = "hk.uwu.reareye.recorder.PLAYBACK"
                fun enabled() = hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, false) &&
                    "com.android.soundrecorder" in hookPrefs.getStringSet(ConfigKeys.REAR_NOTIFICATION_SYNC_APPS, emptySet())
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (!enabled() || intent.action != actionName || intent.getStringExtra("session") != session) return
                        val service = registered ?: return
                        val player = playerField.get(service) ?: return
                        if (player !== currentPlayer) return
                        val playing = isPlaying.invoke(player) == true
                        if (playing != intent.getBooleanExtra("playing", !playing)) return
                        runCatching {
                            val binder = binderField.get(service)
                            if (intent.getBooleanExtra("stop", false)) stopMethod.invoke(service, true)
                            else if (playing) pauseMethod.invoke(binder)
                            else resumeMethod.invoke(binder, (position.invoke(player) as Number).toInt(), true)
                        }.onFailure { YLog.warn("Rear recorder playback control failed", it) }
                    }
                }
                fun cleanup() {
                    registered?.let { runCatching { it.unregisterReceiver(receiver) } }
                    registered = null; currentPlayer = null; session = UUID.randomUUID().toString()
                }
                runtime.onClose { cleanup() }
                serviceType.resolve().firstMethod { name = "onDestroy"; parameterCount = 0 }.hook {
                    before { if (registered === instance) cleanup() }
                }
                Notification.Builder::class.java.resolve().firstMethod { name = "build"; parameterCount = 0 }.hook {
                    after {
                        if (!enabled()) return@after
                        val notification = result as? Notification ?: return@after
                        if (notification.channelId != "channel_id_record" ||
                            notification.extras.containsKey("miui.focus.param") && !notification.extras.getBoolean("hk.uwu.reareye.playback.focus", false)) return@after
                        val service = serviceSingleton.get(null) as? Service ?: return@after
                        fun resource(name: String, type: String) = service.resources.getIdentifier(name, type, service.packageName)
                        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        val playbackTexts = listOf("notification_playback", "notification_pause_playback").mapNotNull {
                            resource(it, "string").takeIf { id -> id != 0 }?.let(service::getString)
                        }
                        if (text !in playbackTexts) return@after
                        val player = playerField.get(service) ?: return@after
                        if (registered !== service) {
                            cleanup()
                            service.registerReceiver(receiver, IntentFilter(actionName).apply { addDataScheme("reareye-recorder") }, Context.RECEIVER_NOT_EXPORTED)
                            registered = service
                        }
                        if (currentPlayer !== player) { currentPlayer = player; session = UUID.randomUUID().toString() }
                        // Native resume seeks asynchronously. Its notification text
                        // describes the requested state before VLC reports isPlaying.
                        val playing = text == service.getString(resource("notification_playback", "string"))
                        val elapsed = (position.invoke(player) as Number).toLong().coerceAtLeast(0)
                        fun control(stopPlayback: Boolean): Notification.Action {
                            val intent = Intent(actionName).setPackage(service.packageName)
                                .putExtra("session", session).putExtra("playing", playing).putExtra("stop", stopPlayback)
                                .setData(android.net.Uri.parse("reareye-recorder://playback/$session/$playing/$stopPlayback"))
                            val pending = PendingIntent.getBroadcast(service, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            val title = if (stopPlayback) "结束播放" else if (playing) "暂停播放" else "继续播放"
                            return Notification.Action.Builder(null as android.graphics.drawable.Icon?, title, pending).build().apply {
                                extras.putString("icon_name", if (stopPlayback) "action_record_end" else if (playing) "action_pause" else "action_restart")
                            }
                        }
                        val actions = Bundle().apply {
                            putParcelable("miui.focus.action_1", control(false))
                            putParcelable("miui.focus.action_2", control(true))
                        }
                        notification.extras.putBundle("miui.focus.actions", actions)
                        fun timer(night: Boolean): RemoteViews {
                            val layout = "focus_notification_layout_" + (if (night) "night" else "light") + (if (playing) "" else "_pause")
                            return RemoteViews(service.packageName, resource(layout, "layout")).apply {
                                if (playing) setChronometer(resource("chronometer", "id"), SystemClock.elapsedRealtime() - elapsed, "%s", true)
                                else setTextViewText(resource("status_bar_pause_time", "id"), android.text.format.DateUtils.formatElapsedTime(elapsed / 1000))
                            }
                        }
                        notification.extras.putParcelable("miui.focus.rvBar", timer(false))
                        notification.extras.putParcelable("miui.focus.rvBarNight", timer(true))
                        val now = System.currentTimeMillis()
                        val timing = JSONObject().put("timerType", if (playing) 1 else 2)
                            .put("timerWhen", now - elapsed).put("timerSystemCurrent", now)
                        fun waveform(src: String) = JSONObject().put("type", 2).put("pic", src).put("loop", playing).put("autoplay", playing)
                        val common = JSONObject(timing.toString()).put("protocol", 1).put("scene", "recorder")
                            .put("content", if (playing) "正在回放录音…" else "回放已暂停")
                            .put("enableFloat", false).put("updatable", true)
                        val detail = JSONObject(common.toString()).put("business", "audio_recording")
                            .put("notifyId", "com.android.soundrecorder.reareye.playback")
                            .put("animTextInfo", JSONObject().put("timerInfo", timing)
                                .put("animIconInfo", JSONObject().put("type", 1).put("src", "voiceWaveBig").put("number", 0).put("loop", playing).put("autoplay", playing)))
                            .put("actions", JSONArray().put(JSONObject().put("type", 0).put("actionIntentType", 0).put("action", "miui.focus.action_1"))
                                .put(JSONObject().put("type", 0).put("actionIntentType", 0).put("action", "miui.focus.action_2")))
                            .put("param_island", JSONObject().put("islandProperty", 2).put("islandPriority", 1).put("islandTimeout", if (playing) 43200 else 600)
                                .put("bigIslandArea", JSONObject()
                                    .put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", waveform("voiceWaveSmall")))
                                    .put("sameWidthDigitInfo", JSONObject().put("timerInfo", timing)))
                                .put("smallIslandArea", JSONObject().put("picInfo", waveform("voiceWaveSmall"))))
                        common.put("param_v2", detail)
                        notification.extras.putString("miui.focus.param", common.toString())
                        notification.extras.putBoolean("hk.uwu.reareye.playback.focus", true)
                    }
                }
                YLog.info("Rear recorder playback notification controls installed")
            }.onFailure { YLog.warn("Rear recorder playback adaptation unavailable", it) }
        }
    }
}
