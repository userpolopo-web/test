package com.ultrastream

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 77
    private lateinit var server: EditText
    private lateinit var room: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 40)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 18f }
        container.addView(label("UltraStream • compartilhamento de tela"))
        container.addView(label("Servidor WebSocket (wss:// para internet)"))
        server = EditText(this).apply { setSingleLine(); setText("ws://10.0.2.2:8080"); hint = "wss://seu-servidor" }
        container.addView(server)
        container.addView(label("Sala"))
        room = EditText(this).apply { setSingleLine(); setText("minha-sala") }
        container.addView(room)
        status = label("A transmissão só começa após sua autorização no Android.")
        container.addView(status)
        container.addView(Button(this).apply { text = "INICIAR TRANSMISSÃO"; setOnClickListener { requestScreen() } })
        container.addView(Button(this).apply { text = "PARAR"; setOnClickListener {
            stopService(Intent(this@MainActivity, StreamService::class.java))
            status.text = "Transmissão encerrada."
        } })
        setContentView(container)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("stream", "Transmissão de tela", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun requestScreen() {
        val url = server.text.toString().trim()
        val key = room.text.toString().trim()
        if (!(url.startsWith("ws://") || url.startsWith("wss://")) || key.isEmpty()) {
            status.text = "Informe um WebSocket e uma sala válidos."
            return
        }
        getPreferences(Context.MODE_PRIVATE).edit().putString("url", url).putString("room", key).apply()
        startActivityForResult(
            (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),
            captureRequest
        )
    }

    @Deprecated("Android capture permission callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val prefs = getPreferences(Context.MODE_PRIVATE)
            val intent = Intent(this, StreamService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("projectionData", data)
                putExtra("url", prefs.getString("url", ""))
                putExtra("room", prefs.getString("room", ""))
            }
            startForegroundService(intent)
            status.text = "Transmissão iniciada. Abra o visualizador no navegador."
        } else if (requestCode == captureRequest) status.text = "Permissão de captura não concedida."
    }
}
