
package com.example.poolguide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var firstFrameReceived = false
    private var lastAnalysisTime = 0L
    private var lastMessageTime = 0L
    private var frameCount = 0

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "capture_data"
        private const val CHANNEL_ID = "pool_guide_capture"
        private const val NOTIFICATION_ID = 2
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        try {
            createNotification("Preparando captura")

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_DATA)
            }

            if (data == null) {
                showError("No se recibieron los datos del permiso")
                stopSelf()
                return START_NOT_STICKY
            }

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            mediaProjection = manager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                showError("Android no ha iniciado la captura")
                stopSelf()
                return START_NOT_STICKY
            }

            val metrics = resources.displayMetrics

            imageReader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                2
            )

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage()

                    if (image != null) {
                        try {
                            frameCount++

                            if (!firstFrameReceived) {
                                firstFrameReceived = true
                                showStatus("RECIBIENDO IMAGEN")
                            }

                            val now = System.currentTimeMillis()

                            if (now - lastAnalysisTime >= 1000) {
                                lastAnalysisTime = now

                                val position = findWhiteBallCandidate(image)

                                if (position != null &&
                                    now - lastMessageTime >= 3000
                                ) {
                                    lastMessageTime = now

                                    showStatus(
                                        "POSIBLE BOLA: X ${position.first}, Y ${position.second}"
                                    )
                                }
                            }

                        } catch (_: Exception) {
                        } finally {
                            image.close()
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                Handler(Looper.getMainLooper())
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PoolGuideCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            if (virtualDisplay == null) {
                showError("No se pudo crear la pantalla virtual")
                stopSelf()
            } else {
                createNotification("Esperando primera imagen")
            }

        } catch (e: Exception) {
            showError("Error: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun findWhiteBallCandidate(image: Image): Pair<Int, Int>? {

        val plane = image.planes[0]
        val buffer = plane.buffer

        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val step = 3
        val gridWidth = (width + step - 1) / step
        val gridHeight = (height + step - 1) / step

        val mask = BooleanArray(gridWidth * gridHeight)

        for (gy in 0 until gridHeight) {
            val y = gy * step

            for (gx in 0 until gridWidth) {
                val x = gx * step
                val index = y * rowStride + x * pixelStride

                if (index + 2 >= buffer.limit()) continue

                val red = buffer.get(index).toInt() and 0xFF
                val green = buffer.get(index + 1).toInt() and 0xFF
                val blue = buffer.get(index + 2).toInt() and 0xFF

                val isWhite =
                    red > 200 &&
                    green > 200 &&
                    blue > 200 &&
                    kotlin.math.abs(red - green) < 40 &&
                    kotlin.math.abs(green - blue) < 40

                mask[gy * gridWidth + gx] = isWhite
            }
        }

        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var head = 0
            var tail = 0

            queue[tail++] = start
            visited[start] = true

            var count = 0
            var minX = gridWidth
            var maxX = 0
            var minY = gridHeight
            var maxY = 0
            var sumX = 0
            var sumY = 0

            while (head < tail) {
                val current = queue[head++]
                val x = current % gridWidth
                val y = current / gridWidth

                count++
                sumX += x
                sumY += y

                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue

                        val nx = x + dx
                        val ny = y + dy

                        if (nx !in 0 until gridWidth ||
                            ny !in 0 until gridHeight
                        ) continue

                        val next = ny * gridWidth + nx

                        if (mask[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1

            if (count < 8 || count > 500) continue
            if (boxWidth < 3 || boxHeight < 3) continue

            val ratio = boxWidth.toFloat() / boxHeight.toFloat()
            val fill = count.toFloat() / (boxWidth * boxHeight)

            if (ratio in 0.65f..1.45f && fill > 0.45f) {
                return Pair(
                    (sumX / count) * step,
                    (sumY / count) * step
                )
            }
        }

        return null
    }

    private fun showStatus(message: String) {
        createNotification(message)

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showError(message: String) {
        showStatus(message)
    }

    private fun createNotification(message: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Captura Pool Guide",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pool Guide")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        mediaProjection?.stop()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


                    

            
