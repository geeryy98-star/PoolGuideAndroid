package com.example.poolguide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.View

class TrajectoryOverlayView(context: Context) : View(context) {
    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val objectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var lines = floatArrayOf()
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lines = intent?.getFloatArrayExtra("lines") ?: floatArrayOf()
            sourceWidth = intent?.getIntExtra("screen_width", 0) ?: 0
            sourceHeight = intent?.getIntExtra("screen_height", 0) ?: 0
            invalidate()
        }
    }

    init {
        val filter = IntentFilter("com.example.poolguide.GUIDE_LINES")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceWidth <= 0 || sourceHeight <= 0 || lines.size < 4) return
        val sx = width.toFloat() / sourceWidth
        val sy = height.toFloat() / sourceHeight
        var index = 0
        var segment = 0
        while (index + 3 < lines.size) {
            val paint = if (segment == 0) cuePaint else objectPaint
            canvas.drawLine(
                lines[index] * sx,
                lines[index + 1] * sy,
                lines[index + 2] * sx,
                lines[index + 3] * sy,
                paint
            )
            index += 4
            segment++
        }
    }

    override fun onDetachedFromWindow() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onDetachedFromWindow()
    }
}
