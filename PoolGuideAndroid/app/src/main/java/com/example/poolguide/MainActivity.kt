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
            text = "Pool Guide · versión 0.2\n\nControl del menú flotante."
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val permission = Button(this).apply {
            text = "Conceder permisos"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val start = Button(this).apply {
            text = "ACTIVAR GUÍA"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(
                        Intent(this@MainActivity, FloatingOverlayService::class.java)
                    )
                } else {
                    permission.performClick()
                }
            }
        }

        val stop = Button(this).apply {
            text = "DESACTIVAR GUÍA"
            setOnClickListener {
                stopService(
                    Intent(this@MainActivity, FloatingOverlayService::class.java)
                )
            }
        }

        layout.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        layout.addView(permission)
        layout.addView(start)
        layout.addView(stop)

        setContentView(layout)
    }
}
