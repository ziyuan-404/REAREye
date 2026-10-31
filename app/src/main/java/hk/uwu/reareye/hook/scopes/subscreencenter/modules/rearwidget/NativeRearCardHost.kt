package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.view.View
import hk.uwu.reareye.hook.support.YLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Bind mirror content to the factory widget's own backdrop and outline state. */
internal object NativeRearCardHost {
    private val widgets = WeakHashMap<View, WeakReference<Any>>()

    fun register(widget: Any, root: View) {
        widgets[root] = WeakReference(widget)
        runCatching {
            val context = root.context
            val resources = context.resources
            fun id(name: String, type: String) = resources.getIdentifier(name, type, context.packageName)
            val utility = context.classLoader.loadClass("G3.d")
            val viewInt = arrayOf(View::class.java, Int::class.javaPrimitiveType)
            // Exact native SmartAssistantPanel backdrop pipeline and resource arrays.
            utility.getMethod("r", *viewInt).invoke(null, root, 1)
            utility.getMethod("G", *viewInt).invoke(null, root, 1)
            utility.getMethod("s", *viewInt).invoke(null, root,
                resources.getDimensionPixelSize(id("assistant_panel_blur_radius", "dimen")))
            utility.getMethod("p", View::class.java, IntArray::class.java, IntArray::class.java)
                .invoke(null, root, resources.getIntArray(id("global_blur_color", "array")),
                    resources.getIntArray(id("global_blur_mode", "array")))
        }.onFailure { YLog.warn("Native card backdrop unavailable: ${it.javaClass.simpleName}") }
    }

    fun container(view: View): View? {
        var node: View? = view
        repeat(8) {
            if (node in widgets) return node
            node = node?.parent as? View
        }
        return null
    }

    fun beginDrag(view: View): () -> Unit {
        val widget = container(view)?.let { widgets[it]?.get() } ?: return {}
        return runCatching {
            val type = widget.javaClass.superclass!!
            val flag = type.getDeclaredField("r").apply { isAccessible = true }
            val previous = flag.getBoolean(widget)
            val transition = type.getDeclaredMethod("E", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            transition.invoke(widget, true)
            val restore: () -> Unit = { runCatching { transition.invoke(widget, previous) }; Unit }
            restore
        }.getOrElse {
            YLog.warn("Native card outline unavailable: ${it.javaClass.simpleName}")
            val restore: () -> Unit = {}
            restore
        }
    }

}
