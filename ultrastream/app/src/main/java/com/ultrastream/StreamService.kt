package com.ultrastream

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

class StreamService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var egl: EglBase? = null
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var room = ""
    private var closed = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !intent.hasExtra("projectionData")) { stopSelf(); return START_NOT_STICKY }
        val notification = Notification.Builder(this, "stream")
            .setContentTitle("UltraStream está transmitindo sua tela")
            .setContentText("Abra o aplicativo para encerrar a transmissão.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notification)
        val projectionIntent = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("projectionData", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("projectionData")
        if (projectionIntent == null) { stopSelf(); return START_NOT_STICKY }
        room = intent.getStringExtra("room") ?: ""
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
            egl = EglBase.create()
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
                .createPeerConnectionFactory()
            capturer = ScreenCapturerAndroid(projectionIntent, object : MediaProjection.Callback() {
                override fun onStop() { mainHandler.post { stopSelf() } }
            })
            videoSource = factory!!.createVideoSource(true)
            helper = SurfaceTextureHelper.create("CaptureThread", egl!!.eglBaseContext)
            capturer!!.initialize(helper, this, videoSource!!.capturerObserver)
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            val factor = minOf(1.0, 1920.0 / maxOf(dm.widthPixels, dm.heightPixels))
            val width = (dm.widthPixels * factor).toInt().coerceAtLeast(2) / 2 * 2
            val height = (dm.heightPixels * factor).toInt().coerceAtLeast(2) / 2 * 2
            capturer!!.startCapture(width, height, 30)
            videoTrack = factory!!.createVideoTrack("screen", videoSource!!)
            connect(intent.getStringExtra("url") ?: "")
        } catch (error: Exception) {
            android.util.Log.e("UltraStream", "Failed to start stream", error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun connect(url: String) {
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(JSONObject().put("type", "join").put("room", room).put("role", "sender"))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "viewer-ready" -> mainHandler.post { startPeer() }
                        "answer" -> {
                            val answer = SessionDescription(SessionDescription.Type.ANSWER, msg.getString("sdp"))
                            peer?.setRemoteDescription(object : SdpObserverAdapter() {}, answer)
                        }
                        "candidate" -> {
                            peer?.addIceCandidate(IceCandidate(msg.optString("mid"), msg.optInt("mline"), msg.getString("candidate")))
                        }
                        "viewer-left" -> mainHandler.post { peer?.close(); peer = null }
                    }
                } catch (e: Exception) { android.util.Log.e("UltraStream", "Signaling error", e) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e("UltraStream", "WebSocket failed", t)
            }
        })
    }

    private fun startPeer() {
        peer?.close()
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory?.createPeerConnection(config, object : PeerObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                send(JSONObject().put("type", "candidate").put("room", room)
                    .put("mid", candidate.sdpMid).put("mline", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp))
            }
        })
        peer?.addTrack(videoTrack, listOf("ultrastream"))
        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        send(JSONObject().put("type", "offer").put("room", room).put("sdp", desc.description))
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    private fun send(message: JSONObject) { ws?.send(message.toString()) }
    override fun onDestroy() {
        if (!closed) {
            closed = true
            ws?.close(1000, "Stream ended")
            peer?.close(); peer?.dispose(); peer = null
            try { capturer?.stopCapture() } catch (_: Exception) {}
            capturer?.dispose(); videoTrack?.dispose(); videoSource?.dispose()
            helper?.dispose(); factory?.dispose(); egl?.release(); client.dispatcher.executorService.shutdown()
        }
        super.onDestroy()
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) { android.util.Log.e("UltraStream", error) }
    override fun onSetFailure(error: String) { android.util.Log.e("UltraStream", error) }
}
open class PeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
}
