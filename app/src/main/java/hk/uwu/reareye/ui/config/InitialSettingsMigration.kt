package hk.uwu.reareye.ui.config

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One-time local handoff; the staged file stays in this app's private data directory. */
internal object InitialSettingsMigration {
    fun apply(context: Context,manager: PrefsManager) {
        if(!manager.isRemoteReady())return
        val file=File(context.filesDir,"initial-settings.json")
        if(!file.isFile)return
        val remoteFiles=File(context.filesDir,"initial-remote-files")
        for(remote in remoteFiles.listFiles().orEmpty()) {
            if(remote.isFile&&!manager.writeRemoteFile(remote.name,remote))return
        }
        val settings=JSONObject(file.readText())
        val editor=manager.prefs.edit()
        settings.keys().forEach { key ->
            if(key.startsWith("agent_")||key.startsWith("__"))return@forEach
            val item=settings.getJSONObject(key)
            when(item.getString("type")) {
                "boolean"->editor.putBoolean(key,item.getBoolean("value"))
                "int"->editor.putInt(key,item.getInt("value"))
                "long"->editor.putLong(key,item.getLong("value"))
                "float"->editor.putFloat(key,item.getDouble("value").toFloat())
                "string"->editor.putString(key,item.getString("value"))
                "set"->{
                    val values=item.getJSONArray("value")
                    editor.putStringSet(key,(0 until values.length()).map { values.getString(it) }.toSet())
                }
            }
        }
        val committed=editor.putBoolean("rear_settings_handoff_completed",true).commit()
        // RemotePreferences writes are synchronous in this adapter. Keep a failed handoff
        // available for retry instead of treating a disconnected service as migration.
        if(committed&&manager.getBoolean("rear_settings_handoff_completed",false)) {
            file.delete()
            remoteFiles.listFiles().orEmpty().forEach { it.delete() }
            remoteFiles.delete()
        }
    }
}
