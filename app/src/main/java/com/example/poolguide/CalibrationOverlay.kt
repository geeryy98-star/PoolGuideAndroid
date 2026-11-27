package com.example.poolguide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Full-screen 10-tap calibration: table corners, then six pocket centers. */
class CalibrationOverlay(
    context: Context,
    private val onComplete: (List<PoolPoint>) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {
    private val labels = listOf(
        "1/10 · Esquina superior izquierda de la mesa",
        "2/10 · Esquina superior derecha de la mesa",
        "3/10 · Esquina inferior derecha de la mesa",
        "4/10 · Esquina inferior izquierda de la mesa",
        "5/10 · Tronera superior izquierda",
        "6/10 · Tronera superior central",
        "7/10 · Tronera superior derecha",
        "8/10 · Tronera inferior derecha",
        "9/10 · Tronera inferior central",
        "10/10 · Tronera inferior izquierda"
    )
    private val taps = mutableListOf<PoolPoint>()
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        setShadowLayer(5f, 1f, 1f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        if (taps.size >= 2) {
            val corners = taps.take(minOf(4, taps.size))
            if (corners.size >= 2) {
                val xs = corners.map { it.x * width }
                val ys = corners.map { it.y * height }
                canvas.drawRect(xs.min(), ys.min(), xs.max(), ys.max(), outline)
            }
        }
        taps.forEachIndexed { index, point ->
            val x = point.x * width
            val y = point.y * height
            canvas.drawCircle(x, y, 13f, marker)
            canvas.drawText((index + 1).toString(), x + 16f, y - 14f, text)
        }
        val label = if (taps.size < labels.size) labels[taps.size] else "Calibración completada"
        canvas.drawRoundRect(24f, 24f, width - 24f, 112f, 14f, 14f, shade)
        canvas.drawText(label, 44f, 78f, text)
        canvas.drawText("Toca CANCELAR en la esquina inferior izquierda para salir", 44f, height - 38f, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        if (event.y > height - 110f && event.x < width * 0.45f) {
            onCancel()
            return true
        }
        if (taps.size < 10 && width > 0 && height > 0) {
            taps += PoolPoint(
                (event.x / width).coerceIn(0f, 1f),
                (event.y / height).coerceIn(0f, 1f)
            )
            invalidate()
            if (taps.size == 10) post { onComplete(taps.toList()) }
        }
        return true
    }
}
