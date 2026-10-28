/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Full-screen liquid drawing adapted from MiRearScreenSwitcher/LightningShapeView.java.
 * Original author: AntiOblivionis (https://github.com/AntiOblivionis/MiRearScreenSwitcher)
 * Upstream revision: 7e0ae1a35aea58c1f94019cf3cb54681adb54b9d
 * REAREye adaptation: visible-window lifecycle, frame limit, safe text bounds,
 * display-relative gravity, and exact empty/full endpoints. See THIRD_PARTY_NOTICES.md.
 */
package hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal class RearChargingLiquidView(context: Context) : View(context), SensorEventListener {
    private val liquid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff34c759.toInt() }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x50ffffff }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(5f, 0f, 2f, 0x80000000.toInt())
    }
    private val path = Path()
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gravity = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var tilt = 0f
    private var fill = 0f
    private var percentage = 0
    private var label = ""
    private var animator: ValueAnimator? = null
    private var running = false
    private var startedAt = 0L
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 33L)
        }
    }

    fun updateBattery(percent: Int, statusLabel: String, animateEntry: Boolean = false) {
        if (!animateEntry && percentage == percent && label == statusLabel) return
        percentage = percent.coerceIn(0, 100)
        label = statusLabel
        animator?.cancel()
        animator = ValueAnimator.ofFloat(if (animateEntry) 0f else fill, percentage / 100f).apply {
            duration = if (animateEntry) 1800L else 500L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { fill = it.animatedValue as Float; invalidate() }
            if (isAttachedToWindow && isShown) start() else fill = percentage / 100f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(Color.BLACK)
        val phase = (SystemClock.uptimeMillis() - startedAt) / 1000f * PI.toFloat() * 1.5f
        if (fill >= 1f) {
            canvas.drawRect(0f, 0f, w, h, liquid)
        } else if (fill > 0f) {
            val waterline = h * (1f - fill)
            val amplitude = min(h * 0.025f, 12f * resources.displayMetrics.density) *
                min(1f, min(fill, 1f - fill) * 20f)
            path.reset()
            path.moveTo(0f, h)
            for (i in 0..120) {
                val x = w * i / 120f
                val wave = sin(x / w * PI.toFloat() * 2f + phase) * amplitude +
                    sin(x / w * PI.toFloat() * 3f - phase * 0.6f) * amplitude * 0.35f
                path.lineTo(x, waterline + wave + tilt * (0.5f - x / w) * amplitude)
            }
            path.lineTo(w, h)
            path.close()
            canvas.drawPath(path, liquid)
            canvas.save()
            canvas.clipPath(path)
            repeat(6) { index ->
                val progress = ((phase * 0.025f + index / 6f) % 1f)
                val x = w * ((index * 0.173f + 0.12f) % 1f) + sin(phase + index) * amplitude
                canvas.drawCircle(x, h - progress * h * fill, min(w, h) * (0.005f + index * 0.001f), bubble)
            }
            canvas.restore()
        }
        val insets = rootWindowInsets?.displayCutout
        val left = (insets?.safeInsetLeft ?: 0).toFloat()
        val right = (insets?.safeInsetRight ?: 0).toFloat()
        val top = (insets?.safeInsetTop ?: 0).toFloat()
        val bottom = (insets?.safeInsetBottom ?: 0).toFloat()
        val safeW = (w - left - right).coerceAtLeast(1f)
        val safeH = (h - top - bottom).coerceAtLeast(1f)
        val centerX = left + safeW / 2f
        val centerY = top + safeH / 2f
        text.textSize = min(safeW * 0.19f, safeH * 0.25f)
        canvas.drawText("$percentage%", centerX, centerY - (text.ascent() + text.descent()) / 2f, text)
        text.textSize = min(safeW * 0.055f, safeH * 0.075f)
        canvas.drawText(label, centerX, centerY + safeH * 0.22f, text)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // View's constructor can call this before Kotlin fields are initialized.
        if (isAttachedToWindow) updateRunning()
    }

    private fun updateRunning() {
        val visible = isAttachedToWindow && windowVisibility == VISIBLE && isShown
        if (visible == running) return
        running = visible
        removeCallbacks(frame)
        sensors?.unregisterListener(this)
        if (running) {
            startedAt = SystemClock.uptimeMillis()
            gravity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            post(frame)
        } else animator?.cancel()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frame)
        animator?.cancel()
        sensors?.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val horizontal = when (display?.rotation) {
            Surface.ROTATION_90 -> -event.values[1]
            Surface.ROTATION_180 -> -event.values[0]
            Surface.ROTATION_270 -> event.values[1]
            else -> event.values[0]
        }
        if (horizontal.isFinite()) tilt = (tilt * 0.95f + horizontal * 0.05f).coerceIn(-2f, 2f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
