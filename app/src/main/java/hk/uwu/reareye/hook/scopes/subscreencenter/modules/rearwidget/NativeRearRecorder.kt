package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.json.JSONObject

/** Reuses the recorder's timer/actions and the installed SystemUI's artwork. */
internal object NativeRearRecorder {
    const val CONTROLS = "rear_notification_recorder_controls"

    fun actions(notification: Notification): List<Notification.Action> {
        val focus = notification.extras.getBundle("miui.focus.actions")
        val params = parameters(notification)
        val declared = params?.optJSONArray("actions")
        val keys = if (declared != null) (0 until declared.length()).mapNotNull {
            declared.optJSONObject(it)?.optString("action")?.takeIf(String::isNotBlank)
        } else focus?.keySet().orEmpty().sorted()
        val result = keys.mapNotNull { key ->
            focus?.getParcelable(key, Notification.Action::class.java)
        }.filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }
        return result.ifEmpty {
            notification.actions.orEmpty().filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }
        }.take(3)
    }

    private fun parameters(notification: Notification): JSONObject? = runCatching {
        JSONObject(notification.extras.getString("miui.focus.param") ?: return null).let {
            it.optJSONObject("param_v2") ?: it
        }
    }.getOrNull()

    fun bind(context: Context, root: View, notification: Notification, execute: (Int) -> Unit): Boolean {
        val actions = actions(notification)
        if (actions.isEmpty()) return false
        fun id(name: String) = context.resources.getIdentifier(name, "id", context.packageName)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val container = root.findViewById<LinearLayout>(id("notification_container")) ?: return false
        val title = root.findViewById<TextView>(id("notification_title"))
        val status = root.findViewById<TextView>(id("notification_content"))
        val plugin = runCatching {
            context.createPackageContext("miui.systemui.plugin", 0).let { packageContext ->
                val config = android.content.res.Configuration(packageContext.resources.configuration)
                config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_YES
                packageContext.createConfigurationContext(config)
            }
        }.getOrNull()
        fun artwork(vararg names: String): Drawable? = names.firstNotNullOfOrNull { name ->
            runCatching {
                val resource = plugin?.resources?.getIdentifier(name, "drawable", plugin.packageName) ?: 0
                if (resource == 0) null else plugin?.getDrawable(resource)?.mutate()
            }.getOrNull()
        }
        container.removeAllViews()
        container.gravity = Gravity.CENTER_HORIZONTAL
        title.apply { textSize = 16f; maxLines = 1; gravity = Gravity.CENTER }
        container.addView(title, LinearLayout.LayoutParams(-1, -2))
        val params = parameters(notification)
        val running = params?.optInt("timerType") == 1
        val timing = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        val wave = runCatching {
            val raw = plugin?.resources?.getIdentifier("voice_wave_big", "raw", plugin.packageName) ?: 0
            require(raw != 0)
            val json = plugin!!.resources.openRawResource(raw).bufferedReader().use { it.readText() }
            val type = context.classLoader.loadClass("com.airbnb.lottie.LottieAnimationView")
            val image = type.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
                .newInstance(context, null) as ImageView
            type.getMethod("setRepeatCount", Int::class.javaPrimitiveType).invoke(image, -1)
            type.getMethod("setAnimationFromJson", String::class.java).invoke(image, json)
            image
        }.getOrNull() ?: artwork("voice_wave_big")?.let { ImageView(context).apply { setImageDrawable(it) } }
        if (wave != null) timing.addView(WaveView(context, wave, running), LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        // rvBar is ONLY the OEM timer; it does not contain controls or waveform.
        val remote = notification.extras.getParcelable("miui.focus.rvBarNight", RemoteViews::class.java)
            ?: notification.extras.getParcelable("miui.focus.rvBar", RemoteViews::class.java)
        val timer = remote?.let { runCatching { it.apply(context, timing) }.getOrNull() }
        if (timer != null) {
            fun style(view: View) {
                if (view is TextView) { view.textSize = 36f; view.setTextColor(Color.WHITE); view.gravity = Gravity.CENTER }
                if (view is ViewGroup) for (index in 0 until view.childCount) style(view.getChildAt(index))
            }
            style(timer)
            timing.addView(timer, LinearLayout.LayoutParams(-2, -2))
        }
        if (timing.childCount > 0) container.addView(timing, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(12) })
        status.apply { textSize = 14f; maxLines = 1; gravity = Gravity.CENTER }
        container.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val controls = LinearLayout(context).apply { tag = CONTROLS; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.forEachIndexed { index, action ->
            val name = action.extras.getString("icon_name").orEmpty().removePrefix("customization_").removePrefix("action_")
            val icon = artwork("${name}_v3", "${name}_v2", name)
                ?: action.getIcon()?.loadDrawable(context)
            val button = ImageButton(context).apply {
                contentDescription = action.title
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, 0, 0, 0)
                background = null
                isFocusable = true
                setOnClickListener { execute(index) }
            }
            controls.addView(button, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(12); marginEnd = dp(12) })
        }
        container.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(8) })
        return true
    }

    private class WaveView(context: Context, private val image: ImageView, private val running: Boolean) : FrameLayout(context) {
        private var attempts = 0
        private var playing = false
        private val ready = Runnable { update() }
        init { addView(image, LayoutParams(-1, -1)) }
        private fun update() {
            removeCallbacks(ready)
            val animate = running && isAttachedToWindow && isShown && windowVisibility == VISIBLE && android.animation.ValueAnimator.areAnimatorsEnabled()
            val animation = image.drawable as? Animatable
            if (animate && animation == null && attempts++ < 40) postDelayed(ready, 50L)
            else if (animate && !playing) { animation?.start(); playing = animation != null }
            else if (!animate) { animation?.stop(); playing = false }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); attempts = 0; update() }
        override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); update() }
        override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); update() }
        override fun onDetachedFromWindow() { removeCallbacks(ready); (image.drawable as? Animatable)?.stop(); playing = false; super.onDetachedFromWindow() }
    }
}
