package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import hk.uwu.reareye.hook.support.YLog
import java.util.WeakHashMap
import kotlin.math.abs

/** Own only content transforms; the native pager keeps ownership of its page transforms. */
internal object NativeRearMotion {
    var hostLoader: ClassLoader? = null
    private val flips = WeakHashMap<View, ValueAnimator>()
    private fun pageFor(view: View): View {
        var node: View? = view
        while (node != null) {
            if (node.javaClass.name == "com.xiaomi.subscreencenter.SmartAssistantPanel") return node
            node = node.parent as? View
        }
        return view.rootView
    }
    fun isFlipping(view: View) = flips.containsKey(pageFor(view))

    fun spring(view: View, values: Map<String, Float>, response: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) { assign(view, values); return }
        runCatching {
            val loader = hostLoader ?: view.context.classLoader
            val folme = loader.loadClass("miuix.animation.Folme")
            val controller = folme.getMethod("use", View::class.java).invoke(null, view)
            val configType = loader.loadClass("miuix.animation.base.AnimConfig")
            val config = configType.getConstructor().newInstance()
            val ease = loader.loadClass("miuix.animation.FolmeEase")
                .getMethod("spring", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(null, 1f, response)
            configType.getMethod("setEase", loader.loadClass("miuix.animation.utils.EaseManager\$EaseStyle"))
                .invoke(config, ease)
            val property = loader.loadClass("miuix.animation.property.ViewProperty")
            val args = values.flatMap { (name, value) -> listOf(property.getField(name).get(null), value) }
                .plus(config).toTypedArray()
            loader.loadClass("miuix.animation.FolmeStyle").getMethod("to", Array<Any>::class.java)
                .invoke(controller, args as Any)
        }.onFailure {
            assign(view, values)
            YLog.warn("Rear content motion unavailable: ${it.javaClass.simpleName}")
        }
    }

    private fun assign(view: View, values: Map<String, Float>) = values.forEach { (name, value) ->
        when (name) {
            "SCALE_X" -> view.scaleX = value
            "SCALE_Y" -> view.scaleY = value
            "TRANSLATION_Y" -> view.translationY = value
            "ALPHA" -> view.alpha = value
        }
    }

    fun aod(content: View, asleep: Boolean, animate: Boolean) {
        // Music's factory album shrinks from 66 to 58 units; use its native
        // Folme spring responses (enter .72, wake .5) for notification content.
        val resources = content.resources
        val pkg = "com.xiaomi.subscreencenter"
        fun target(name: String) = content.findViewById<View>(resources.getIdentifier(name, "id", pkg))
        fun apply(view: View?, values: Map<String, Float>) {
            if (view == null) return
            if (animate) spring(view, values, if (asleep) .72f else .5f) else assign(view, values)
        }
        // Keep text at its readable native size; music moves its album and text
        // independently instead of scaling the entire page (and its controls).
        content.scaleX = 1f; content.scaleY = 1f; content.translationY = 0f; content.alpha = 1f
        val scale = if (asleep) 58f / 66f else 1f
        apply(target("notification_icon"), mapOf("SCALE_X" to scale, "SCALE_Y" to scale))
        apply(target("notification_container"), mapOf("TRANSLATION_Y" to
            if (asleep) -12f * resources.displayMetrics.density else 0f))
    }

    fun flip(card: View, returning: Boolean = false, swap: () -> Unit) {
        if (isFlipping(card)) return
        if (!ValueAnimator.areAnimatorsEnabled() || !card.isAttachedToWindow) { swap(); return }
        // Transform the entire assistant page, including backdrop, indicators
        // and footer. Keep the wallpaper underneath stationary: transforming the
        // decor as well makes MIUI's live backdrop blur sample its own frames.
        val page = pageFor(card)
        // Preserve the resting backdrop throughout the flip. Only the native
        // edge stroke is temporary; switching material here changes its tint.
        val restoreGlass = NativeRearGlass.begin(page)
        val layer = page.layerType
        page.setLayerType(View.LAYER_TYPE_NONE, null)
        val camera = page.cameraDistance
        val pivotX = page.pivotX
        val pivotY = page.pivotY
        val scaleX = page.scaleX
        val scaleY = page.scaleY
        page.pivotX = page.width / 2f
        page.pivotY = page.height / 2f
        page.cameraDistance = page.width * 8f
        var swapped = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                if (t >= .5f && !swapped) { swapped = true; swap() }
                // Swap only edge-on: neither mirrored text nor overlapping faces.
                val direction = if (returning) -1f else 1f
                page.rotationY = direction * if (t < .5f) -180f * t else 180f * (1f - t)
                val scale = 1f - .2f * (1f - abs(t * 2f - 1f))
                page.scaleX = scaleX * scale; page.scaleY = scaleY * scale
                page.invalidate()
            }
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { animator.cancel() }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!swapped && card.isAttachedToWindow) { swapped = true; swap() }
                page.rotationY = 0f; page.scaleX = scaleX; page.scaleY = scaleY
                page.pivotX = pivotX; page.pivotY = pivotY
                page.cameraDistance = camera
                restoreGlass()
                page.setLayerType(layer, null)
                page.invalidate()
                card.removeOnAttachStateChangeListener(attachment)
                flips.remove(page)
            }
        })
        flips[page] = animator
        card.addOnAttachStateChangeListener(attachment)
        animator.start()
    }
}
