package hk.uwu.reareye.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.TextView
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import org.json.JSONArray
import org.json.JSONObject

/** Restores a local LSPosed preference backup through the module's normal service API. */
class LocalPreferenceRestoreActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var attempts = 0
    private var restoredCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 20f; setPadding(32, 80, 32, 32) }
        setContentView(status)
        restoredCount = savedInstanceState?.getInt("restoredCount", -1) ?: -1
        if (restoredCount >= 0) status.text = "配置恢复完成：$restoredCount 项。"
        else restoreWhenReady()
    }

    private fun restoreWhenReady() {
        val prefs = getPrefsManager()
        if (!prefs.isRemoteReady()) {
            status.text = "等待模块远程配置服务连接…"
            if (++attempts < 80) main.postDelayed(::restoreWhenReady, 250L)
            else status.text = "远程配置服务未连接，尚未写入配置"
            return
        }
        runCatching {
            val source = File(filesDir, "rear-experience-restore.json")
            require(source.length() in 1..1_000_000L) { "Local backup absent or too large" }
            val root = JSONObject(source.readText())
            val values = linkedMapOf<String, Any>()
            val entries = root.optJSONObject("entries") ?: JSONObject()
            require(entries.length() <= 256)
            entries.keys().forEach { key ->
                val bytes = Base64.decode(entries.getString(key), Base64.DEFAULT)
                require(bytes.size <= 256_000)
                val value = object : ObjectInputStream(ByteArrayInputStream(bytes)) {
                    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
                        if (desc.name !in setOf("java.lang.Boolean", "java.lang.Number", "java.lang.Integer",
                                "java.lang.Long", "java.lang.Float", "java.lang.String", "java.util.HashSet",
                                "java.util.LinkedHashSet")) throw InvalidClassException("Unsupported backup value")
                        return super.resolveClass(desc)
                    }
                }.use { it.readObject() }
                values[key] = value
            }
            val overrides = root.optJSONObject("values") ?: JSONObject()
            overrides.keys().forEach { key ->
                val value = overrides.get(key)
                values[key] = if (value is JSONArray) (0 until value.length()).map { value.getString(it) }.toSet() else value
            }
            val editor = prefs.prefs.edit()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> {
                        require(value.all { it is String })
                        editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                    else -> error("Unsupported preference type")
                }
            }
            check(editor.commit()) { "Preference commit failed" }
            val actual = prefs.all()
            check(values.all { (key, value) -> actual[key] == value }) { "Preference readback differs" }
            check(source.delete()) { "Could not remove consumed local backup" }
            restoredCount = values.size
            status.text = "配置恢复完成：$restoredCount 项。"
            Log.i("REAREye-LocalRestore", "Restored ${values.size} preferences through module service")
        }.onFailure {
            status.text = "配置未恢复：${it.javaClass.simpleName}。备份已保留。"
            Log.e("REAREye-LocalRestore", "Local preference restore failed", it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("restoredCount", restoredCount)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
}
