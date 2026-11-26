
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

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var frames = 0
    private var lastReport = System.currentTimeMillis()
    private var firstFrame = false

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
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_DATA)
            }

            if (data == null) {
                sendStatus("ERROR: falta permiso de captura")
                stopSelf()
                return START_NOT_STICKY
            }

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            mediaProjection = manager.getMediaProjection(resultCode, data)

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

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage()

                    if (image != null) {
                        try {
                            frames++

                            if (!firstFrame) {
                                firstFrame = true
                                sendStatus("RECIBIENDO IMAGEN")
                            }

                            val now = System.currentTimeMillis()

                            if (now - lastReport >= 3000) {

                                val seconds =
                                    (now - lastReport) / 1000.0

                                val fps = frames / seconds
                                val brightness = measureBrightness(image)

                                val state = if (brightness < 15) {
                                    "IMAGEN OSCURA"
                                } else {
                                    "IMAGEN VISIBLE"
                                }

                                val message =
                                    "FPS: ${"%.1f".format(fps)} | $state | Luz: $brightness"

                                sendStatus(message)

                                frames = 0
                                lastReport = now
                            }

                        } catch (_: Exception) {
                        } finally {
                            image.close()
                        }
                    }
                },
                Handler(mainLooper)
            )

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        sendStatus("CAPTURA DETENIDA")
                        stopSelf()
                    }
                },
                Handler(mainLooper)
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
                sendStatus("ERROR: pantalla virtual")
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

        sendBroadcast(
            Intent(ACTION_CAPTURE_STATUS)
                .setPackage(packageName)
                .putExtra("status", "CAPTURA DETENIDA")
        )

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
