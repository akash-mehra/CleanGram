package com.example

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Soft colour orbs drifting on slow elliptical orbits, drawn natively.
 *
 * Each orb's gradient is built once at unit radius and placed per frame through its local
 * matrix, so a running animation allocates nothing. All angular multipliers are whole numbers,
 * which makes the loop seamless when the animator wraps from 2π back to 0.
 */
class OrbLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Orb(
        val color: Int,
        /** Radius as a fraction of the view's shorter side. */
        val radius: Float,
        /** Orbit half-extents as fractions of the shorter side. */
        val orbitX: Float,
        val orbitY: Float,
        /** Whole turns per animation cycle; negative runs the other way. */
        val turns: Int,
        val phase: Float,
    ) {
        var shader: RadialGradient? = null
    }

    private val orbs = listOf(
        Orb(0xFFFF4D8D.toInt(), radius = 0.46f, orbitX = 0.16f, orbitY = 0.12f, turns = 1, phase = 0f),
        Orb(0xFF7C4DFF.toInt(), radius = 0.42f, orbitX = 0.14f, orbitY = 0.18f, turns = -1, phase = 2.1f),
        Orb(0xFFFFB547.toInt(), radius = 0.34f, orbitX = 0.20f, orbitY = 0.10f, turns = 2, phase = 4.2f),
    )

    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    // On black the orbs can glow; on white they must stay soft or they read as stains.
    private val coreAlpha = if (night) 0.78f else 0.5f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placement = Matrix()
    private var angle = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 9_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        for (orb in orbs) {
            orb.shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    withAlpha(orb.color, coreAlpha),
                    withAlpha(orb.color, coreAlpha * 0.35f),
                    // Fade to the same hue, not to transparent black, or the edge greys out.
                    withAlpha(orb.color, 0f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        animator.cancel()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        for (orb in orbs) {
            val shader = orb.shader ?: continue
            val a = angle * orb.turns + orb.phase
            val x = cx + cos(a) * orb.orbitX * side
            val y = cy + sin(a) * orb.orbitY * side
            // A slow breath on top of the orbit.
            val r = orb.radius * side * (1f + 0.08f * sin(2 * angle + orb.phase))

            placement.setScale(r, r)
            placement.postTranslate(x, y)
            shader.setLocalMatrix(placement)
            paint.shader = shader
            canvas.drawCircle(x, y, r, paint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}
