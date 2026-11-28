package com.example.poolguide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager

class FloatingOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: GuideOverlayView? = null
    private var trajectoryOverlay: TrajectoryOverlayView? = null
    private var calibrationOverlay: CalibrationOverlay? = null
    private val calibrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == "com.example.poolguide.START_CALIBRATION") startCalibration()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channelId = "pool_guide_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Pool Guide", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setContentTitle("Pool Guide activo")
                .setContentText("Controles de guía flotante")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Pool Guide activo")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val calibrationFilter = IntentFilter("com.example.poolguide.START_CALIBRATION")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(calibrationReceiver, calibrationFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(calibrationReceiver, calibrationFilter)
        }
        trajectoryOverlay = TrajectoryOverlayView(this)
        val trajectoryParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(trajectoryOverlay, trajectoryParams)

        overlay = GuideOverlayView(this) { stopSelf() }
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 180 }
        windowManager.addView(overlay, params)
    }

    private fun startCalibration() {
        if (calibrationOverlay != null) return
        val view = CalibrationOverlay(
            this,
            onComplete = { taps ->
                try {
                    TableCalibration.save(this, taps)
                    sendBroadcast(Intent(ScreenCaptureService.ACTION_CAPTURE_STATUS)
                        .setPackage(packageName)
                        .putExtra("status", "Calibración guardada: mesa y 6 troneras"))
                } catch (error: Exception) {
                    sendBroadcast(Intent(ScreenCaptureService.ACTION_CAPTURE_STATUS)
                        .setPackage(packageName)
                        .putExtra("status", "Error de calibración: revisa las 4 esquinas"))
                }
                finishCalibration()
            },
            onCancel = { finishCalibration() }
        )
        calibrationOverlay = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(view, params)
    }

    private fun finishCalibration() {
        calibrationOverlay?.let { runCatching { windowManager.removeView(it) } }
        calibrationOverlay = null
    }

    override fun onDestroy() {
        try { unregisterReceiver(calibrationReceiver) } catch (_: Exception) {}
        finishCalibration()
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        trajectoryOverlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlay = null
        trajectoryOverlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}