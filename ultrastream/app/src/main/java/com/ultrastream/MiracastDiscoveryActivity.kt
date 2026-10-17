package com.ultrastream

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pWfdInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Diagnóstico de descoberta Wi-Fi Direct / Wi-Fi Display.
 *
 * NÃO estabelece uma sessão Miracast nem transmite vídeo.
 * CONFIGURE_WIFI_DISPLAY é uma permissão privilegiada do Android.
 */
class MiracastDiscoveryActivity : Activity() {
    private val requestPermissionCode = 3209
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private lateinit var status: TextView
    private lateinit var peersBox: LinearLayout
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> updatePeers()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        status.text = "Wi-Fi Direct desativado neste aparelho."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "UltraStream — diagnóstico Miracast"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Esta versão experimental encontra dispositivos Wi-Fi Direct e identifica os que anunciam suporte Wi-Fi Display. Ainda NÃO transmite a tela."
            textSize = 16f
        })
        status = TextView(this).apply {
            text = "Toque em buscar para testar seu Samsung."
            textSize = 16f
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Buscar TVs (Wi-Fi Direct)"
            setOnClickListener { beginDiscovery() }
        })
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peersBox)
        root.addView(TextView(this).apply {
            text = "Antes do teste, ative Wi-Fi e Localização do celular e deixe a TV no modo de espelhamento. Ver um aparelho na lista não significa que já seja possível transmitir para ele."
            textSize = 14f
        })
        setContentView(ScrollView(this).apply { addView(root) })

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            status.text = "Este celular não declara suporte a Wi-Fi Direct."
            return
        }
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(this, Looper.getMainLooper()) {
            status.text = "A conexão de diagnóstico foi interrompida."
        }
        if (manager == null || channel == null) {
            status.text = "Wi-Fi Direct não está disponível neste aparelho."
        }
    }

    override fun onStart() {
        super.onStart()
        if (manager == null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun permissionName(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    private fun beginDiscovery() {
        val permission = permissionName()
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), requestPermissionCode)
            return
        }
        val service = manager ?: return
        val activeChannel = channel ?: return
        status.text = "Procurando aparelhos próximos…"
        peersBox.removeAllViews()
        try {
            service.discoverPeers(activeChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    status.text = "Busca iniciada. Aguarde alguns segundos…"
                    updatePeers()
                }
                override fun onFailure(reason: Int) {
                    status.text = "Falha na descoberta Wi-Fi Direct (código $reason)."
                }
            })
        } catch (e: SecurityException) {
            status.text = "Permissão de dispositivos próximos necessária."
        }
    }

    private fun updatePeers() {
        val service = manager ?: return
        val activeChannel = channel ?: return
        if (checkSelfPermission(permissionName()) != PackageManager.PERMISSION_GRANTED) return
        try {
            service.requestPeers(activeChannel) { peers ->
                if (isFinishing || isDestroyed) return@requestPeers
                val items = peers.deviceList.sortedBy { it.deviceName ?: "" }
                peersBox.removeAllViews()
                status.text = if (items.isEmpty()) {
                    "Nenhum aparelho encontrado. Ative o modo de espelhamento da TV e tente novamente."
                } else {
                    "${items.size} dispositivo(s) Wi-Fi Direct encontrado(s)."
                }
                items.forEach { peer -> displayPeer(peer) }
            }
        } catch (e: SecurityException) {
            status.text = "Permissão Wi-Fi insuficiente para listar aparelhos."
        }
    }

    private fun displayPeer(peer: WifiP2pDevice) {
        val wfd = if (Build.VERSION.SDK_INT >= 30) peer.wfdInfo else null
        val classification = if (wfd == null) {
            "Wi-Fi Direct; suporte Miracast não informado"
        } else if (!wfd.isEnabled) {
            "Wi-Fi Display não está ativo"
        } else when (wfd.deviceType) {
            WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK,
            WifiP2pWfdInfo.DEVICE_TYPE_SECONDARY_SINK,
            WifiP2pWfdInfo.DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK -> "Receptor Wi-Fi Display anunciado"
            else -> "Wi-Fi Display: função transmissora"
        }
        peersBox.addView(TextView(this).apply {
            text = "📺 ${peer.deviceName ?: "Dispositivo sem nome"}\n$classification"
            textSize = 17f
            setPadding(0, 18, 0, 18)
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPermissionCode) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginDiscovery()
            else status.text = "Sem a permissão solicitada não é possível descobrir dispositivos."
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }
}
