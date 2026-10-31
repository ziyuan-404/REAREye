package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import dalvik.system.PathClassLoader
import hk.uwu.reareye.hook.support.YLog

/** Add the installed OS4 edge stroke without replacing the factory backdrop. */
internal object NativeRearGlass {
    private var tokenClass: Class<*>? = null
    fun begin(page: View): () -> Unit {
        val previousOutline = page.outlineProvider
        val previousClip = page.clipToOutline
        val previousElevation = page.elevation
        return runCatching {
            val context = page.context
            val type = tokenClass ?: PathClassLoader(
                context.packageManager.getApplicationInfo("miui.systemui.plugin", 0).sourceDir,
                View::class.java.classLoader).loadClass("miuix.theme.token.BloomStrokeToken").also { tokenClass = it }
            val parameters = (type.getField("Glass_Stroke_Big_Dark").get(null) as FloatArray).clone()
            require(parameters.size == 21)
            // Match the host's G3.d.y density conversion. The token affects only
            // the rim; blur, color blending and transparency remain untouched.
            val density = page.resources.displayMetrics.density
            parameters[0] = parameters[0] * density + .5f
            parameters[6] = parameters[6] * density + .5f
            val stroke = View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java)
            val radius = context.classLoader.loadClass("o2.j").getField("t").getInt(null).toFloat()
            stroke.invoke(page, parameters)
            page.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            page.clipToOutline = true
            val restore: () -> Unit = {
                runCatching {
                    stroke.invoke(page, FloatArray(0))
                }
                page.outlineProvider = previousOutline; page.clipToOutline = previousClip
                page.elevation = previousElevation
            }
            restore
        }.getOrElse {
            YLog.warn("Native OS4 edge unavailable: ${it.javaClass.simpleName}: ${it.message}");
            { page.outlineProvider = previousOutline; page.clipToOutline = previousClip }
        }
    }
}
