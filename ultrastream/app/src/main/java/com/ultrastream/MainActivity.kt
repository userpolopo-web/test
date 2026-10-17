package com.ultrastream

import android.app.*
import android.content.Context
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
        root.addView(TextView(this).apply { text = "Escolha sua TV na mesma rede Wi-Fi"; textSize = 18f })
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
            list.addView(Button(this).apply { text = "📺 $name    •    Transmitir"; setOnClickListener { askPin(endpoint.first,endpoint.second) } })
        }
    }

    private fun askPin(ip: String, port: Int) {
        val input = EditText(this).apply { hint = "Código de 6 dígitos"; inputType = 2 }
        AlertDialog.Builder(this).setTitle("Código exibido na TV").setMessage("Digite o código que aparece na tela da TV.")
            .setView(input).setNegativeButton("Cancelar", null)
            .setPositiveButton("Conectar") { _, _ ->
                val code = input.text.toString().trim()
                if (!code.matches(Regex("[0-9]{6}"))) { status.text = "O código deve ter 6 números."; return@setPositiveButton }
                validatePin("ws://$ip:$port", code)
            }.show()
    }

    private fun validatePin(url: String, code: String) {
        status.text = "Conectando à TV…"
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(JSONObject().put("type","check").put("pin",code).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                val valid = try { JSONObject(text).optString("type") == "pin-valid" } catch (_: Exception) { false }
                ws.close(1000,"checked")
                runOnUiThread {
                    if (valid) {
                        address=url; pin=code
                        status.text = "TV conectada! Autorize compartilhar sua tela."
                        startActivityForResult((getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),77)
                    } else status.text = "Código incorreto ou expirado."
                }
            }
            override fun onFailure(ws: WebSocket, e: Throwable, response: Response?) { runOnUiThread { status.text = "Não foi possível conectar à TV." } }
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
            status.text = "Transmitindo para sua TV."
        } else if(requestCode==77) status.text="Compartilhamento cancelado."
    }

    override fun onDestroy() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_:Exception) {} }
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
