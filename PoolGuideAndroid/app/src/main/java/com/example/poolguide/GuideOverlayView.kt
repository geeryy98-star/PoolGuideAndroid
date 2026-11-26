
package com.example.poolguide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import android.view.WindowManager

class GuideOverlayView(
    context: Context,
    private val close: () -> Unit
) : View(context) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD172033.toInt()
    }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private var guides = false
    private var captureStatus = "Captura: esperando datos"

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private val panel = RectF(0f, 0f, 300f, 140f)

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            captureStatus = intent?.getStringExtra("status")
                ?: "Captura: sin datos"
            invalidate()
        }
    }

    init {
        val filter = IntentFilter("com.example.poolguide.CAPTURE_STATUS")

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                statusReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(statusReceiver, filter)
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        setMeasuredDimension(540, 160)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRoundRect(panel, 18f, 18f, bg)

        canvas.drawText("POOL GUIDE", 18f, 28f, text)
        canvas.drawText(
            if (guides) "Guías demo: ON" else "Toca para guías demo",
            18f, 53f, text
        )
        canvas.drawText(
            "Mantén y arrastra para mover",
            18f, 78f, text
        )
        canvas.drawText(captureStatus, 18f, 105f, text)
        canvas.drawText("Cerrar", 18f, 130f, text)

        if (guides) {
            canvas.drawLine(318f, 18f, 520f, 18f, line)
            canvas.drawLine(318f, 34f, 450f, 100f, red)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY

                val lp = layoutParams as? WindowManager.LayoutParams
                startX = lp?.x ?: 0
                startY = lp?.y ?: 0

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.eventTime - event.downTime > 250) {
                    val lp = layoutParams as? WindowManager.LayoutParams

                    if (lp != null) {
                        lp.x = startX + (event.rawX - downX).toInt()
                        lp.y = startY + (event.rawY - downY).toInt()

                        (context.getSystemService(Context.WINDOW_SERVICE)
                            as WindowManager).updateViewLayout(this, lp)
                    }
                }

                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY

                if (kotlin.math.abs(dx) < 12 &&
                    kotlin.math.abs(dy) < 12
                ) {
                    if (event.y > 112f) {
                        close()
                    } else {
                        guides = !guides
                        invalidate()

                        Toast.makeText(
                            context,
                            "Guías de demostración",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                return true
            }
        }

        return true
    }

    override fun onDetachedFromWindow() {
        try {
            context.unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }

        super.onDetachedFromWindow()
    }
}
