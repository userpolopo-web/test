package com.ultrastream.tv

import android.app.Activity
import android.app.AlertDialog
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import org.webrtc.*
import java.net.InetSocketAddress
import java.security.SecureRandom

class TvActivity : Activity() {
    private lateinit var nsd: NsdManager
    private var server: WebSocketServer? = null
    private var renderer: SurfaceViewRenderer? = null
    private var codeLabel: TextView? = null
    private var status: TextView? = null
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var sender: WebSocket? = null
    private val pending = mutableListOf<IceCandidate>()
    private val pin = (SecureRandom().nextInt(900000) + 100000).toString()
    private var registered: NsdServiceInfo? = null
    private var attempts = mutableMapOf<WebSocket,Int>()
    private val port = 39876

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff090c12.toInt()); gravity = Gravity.CENTER; setPadding(24,24,24,24) }
        val title = TextView(this).apply { text = "UltraStream TV"; textSize = 32f; setTextColor(-1); gravity = Gravity.CENTER }
        codeLabel = TextView(this).apply { text = "Código de conexão: $pin"; textSize = 28f; setTextColor(-1); gravity = Gravity.CENTER }
        status = TextView(this).apply { text = "Abra UltraStream no celular conectado ao mesmo Wi-Fi."; textSize = 18f; setTextColor(-1); gravity = Gravity.CENTER }
        renderer = SurfaceViewRenderer(this)
        root.addView(title); root.addView(codeLabel); root.addView(status)
        root.addView(renderer, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        egl = EglBase.create()
        renderer!!.init(egl!!.eglBaseContext, null)
        factory = PeerConnectionFactory.builder()
          .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext,true,true))
          .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
          .createPeerConnectionFactory()
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        startReceiver()
    }

    private fun send(client: WebSocket?, message: JSONObject) { client?.send(message.toString()) }

    private fun startReceiver() {
        server = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onStart() { advertise() }
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                attempts.remove(conn)
                pendingPairings.remove(conn)
                if (sender === conn) {
                    sender = null
                    runOnUiThread { peer?.close(); peer?.dispose(); peer = null; status?.text = "Aguardando conexão do celular…"; codeLabel?.visibility = android.view.View.VISIBLE }
                }
            }
            override fun onError(conn: WebSocket?, ex: Exception) {
                android.util.Log.e("UltraStreamTV","Server error",ex)
            }
            override fun onMessage(conn: WebSocket, message: String) {
                try {
                    val msg = JSONObject(message)
                    when(msg.optString("type")) {
                        "pair-request" -> {
                            val fresh = pendingPairings.add(conn)
                            if (fresh) {
                                val deviceName = msg.optString("device").take(48).ifBlank { "Celular Android" }
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        AlertDialog.Builder(this@TvActivity)
                                            .setTitle("Autorizar transmissão?")
                                            .setMessage("$deviceName quer transmitir a tela para esta TV.")
                                            .setNegativeButton("Recusar") { _, _ ->
                                                pendingPairings.remove(conn)
                                                send(conn, JSONObject().put("type", "pair-denied"))
                                            }
                                            .setPositiveButton("Permitir") { _, _ ->
                                                pendingPairings.remove(conn)
                                                if (conn.isOpen) {
                                                    send(conn, JSONObject().put("type", "pair-approved").put("pin", pin))
                                                }
                                            }.show()
                                    }
                                }
                            }
                        }
                        "check", "auth" -> {
                            val count = attempts[conn] ?: 0
                            if (count >= 5) { conn.close(1008,"Too many tries"); return }
                            attempts[conn] = count + 1
                            val valid = msg.optString("pin") == pin
                            if (valid && msg.optString("type") == "auth") {
                                sender?.close(1000,"Replaced")
                                sender=conn
                            }
                            send(conn,JSONObject().put("type", if(valid) "pin-valid" else "pin-invalid"))
                        }
                        "ready" -> if(conn === sender) send(conn,JSONObject().put("type","viewer-ready"))
                        "offer" -> if(conn === sender) runOnUiThread { receiveOffer(msg.getString("sdp")) }
                        "candidate" -> if(conn === sender) runOnUiThread {
                            val ice=IceCandidate(msg.optString("mid"),msg.optInt("mline"),msg.getString("candidate"))
                            if(peer?.remoteDescription != null) peer?.addIceCandidate(ice) else pending.add(ice)
                        }
                    }
                } catch(e: Exception) { android.util.Log.e("UltraStreamTV","Invalid message",e) }
            }
        }.also { it.start() }
    }

    private fun advertise() {
        val info=NsdServiceInfo().apply { serviceName="UltraStream TV"; serviceType="_ultrastream._tcp."; setPort(port) }
        nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { registered=serviceInfo }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo,errorCode:Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo,errorCode:Int) {}
        }.also { registration = it })
    }
    private var registration: NsdManager.RegistrationListener? = null

    private fun receiveOffer(sdp:String) {
        peer?.close(); peer?.dispose()
        pending.clear()
        peer=factory?.createPeerConnection(
            PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())),
            object : PeerObserver() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    send(sender,JSONObject().put("type","candidate").put("mid",candidate.sdpMid)
                        .put("mline",candidate.sdpMLineIndex).put("candidate",candidate.sdp))
                }
                override fun onAddTrack(receiver:RtpReceiver, streams:Array<MediaStream>) {
                    val track=receiver.track()
                    if(track is VideoTrack) runOnUiThread {
                        track.addSink(renderer)
                        status?.text = "Transmitindo tela do celular"
                        codeLabel?.visibility = android.view.View.GONE
                    }
                }
            })
        peer?.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                for(candidate in pending) peer?.addIceCandidate(candidate)
                pending.clear()
                peer?.createAnswer(object : SdpAdapter() {
                    override fun onCreateSuccess(desc:SessionDescription) {
                        peer?.setLocalDescription(object : SdpAdapter() {
                            override fun onSetSuccess() { send(sender,JSONObject().put("type","answer").put("sdp",desc.description)) }
                        },desc)
                    }
                },MediaConstraints())
            }
        },SessionDescription(SessionDescription.Type.OFFER,sdp))
    }

    override fun onDestroy() {
        registration?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        server?.stop(500)
        peer?.close(); peer?.dispose(); factory?.dispose()
        renderer?.release(); egl?.release()
        super.onDestroy()
    }
}

open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(sdp:SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error:String) { android.util.Log.e("UltraStreamTV",error) }
    override fun onSetFailure(error:String) { android.util.Log.e("UltraStreamTV",error) }
}
open class PeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state:PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state:PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving:Boolean) {}
    override fun onIceGatheringChange(state:PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate:IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates:Array<IceCandidate>) {}
    override fun onAddStream(stream:MediaStream) {}
    override fun onRemoveStream(stream:MediaStream) {}
    override fun onDataChannel(channel:DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver:RtpReceiver,streams:Array<MediaStream>) {}
}
