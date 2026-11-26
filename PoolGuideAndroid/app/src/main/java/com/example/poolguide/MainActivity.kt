
package com.example.poolguide

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager

    companion object {
        private const val REQUEST_CAPTURE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "POOL GUIDE · VERSIÓN 0.3\n\nControl de guía y captura de pantalla."
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val permission = Button(this).apply {
            text = "CONCEDER PERMISO DE VENTANA"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val startGuide = Button(this).apply {
            text = "ACTIVAR GUÍA"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(
                        Intent(
                            this@MainActivity,
                            FloatingOverlayService::class.java
                        )
                    )
                } else {
                    permission.performClick()
                }
            }
        }

        val stopGuide = Button(this).apply {
            text = "DESACTIVAR GUÍA"
            setOnClickListener {
                stopService(
                    Intent(
                        this@MainActivity,
                        FloatingOverlayService::class.java
                    )
                )
            }
        }

        val startCapture = Button(this).apply {
            text = "ACTIVAR CAPTURA DE PANTALLA"
            setOnClickListener {
                startActivityForResult(
                    projectionManager.createScreenCaptureIntent(),
                    REQUEST_CAPTURE
                )
            }
        }

        val stopCapture = Button(this).apply {
            text = "DETENER CAPTURA"
            setOnClickListener {
                stopService(
                    Intent(
                        this@MainActivity,
                        ScreenCaptureService::class.java
                    )
                )
            }
        }

        layout.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        layout.addView(permission)
        layout.addView(startGuide)
        layout.addView(stopGuide)
        layout.addView(startCapture)
        layout.addView(stopCapture)

        setContentView(layout)
    }

    @Deprecated("Usado para recibir la autorización de captura")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    putExtra(
                        ScreenCaptureService.EXTRA_RESULT_CODE,
                        resultCode
                    )
                    putExtra(
                        ScreenCaptureService.EXTRA_DATA,
                        data
                    )
                }

                startForegroundService(serviceIntent)

                Toast.makeText(
                    this,
                    "Autorización de captura concedida",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Captura cancelada",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
