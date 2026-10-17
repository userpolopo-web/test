package com.ultrastream

import android.app.*
import android.content.Context
import android.content.ActivityNotFoundException
import android.provider.Settings
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val devices = linkedMapOf<String, Pair<String,Int>>()
    private lateinit var nsd: NsdManager
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var listener: NsdManager.DiscoveryListener? = null
    private var address = ""
    private var pin = ""
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(35,45,35,35) }
        root.addView(TextView(this).apply { text = "UltraStream  📺"; textSize = 29f })
        root.addView(TextView(this).apply {
            text = "Para Smart TVs Samsung, LG, Philco ou outras que aceitem espelhamento nativo:"
            textSize = 17f
        })
        root.addView(Button(this).apply {
            text = "📺 Espelhar na TV (sem instalar app)"
            setOnClickListener { openSystemCasting() }
        })
        root.addView(TextView(this).apply {
            text = "O Android mostrará as TVs compatíveis. Dependendo do modelo, confirme na televisão. Nem toda TV ou celular oferece essa função."
            textSize = 14f
        })
        root.addView(TextView(this).apply {
            text = "Ou conecte a uma Android TV / Google TV com UltraStream TV instalado:"
            textSize = 17f
        })
        status = TextView(this).apply { text = "Procurando TVs…"; textSize = 16f }
        root.addView(status)
        root.addView(Button(this).apply { text = "🔄 Procurar novamente"; setOnClickListener { scan() } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(Button(this).apply { text = "■ Parar transmissão"; setOnClickListener { stopService(Intent(this@MainActivity, StreamService::class.java)); status.text = "Transmissão interrompida." } })
        setContentView(ScrollView(this).apply { addView(root) })
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("stream", "Compartilhamento de tela", NotificationManager.IMPORTANCE_LOW)
        )
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        scan()
    }

    private fun openSystemCasting() {
        try {
            startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("Espelhamento nativo")
                .setMessage(
                    "Abra os ajustes rápidos do celular e procure por Transmitir, Smart View ou Espelhar tela. " +
                    "A TV precisa oferecer espelhamento compatível. Em aparelhos sem essa função, esta opção não funciona."
                )
                .setPositiveButton("Entendi", null)
                .show()
        } catch (_: SecurityException) {
            status.text = "O sistema do celular não permite abrir o espelhamento."
        }
    }

    private fun scan() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        devices.clear()
        list.removeAllViews()
        status.text = "Procurando TVs com UltraStream…"
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String?, code: Int) { runOnUiThread { status.text = "Falha ao buscar TVs ($code)." } }
            override fun onStopDiscoveryFailed(type: String?, code: Int) {}
            override fun onDiscoveryStarted(type: String?) {}
            override fun onDiscoveryStopped(type: String?) {}
            override fun onServiceLost(info: NsdServiceInfo) {
                runOnUiThread { devices.remove(info.serviceName); render() }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') != "_ultrastream._tcp") return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val ip = serviceInfo.host?.hostAddress ?: return
                        runOnUiThread { devices[serviceInfo.serviceName] = ip to serviceInfo.port; render() }
                    }
                })
            }
        }
        listener = discovery
        nsd.discoverServices("_ultrastream._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    private fun render() {
        list.removeAllViews()
        status.text = if(devices.isEmpty()) "Nenhuma TV encontrada. Abra UltraStream na Android TV." else "${devices.size} TV(s) encontrada(s)"
        for ((name, endpoint) in devices) {
            list.addView(Button(this).apply { text = "📺 $name    •    Transmitir"; setOnClickListener { requestPairing(endpoint.first,endpoint.second) } })
        }
    }

    private fun requestPairing(ip: String, port: Int) {
        val host = if (ip.contains(":") && !ip.startsWith("[")) "[$ip]" else ip
        val url = "ws://$host:$port"
        status.text = "Aguardando autorização na TV…"
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(JSONObject().put("type", "pair-request").put("device", android.os.Build.MODEL.take(48)).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (msg.optString("type")) {
                    "pair-approved" -> {
                        val issuedPin = msg.optString("pin")
                        ws.close(1000, "paired")
                        if (!issuedPin.matches(Regex("[0-9]{6}"))) {
                            runOnUiThread { status.text = "Resposta de conexão inválida." }
                            return
                        }
                        runOnUiThread {
                            address = url
                            pin = issuedPin
                            status.text = "TV autorizada! Confirme o compartilhamento da tela."
                            startActivityForResult(
                                (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(), 77
                            )
                        }
                    }
                    "pair-denied" -> {
                        ws.close(1000, "denied")
                        runOnUiThread { status.text = "A conexão foi recusada na TV." }
                    }
                }
            }
            override fun onFailure(ws: WebSocket, e: Throwable, response: Response?) {
                runOnUiThread { status.text = "Não foi possível conectar à TV." }
            }
        })
    }

    @Deprecated("MediaProjection permission callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 77 && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, StreamService::class.java).apply {
                putExtra("resultCode",resultCode); putExtra("projectionData",data)
                putExtra("url",address); putExtra("pin",pin); putExtra("room","tv")
            })
            status.text = "Iniciando transmissão para a TV…"
        } else if(requestCode==77) status.text="Compartilhamento cancelado."
    }

    override fun onDestroy() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_:Exception) {} }
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
