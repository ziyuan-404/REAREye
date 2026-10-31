package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.graphics.Outline
import android.graphics.Rect
import android.os.Binder
import android.os.IBinder
import android.view.View
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server

/** Small native launch-cookie payload, with no retained View, Activity or bitmap. */
internal data class RearLaunchOrigin(val kind:Int,val bounds:Rect,val radius:Float) {
    companion object {
        const val PICKER=1
        const val NOTIFICATION=2
        private const val PREFIX="hrs.rear.origin.v1|"
        private val cookieOrigins=java.util.Collections.synchronizedMap(java.util.WeakHashMap<IBinder,RearLaunchOrigin?>())
        fun capture(view:View,kind:Int):RearLaunchOrigin? {
            val area=Rect()
            if(!view.getGlobalVisibleRect(area)||area.isEmpty)return null
            val outline=Outline()
            view.outlineProvider?.getOutline(view,outline)
            val radius=outline.radius.takeIf { it>=0f }?:minOf(area.width(),area.height())*if(kind==PICKER).5f else .1f
            return RearLaunchOrigin(kind,area,radius)
        }
        fun fromTask(info:Any):RearLaunchOrigin? {
            val cookies=Server.field(info,"launchCookies") as? List<*>?:return null
            return cookies.asReversed().firstNotNullOfOrNull { cookie -> runCatching {
                val binder=cookie as? IBinder?:return@runCatching null
                if(cookieOrigins.containsKey(binder))return@runCatching cookieOrigins[binder]
                val value=binder.interfaceDescriptor?:return@runCatching null
                if(!value.startsWith(PREFIX)||value.length>160) {
                    cookieOrigins[binder]=null
                    return@runCatching null
                }
                val fields=value.removePrefix(PREFIX).split(',')
                if(fields.size!=6)return@runCatching null
                val kind=fields[0].toInt()
                val bounds=Rect(fields[1].toInt(),fields[2].toInt(),fields[3].toInt(),fields[4].toInt())
                if(kind !in PICKER..NOTIFICATION||bounds.isEmpty)return@runCatching null
                val radius=fields[5].toFloat()
                if(!radius.isFinite())return@runCatching null
                RearLaunchOrigin(kind,bounds,radius.coerceIn(0f,minOf(bounds.width(),bounds.height())/2f))
                    .also { cookieOrigins[binder]=it }
            }.getOrNull() }
        }
    }
    fun cookie():IBinder=Binder().apply {
        attachInterface(null,"$PREFIX$kind,${bounds.left},${bounds.top},${bounds.right},${bounds.bottom},$radius")
    }
}
