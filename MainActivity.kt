package com.example.poolguide

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.view.ViewGroup

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "Pool Guide · prototipo 0.1\n\nPrimera prueba: menú flotante y capa de dibujo.\nEl reconocimiento automático de bolas todavía no está implementado."
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val permission = Button(this).apply {
            text = "Conceder permiso de superposición"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }
        val start = Button(this).apply {
            text = "Iniciar menú flotante"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(Intent(this@MainActivity, FloatingOverlayService::class.java))
                } else {
                    permission.performClick()
                }
            }
        }
        layout.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(permission)
        layout.addView(start)
        setContentView(layout)
    }
}
