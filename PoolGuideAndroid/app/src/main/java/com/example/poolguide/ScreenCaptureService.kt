
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
            createNotification("Preparando captura de pantalla")

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_DATA)
            }

            if (data == null) {
                showError("No se han recibido los datos del permiso")
                stopSelf()
                return START_NOT_STICKY
            }

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            mediaProjection = manager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                showError("Android no ha iniciado MediaProjection")
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
                        image.close()

                        if (!firstFrameReceived) {
                            firstFrameReceived = true
                            createNotification(
                                "Captura funcionando: fotograma recibido"
                            )
                            Toast.makeText(
                                this,
                                "POOL GUIDE: captura funcionando",
                                Toast.LENGTH_LONG
                            ).show()
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
                showError("No se ha podido crear la pantalla virtual")
                stopSelf()
            } else {
                createNotification("Captura iniciada; esperando imagen")
            }

        } catch (e: Exception) {
            showError("Error de captura: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun showError(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()

        try {
            createNotification(message)
        } catch (_: Exception) {
        }
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
