
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
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import android.view.WindowManager
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var analysisThread: HandlerThread? = null

    private var frames = 0
    private var lastReport = System.currentTimeMillis()
    private var firstFrame = false
    private var foregroundStarted = false
    @Volatile private var stopping = false

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "capture_data"

        private const val CHANNEL_ID = "pool_guide_capture"
        private const val NOTIFICATION_ID = 2

        const val ACTION_CAPTURE_STATUS =
            "com.example.poolguide.CAPTURE_STATUS"
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        try {
            createNotification("Preparando captura")

            val resultCode =
                intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_DATA)
            }

            if (data == null) {
                sendStatus("ERROR: falta permiso")
                stopSelf()
                return START_NOT_STICKY
            }

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            mediaProjection =
                manager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                sendStatus("ERROR: captura no iniciada")
                stopSelf()
                return START_NOT_STICKY
            }

            val metrics = resources.displayMetrics

            imageReader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                3
            )

            analysisThread = HandlerThread("PoolGuideFrameAnalysis").apply { start() }

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage()

                    if (image != null) {
                        try {
                            frames++

                            if (!firstFrame) {
                                firstFrame = true
                                sendStatus("Captura funcionando: primer fotograma recibido")
                            }

                            val now = System.currentTimeMillis()

                            if (now - lastReport >= 3000) {

                                val seconds =
                                    (now - lastReport) / 1000.0

                                val fps = frames / seconds
                                val analysis = LivePoolAnalyzer.analyze(image)
                                sendStatus(analysis.message)
                                sendGuideLines(
                                    analysis.screenLines,
                                    analysis.screenWidth,
                                    analysis.screenHeight
                                )

                                frames = 0
                                lastReport = now
                            }

                        } catch (e: Exception) {
                            sendStatus("ERROR: ANALISIS")
                        } finally {
                            image.close()
                        }
                    }
                },
                Handler(analysisThread?.looper ?: mainLooper)
            )

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (!stopping) {
                            stopping = true
                            sendStatus("CAPTURA DETENIDA")
                            stopSelf()
                        }
                    }
                },
                Handler(mainLooper)
            )

            virtualDisplay =
                mediaProjection?.createVirtualDisplay(
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
                sendStatus("ERROR: PANTALLA VIRTUAL")
                stopSelf()
            } else {
                sendStatus("ESPERANDO IMAGEN")
            }

        } catch (e: Exception) {
            sendStatus(
                "ERROR: ${e.message ?: e.javaClass.simpleName}"
            )
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun getScreenRotation(): Int {
        @Suppress("DEPRECATION")
        val display = (
            getSystemService(Context.WINDOW_SERVICE)
                as WindowManager
        ).defaultDisplay

        return when (display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> -1
        }
    }

    private fun measureBrightness(image: Image): Int {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        var total = 0
        var samples = 0

        for (gy in 1..10) {
            for (gx in 1..10) {
                val x = width * gx / 11
                val y = height * gy / 11
                val index = y * rowStride + x * pixelStride

                if (index + 2 >= buffer.limit()) continue

                val red = buffer.get(index).toInt() and 0xFF
                val green = buffer.get(index + 1).toInt() and 0xFF
                val blue = buffer.get(index + 2).toInt() and 0xFF

                total += (red + green + blue) / 3
                samples++
            }
        }

        return if (samples > 0) total / samples else 0
    }

    private fun detectWhiteGroups(image: Image): Int {

        val plane = image.planes[0]
        val buffer = plane.buffer

        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val gridWidth = 180
        val gridHeight = 180

        val stepX = max(1, width / gridWidth)
        val stepY = max(1, height / gridHeight)

        val actualWidth = (width + stepX - 1) / stepX
        val actualHeight = (height + stepY - 1) / stepY

        val pixels = BooleanArray(actualWidth * actualHeight)
        val visited = BooleanArray(actualWidth * actualHeight)

        for (gy in 0 until actualHeight) {
            for (gx in 0 until actualWidth) {

                val x = min(width - 1, gx * stepX)
                val y = min(height - 1, gy * stepY)

                val index = y * rowStride + x * pixelStride

                if (index + 2 >= buffer.limit()) continue

                val red = buffer.get(index).toInt() and 0xFF
                val green = buffer.get(index + 1).toInt() and 0xFF
                val blue = buffer.get(index + 2).toInt() and 0xFF

                val brightest = max(red, max(green, blue))
                val darkest = min(red, min(green, blue))

                val isWhite =
                    red > 205 &&
                    green > 205 &&
                    blue > 205 &&
                    brightest - darkest < 55

                pixels[gy * actualWidth + gx] = isWhite
            }
        }

        val queue = IntArray(pixels.size)
        var groups = 0

        for (start in pixels.indices) {

            if (!pixels[start] || visited[start]) continue

            var head = 0
            var tail = 0

            queue[tail++] = start
            visited[start] = true

            var count = 0
            var minX = actualWidth
            var maxX = 0
            var minY = actualHeight
            var maxY = 0

            while (head < tail) {

                val current = queue[head++]
                val x = current % actualWidth
                val y = current / actualWidth

                count++

                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)

                for (dy in -1..1) {
                    for (dx in -1..1) {

                        if (dx == 0 && dy == 0) continue

                        val nx = x + dx
                        val ny = y + dy

                        if (nx !in 0 until actualWidth) continue
                        if (ny !in 0 until actualHeight) continue

                        val next = ny * actualWidth + nx

                        if (pixels[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1

            val ratio =
                boxWidth.toFloat() / boxHeight.toFloat()

            val compactness =
                count.toFloat() / (boxWidth * boxHeight)

            if (
                count in 3..80 &&
                ratio in 0.65f..1.45f &&
                compactness > 0.25f
            ) {
                groups++
            }
        }

        return groups
    }

    private fun sendGuideLines(lines: FloatArray, width: Int, height: Int) {
        val intent = Intent("com.example.poolguide.GUIDE_LINES").apply {
            setPackage(packageName)
            putExtra("lines", lines)
            putExtra("screen_width", width)
            putExtra("screen_height", height)
        }
        sendBroadcast(intent)
    }

    private fun sendStatus(message: String) {
        createNotification(message)

        val broadcast = Intent(ACTION_CAPTURE_STATUS).apply {
            setPackage(packageName)
            putExtra("status", message)
        }

        sendBroadcast(broadcast)
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

        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopping = true
        virtualDisplay?.release()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        analysisThread?.quitSafely()
        analysisThread = null
        mediaProjection?.stop()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        sendBroadcast(
            Intent(ACTION_CAPTURE_STATUS)
                .setPackage(packageName)
                .putExtra("status", "CAPTURA DETENIDA")
        )

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
