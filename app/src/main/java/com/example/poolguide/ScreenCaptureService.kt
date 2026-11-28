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
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * MediaProjection capture that follows physical display rotations/resizes.
 *
 * The ImageReader surface must have the current display dimensions. Keeping
 * the dimensions from the moment the permission dialog was accepted can leave
 * the virtual display in portrait while the game is landscape.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var densityDpi = 0
    private var frames = 0
    private var lastReport = 0L
    private var firstFrame = false
    private var foregroundStarted = false
    @Volatile private var stopping = false
    @Volatile private var resizing = false

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "capture_data"
        private const val CHANNEL_ID = "pool_guide_capture"
        private const val NOTIFICATION_ID = 2
        const val ACTION_CAPTURE_STATUS = "com.example.poolguide.CAPTURE_STATUS"
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val defaultId = currentDisplay()?.displayId ?: return
            if (displayId == defaultId) {
                analysisHandler?.post { resizeCaptureToCurrentDisplay() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                sendStatus("ERROR: falta permiso")
                stopSelf()
                return START_NOT_STICKY
            }

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                sendStatus("ERROR: captura no iniciada")
                stopSelf()
                return START_NOT_STICKY
            }

            analysisThread = HandlerThread("PoolGuideFrameAnalysis").apply { start() }
            analysisHandler = Handler(analysisThread!!.looper)

            val display = currentDisplay()
            if (display == null) {
                sendStatus("ERROR: pantalla no disponible")
                stopSelf()
                return START_NOT_STICKY
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            densityDpi = metrics.densityDpi
            imageReader = createReader(metrics.widthPixels, metrics.heightPixels)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping) {
                        stopping = true
                        sendStatus("CAPTURA DETENIDA")
                        stopSelf()
                    }
                }
            }, Handler(mainLooper))

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PoolGuideCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            if (virtualDisplay == null) {
                sendStatus("ERROR: PANTALLA VIRTUAL")
                stopSelf()
                return START_NOT_STICKY
            }

            displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager?.registerDisplayListener(displayListener, Handler(mainLooper))
            sendStatus("Captura activa: ${metrics.widthPixels}x${metrics.heightPixels}")
        } catch (error: Exception) {
            sendStatus("ERROR CAPTURA: ${error.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun currentDisplay(): Display? {
        @Suppress("DEPRECATION")
        return (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }

    private fun createReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: Exception) { null }
            if (image != null) processImage(image)
        }, analysisHandler)
        return reader
    }

    private fun processImage(image: Image) {
        try {
            if (stopping || resizing) return
            frames++
            if (!firstFrame) {
                firstFrame = true
                sendStatus("Captura funcionando: ${image.width}x${image.height}")
            }
            val now = System.currentTimeMillis()
            if (now - lastReport >= 1000L) {
                val analysis = LivePoolAnalyzer.analyze(
                    image,
                    TableCalibration.load(this@ScreenCaptureService)
                )
                sendStatus(analysis.message)
                sendGuideLines(
                    analysis.screenLines,
                    analysis.screenWidth,
                    analysis.screenHeight,
                    analysis.screenBallMarkers,
                    analysis.cueBallIndex
                )
                frames = 0
                lastReport = now
            }
        } catch (error: Exception) {
            sendStatus("ERROR ANALISIS: ${error.javaClass.simpleName}")
            sendGuideLines(floatArrayOf(), image.width, image.height, floatArrayOf(), -1)
        } finally {
            image.close()
        }
    }

    /**
     * Rebuild the ImageReader surface when Android changes the physical
     * display orientation. The operation runs on the same handler as frame
     * processing, so an old Image cannot race with surface replacement.
     */
    private fun resizeCaptureToCurrentDisplay() {
        if (stopping || resizing) return
        val display = currentDisplay() ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val oldReader = imageReader ?: return
        if (oldReader.width == metrics.widthPixels &&
            oldReader.height == metrics.heightPixels &&
            densityDpi == metrics.densityDpi) return

        resizing = true
        try {
            val newReader = createReader(metrics.widthPixels, metrics.heightPixels)
            virtualDisplay?.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            virtualDisplay?.setSurface(newReader.surface)
            imageReader = newReader
            densityDpi = metrics.densityDpi
            oldReader.setOnImageAvailableListener(null, null)
            oldReader.close()
            frames = 0
            lastReport = 0L
            sendGuideLines(floatArrayOf(), metrics.widthPixels, metrics.heightPixels, floatArrayOf(), -1)
            sendStatus("Pantalla reajustada: ${metrics.widthPixels}x${metrics.heightPixels}")
        } catch (error: Exception) {
            sendStatus("ERROR AJUSTE PANTALLA: ${error.javaClass.simpleName}")
        } finally {
            resizing = false
        }
    }

    private fun sendGuideLines(
        lines: FloatArray,
        width: Int,
        height: Int,
        ballMarkers: FloatArray,
        cueIndex: Int
    ) {
        sendBroadcast(Intent("com.example.poolguide.GUIDE_LINES").apply {
            setPackage(packageName)
            putExtra("lines", lines)
            putExtra("screen_width", width)
            putExtra("screen_height", height)
            putExtra("ball_markers", ballMarkers)
            putExtra("cue_index", cueIndex)
        })
    }

    private fun sendStatus(message: String) {
        createNotification(message)
        sendBroadcast(Intent(ACTION_CAPTURE_STATUS).apply {
            setPackage(packageName)
            putExtra("status", message)
        })
    }

    private fun createNotification(message: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Captura Pool Guide", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pool Guide")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
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
        displayManager?.unregisterDisplayListener(displayListener)
        sendGuideLines(floatArrayOf(), 0, 0, floatArrayOf(), -1)
        virtualDisplay?.setSurface(null)
        virtualDisplay?.release()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        analysisThread?.quitSafely()
        analysisThread = null
        analysisHandler = null
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        sendBroadcast(Intent(ACTION_CAPTURE_STATUS).setPackage(packageName)
            .putExtra("status", "CAPTURA DETENIDA"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}