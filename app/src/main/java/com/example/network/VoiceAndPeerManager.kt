package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.math.sqrt

/**
 * Real Android AudioRecord voice activity monitor.
 * Measures live microphone RMS energy (0f..1f) to drive Lobby Mic Check and HUD speaking glow.
 */
class NativeMicMonitor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _isMicActive = MutableStateFlow(false)
    val isMicActive: StateFlow<Boolean> = _isMicActive.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) {
            _micLevel.value = 0f
            _isMicActive.value = false
            return
        }
        if (monitorJob?.isActive == true) return
        _isMicActive.value = true
        monitorJob = scope.launch {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@launch
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    return@launch
                }
                recorder.startRecording()
                val buffer = ShortArray(minBuf / 2)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val norm = buffer[i] / 32768.0
                            sumSq += norm * norm
                        }
                        val rms = sqrt(sumSq / read).toFloat()
                        val boosted = (rms * 7.5f).coerceIn(0f, 1f)
                        _micLevel.value = _micLevel.value * 0.45f + boosted * 0.55f
                    }
                    delay(45)
                }
            } catch (_: Throwable) {
                _micLevel.value = 0f
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Throwable) {
                }
                _micLevel.value = 0f
                _isMicActive.value = false
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        _micLevel.value = 0f
        _isMicActive.value = false
    }
}

interface PeerEventsListener {
    fun onPeerStatus(status: String, isConnected: Boolean)
    fun onRemotePeerJoined(peerId: String, name: String)
    fun onRemotePeerLeft(peerId: String)
    fun onRemotePacket(peerId: String, payloadJson: String)
    fun onRemoteVoiceLevel(peerId: String, level: Float)
}

/**
 * Local Network (Hotspot / Wi-Fi) Socket Manager.
 * Enables zero-internet and ultra-low latency direct LAN multiplayer when users are on
 * the same Wi-Fi router or Mobile Hotspot.
 */
class BouChorLanManager(private val listener: PeerEventsListener) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var clientSocket: Socket? = null
    private val hostClientSockets = Collections.synchronizedList(mutableListOf<Socket>())
    private var beaconJob: Job? = null
    private var searchJob: Job? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var isHosting = false
    private var currentRoomCode = ""
    private var myName = "Player"

    fun startHosting(roomCode: String, playerName: String) {
        stopAll()
        isHosting = true
        currentRoomCode = roomCode.trim().uppercase()
        myName = playerName

        serverJob = scope.launch {
            try {
                val ss = ServerSocket(8988)
                serverSocket = ss
                while (isActive) {
                    val client = ss.accept()
                    hostClientSockets.add(client)
                    launch { handleHostClient(client) }
                }
            } catch (_: Throwable) {}
        }

        beaconJob = scope.launch {
            try {
                val ds = DatagramSocket()
                ds.broadcast = true
                udpSocket = ds
                val msg = JSONObject().apply {
                    put("type", "bouchor_beacon")
                    put("code", currentRoomCode)
                    put("host", myName)
                    put("port", 8988)
                }.toString().toByteArray()

                while (isActive) {
                    val targets = getBroadcastAddresses()
                    for (target in targets) {
                        try {
                            val packet = DatagramPacket(msg, msg.size, target, 8989)
                            ds.send(packet)
                        } catch (_: Throwable) {}
                    }
                    delay(800)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableSetOf<InetAddress>()
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        runCatching { list.add(InetAddress.getByName("192.168.43.255")) }
        runCatching { list.add(InetAddress.getByName("10.0.2.255")) }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                for (interfaceAddress in netIf.interfaceAddresses) {
                    val bcast = interfaceAddress.broadcast
                    if (bcast != null) {
                        list.add(bcast)
                    }
                }
            }
        }
        return list.toList()
    }

    private suspend fun handleHostClient(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val clientId = "lan_guest_${socket.port}"
        try {
            while (scope.isActive && socket.isConnected && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val obj = JSONObject(line)
                when (obj.optString("type")) {
                    "hello" -> {
                        val guestName = obj.optString("name", "Guest")
                        listener.onRemotePeerJoined(clientId, guestName)
                        val reply = JSONObject().apply {
                            put("type", "hello_reply")
                            put("name", myName)
                            put("isHost", true)
                            put("roomCode", currentRoomCode)
                        }.toString()
                        sendToSocket(socket, reply)
                    }
                    else -> {
                        listener.onRemotePacket(clientId, line)
                        broadcastExcept(line, socket)
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            hostClientSockets.remove(socket)
            try { socket.close() } catch (_: Throwable) {}
            listener.onRemotePeerLeft(clientId)
        }
    }

    fun startJoining(roomCode: String, playerName: String) {
        stopAll()
        isHosting = false
        val targetInput = roomCode.trim().uppercase()
        currentRoomCode = targetInput
        myName = playerName

        searchJob = scope.launch {
            var connected = false
            val discoveredIps = Collections.synchronizedSet(mutableSetOf<String>())

            if (targetInput.contains(".")) {
                discoveredIps.add(targetInput)
            }

            discoveredIps.add("192.168.43.1")
            discoveredIps.add("10.0.2.2")
            discoveredIps.add("10.0.2.15")
            discoveredIps.add("127.0.0.1")

            val beaconSocket = try {
                DatagramSocket(8989).apply {
                    soTimeout = 1200
                    broadcast = true
                }
            } catch (_: Throwable) { null }

            val startTime = System.currentTimeMillis()

            // 1. UDP Listener coroutine
            launch {
                val buf = ByteArray(1024)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive && !connected) {
                    if (beaconSocket != null) {
                        try {
                            beaconSocket.receive(packet)
                            val text = String(packet.data, 0, packet.length)
                            val obj = JSONObject(text)
                            val code = obj.optString("code")
                            if (code.equals(currentRoomCode, ignoreCase = true) || targetInput.contains(".")) {
                                val hostIp = packet.address?.hostAddress
                                if (hostIp != null) {
                                    discoveredIps.add(hostIp)
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    delay(200)
                }
            }

            // 2. Parallel Fast Subnet IP Scanner
            val localSubnets = getLocalSubnetPrefixes()

            while (isActive && !connected && (System.currentTimeMillis() - startTime < 12000)) {
                val snapshot = discoveredIps.toList()
                for (ip in snapshot) {
                    if (connected) break
                    val s = tryConnectIp(ip)
                    if (s != null) {
                        clientSocket = s
                        connected = true
                        listener.onPeerStatus("Connected to Host via Local Network ($ip)", true)
                        clientJob = launch { handleClientSession(s) }
                        break
                    }
                }

                if (!connected) {
                    for (prefix in localSubnets) {
                        if (connected) break
                        val jobs = (1..254).map { lastOctet ->
                            async {
                                if (connected) return@async null
                                val testIp = "$prefix$lastOctet"
                                tryConnectIp(testIp)
                            }
                        }
                        for (j in jobs) {
                            val resSocket = j.await()
                            if (resSocket != null && !connected) {
                                clientSocket = resSocket
                                connected = true
                                listener.onPeerStatus("Connected to Host via Local Network ($prefix...)", true)
                                clientJob = launch { handleClientSession(resSocket) }
                                break
                            }
                        }
                    }
                }

                delay(600)
            }
            try { beaconSocket?.close() } catch (_: Throwable) {}
        }
    }

    private suspend fun tryConnectIp(ip: String): Socket? = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(ip, 8988), 350)
            val out = PrintWriter(OutputStreamWriter(s.getOutputStream()), true)
            val hello = JSONObject().apply {
                put("type", "hello")
                put("name", myName)
                put("isHost", false)
                put("roomCode", currentRoomCode)
            }.toString()
            out.println(hello)

            s.soTimeout = 800
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val respLine = reader.readLine() ?: return@withContext null
            val obj = JSONObject(respLine)
            val respType = obj.optString("type")
            val respCode = obj.optString("roomCode")

            if (respType == "hello_reply" && (currentRoomCode.contains(".") || respCode.equals(currentRoomCode, ignoreCase = true))) {
                val hostName = obj.optString("name", "Host")
                listener.onRemotePeerJoined("lan_host", hostName)
                s.soTimeout = 0
                s
            } else {
                try { s.close() } catch (_: Throwable) {}
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getLocalSubnetPrefixes(): List<String> {
        val prefixes = mutableSetOf<String>()
        prefixes.add("192.168.1.")
        prefixes.add("192.168.0.")
        prefixes.add("192.168.43.")
        prefixes.add("10.0.2.")
        prefixes.add("172.20.10.")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                val addrs = netIf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        val lastDot = host.lastIndexOf('.')
                        if (lastDot > 0) {
                            val prefix = host.substring(0, lastDot + 1)
                            prefixes.add(prefix)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return prefixes.toList()
    }

    private suspend fun handleClientSession(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val hostId = "lan_host"
        try {
            while (scope.isActive && socket.isConnected && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val obj = JSONObject(line)
                when (obj.optString("type")) {
                    "hello_reply" -> {
                        val hName = obj.optString("name", "Host")
                        listener.onRemotePeerJoined(hostId, hName)
                    }
                    else -> {
                        listener.onRemotePacket(hostId, line)
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { socket.close() } catch (_: Throwable) {}
            listener.onRemotePeerLeft(hostId)
        }
    }

    fun broadcast(jsonString: String) {
        scope.launch {
            if (isHosting) {
                val dead = mutableListOf<Socket>()
                synchronized(hostClientSockets) {
                    for (s in hostClientSockets) {
                        if (!sendToSocket(s, jsonString)) dead.add(s)
                    }
                    hostClientSockets.removeAll(dead)
                }
            } else {
                clientSocket?.let { sendToSocket(it, jsonString) }
            }
        }
    }

    private fun broadcastExcept(jsonString: String, except: Socket) {
        synchronized(hostClientSockets) {
            for (s in hostClientSockets) {
                if (s !== except) sendToSocket(s, jsonString)
            }
        }
    }

    private fun sendToSocket(s: Socket, data: String): Boolean {
        return try {
            if (s.isConnected && !s.isClosed) {
                val out = PrintWriter(OutputStreamWriter(s.getOutputStream()), true)
                out.println(data)
                true
            } else false
        } catch (_: Throwable) {
            false
        }
    }

    fun stopAll() {
        searchJob?.cancel()
        searchJob = null
        beaconJob?.cancel()
        beaconJob = null
        serverJob?.cancel()
        serverJob = null
        clientJob?.cancel()
        clientJob = null

        try { udpSocket?.close() } catch (_: Throwable) {}
        udpSocket = null
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        try { clientSocket?.close() } catch (_: Throwable) {}
        clientSocket = null
        synchronized(hostClientSockets) {
            for (s in hostClientSockets) {
                try { s.close() } catch (_: Throwable) {}
            }
            hostClientSockets.clear()
        }
    }
}

fun getPrimaryLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val netIf = interfaces.nextElement()
            if (netIf.isLoopback || !netIf.isUp) continue
            val addrs = netIf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress ?: continue
                    if (host.isNotEmpty() && !host.startsWith("127.")) {
                        return host
                    }
                }
            }
        }
    } catch (_: Throwable) {}
    return "127.0.0.1"
}

/**
 * Headless PeerJS + WebRTC P2P data and proximity voice chat bridge,
 * augmented with local LAN / Hotspot direct socket connectivity.
 */
class PeerBridgeController(
    private val context: Context,
    private val listener: PeerEventsListener
) {
    private var webView: WebView? = null
    private var isPageReady = false
    private val pendingScripts = mutableListOf<String>()
    private val lanManager = BouChorLanManager(listener)

    private val peerJsSource: String by lazy {
        try {
            context.assets.open("peerjs.min.js").bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            ""
        }
    }

    fun getBridgeHtml(): String = buildBridgeHtml(peerJsSource)

    fun attachWebView(view: WebView) {
        webView = view
    }

    fun onPageReady() {
        isPageReady = true
        pendingScripts.forEach { evalJs(it) }
        pendingScripts.clear()
    }

    fun createRoom(roomCode: String, playerName: String) {
        lanManager.startHosting(roomCode, playerName)
        val safeName = JSONObject.quote(playerName)
        val safeCode = JSONObject.quote(roomCode)
        evalJs("window.BouChorNet && window.BouChorNet.createRoom($safeCode, $safeName);")
    }

    fun joinRoom(roomCode: String, playerName: String) {
        lanManager.startJoining(roomCode, playerName)
        val safeName = JSONObject.quote(playerName)
        val safeCode = JSONObject.quote(roomCode)
        evalJs("window.BouChorNet && window.BouChorNet.joinRoom($safeCode, $safeName);")
    }

    fun broadcastJson(json: String) {
        lanManager.broadcast(json)
        val quoted = JSONObject.quote(json)
        evalJs("window.BouChorNet && window.BouChorNet.broadcast($quoted);")
    }

    fun setAudioControls(micOn: Boolean, speakerMuted: Boolean) {
        evalJs("window.BouChorNet && window.BouChorNet.setAudio($micOn, $speakerMuted);")
    }

    fun updatePeerGain(peerId: String, gain: Float) {
        val safePeer = JSONObject.quote(peerId)
        evalJs("window.BouChorNet && window.BouChorNet.setPeerGain($safePeer, ${gain.coerceIn(0f, 1f)});")
    }

    fun disconnect() {
        lanManager.stopAll()
        evalJs("window.BouChorNet && window.BouChorNet.disconnect();")
    }

    private fun evalJs(script: String) {
        val wv = webView
        if (wv == null || !isPageReady) {
            pendingScripts.add(script)
            return
        }
        wv.post { wv.evaluateJavascript(script, null) }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onStatus(status: String, connected: Boolean) {
            listener.onPeerStatus(status, connected)
        }

        @JavascriptInterface
        fun onPeerJoined(peerId: String, name: String) {
            listener.onRemotePeerJoined(peerId, name)
        }

        @JavascriptInterface
        fun onPeerLeft(peerId: String) {
            listener.onRemotePeerLeft(peerId)
        }

        @JavascriptInterface
        fun onPacket(peerId: String, payload: String) {
            listener.onRemotePacket(peerId, payload)
        }

        @JavascriptInterface
        fun onVoiceLevel(peerId: String, level: Float) {
            listener.onRemoteVoiceLevel(peerId, level)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HeadlessPeerBridge(
    controller: PeerBridgeController,
    micPermissionGranted: Boolean
) {
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                addJavascriptInterface(controller.JsBridge(), "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        if (wantsAudio && micPermissionGranted) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            request.deny()
                        }
                    }
                }
                controller.attachWebView(this)
                loadDataWithBaseURL(
                    "https://localhost/",
                    controller.getBridgeHtml(),
                    "text/html",
                    "UTF-8",
                    null
                )
                postDelayed({ controller.onPageReady() }, 200)
            }
        }
    )
}

private fun buildBridgeHtml(peerJsContent: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<script>
$peerJsContent
</script>
</head>
<body>
<script>
(function() {
  let peer = null;
  let dataConns = {};
  let mediaCalls = {};
  let peerGains = {};
  let audioCtx = null;
  let localStream = null;
  let micEnabled = true;
  let speakerMuted = false;
  let myName = "Player";
  let myRoomCode = "";

  const PEER_CONFIG = {
    debug: 1,
    config: {
      iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
        { urls: 'stun:stun2.l.google.com:19302' },
        { urls: 'stun:stun3.l.google.com:19302' },
        { urls: 'stun:stun4.l.google.com:19302' },
        { urls: 'stun:stun.services.mozilla.com' },
        { urls: 'stun:stun.cloudflare.com:3478' },
        { urls: 'stun:global.stun.twilio.com:3478' },
        {
          urls: [
            'turn:openrelay.metered.ca:80',
            'turn:openrelay.metered.ca:443',
            'turn:openrelay.metered.ca:443?transport=tcp'
          ],
          username: 'openrelay',
          credential: 'openrelay'
        }
      ],
      iceCandidatePoolSize: 10
    }
  };

  function notifyStatus(msg, ok) {
    if (window.AndroidBridge) window.AndroidBridge.onStatus(msg, !!ok);
  }

  async function ensureAudioStream() {
    if (localStream) return localStream;
    try {
      if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
        localStream.getAudioTracks().forEach(t => t.enabled = micEnabled);
      }
    } catch (e) {
      localStream = null;
    }
    return localStream;
  }

  function attachRemoteStream(peerId, stream) {
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
      const src = audioCtx.createMediaStreamSource(stream);
      const gain = audioCtx.createGain();
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 256;
      gain.gain.value = speakerMuted ? 0 : 1;
      src.connect(analyser);
      analyser.connect(gain);
      gain.connect(audioCtx.destination);
      peerGains[peerId] = gain;

      const data = new Uint8Array(analyser.frequencyBinCount);
      const timer = setInterval(() => {
        if (!peerGains[peerId]) { clearInterval(timer); return; }
        analyser.getByteFrequencyData(data);
        let sum = 0;
        for (let i = 0; i < data.length; i++) sum += data[i];
        const level = Math.min(1, (sum / data.length) / 90);
        if (window.AndroidBridge) window.AndroidBridge.onVoiceLevel(peerId, level);
      }, 120);
    } catch (e) {}
  }

  function wireDataConn(conn, isOutgoing) {
    dataConns[conn.peer] = conn;

    function handleOpen() {
      notifyStatus(isOutgoing ? ('Connected to Room ' + myRoomCode + '!') : 'Player joined!', true);
      try {
        conn.send(JSON.stringify({
          type: 'hello',
          name: myName,
          isHost: !isOutgoing,
          roomCode: myRoomCode
        }));
      } catch (e) {}

      if (isOutgoing && localStream) {
        try {
          const call = peer.call(conn.peer, localStream);
          if (call) {
            mediaCalls[conn.peer] = call;
            call.on('stream', (rs) => attachRemoteStream(conn.peer, rs));
          }
        } catch (e) {}
      }
    }

    if (conn.open) {
      handleOpen();
    } else {
      conn.on('open', handleOpen);
    }

    conn.on('data', (raw) => {
      try {
        const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (parsed.type === 'hello') {
          const senderName = parsed.name || (parsed.isHost ? 'Host' : 'Guest');
          if (window.AndroidBridge) {
            window.AndroidBridge.onPeerJoined(conn.peer, senderName);
          }
          if (!isOutgoing) {
            try {
              conn.send(JSON.stringify({
                type: 'hello_reply',
                name: myName,
                isHost: true,
                roomCode: myRoomCode
              }));
            } catch (e) {}
          }
        } else if (parsed.type === 'hello_reply') {
          const hostName = parsed.name || 'Host';
          if (window.AndroidBridge) {
            window.AndroidBridge.onPeerJoined(conn.peer, hostName);
          }
        } else {
          if (window.AndroidBridge) {
            window.AndroidBridge.onPacket(conn.peer, typeof raw === 'string' ? raw : JSON.stringify(raw));
          }
        }
      } catch (e) {}
    });

    conn.on('close', () => {
      delete dataConns[conn.peer];
      delete peerGains[conn.peer];
      if (window.AndroidBridge) window.AndroidBridge.onPeerLeft(conn.peer);
    });

    conn.on('error', (err) => {
      console.error('Conn error', err);
    });
  }

  window.BouChorNet = {
    createRoom: async function(code, name) {
      this.disconnect();
      myName = name || 'Host';
      myRoomCode = (code || '').toUpperCase();
      if (typeof Peer === 'undefined') {
        notifyStatus('P2P Initializing...', false);
        return;
      }
      await ensureAudioStream();
      notifyStatus('Opening P2P Room ' + myRoomCode + '...', false);
      try {
        const hostPeerId = 'bouchor-room-' + myRoomCode;
        peer = new Peer(hostPeerId, PEER_CONFIG);
        peer.on('open', (id) => {
          notifyStatus('Room ' + myRoomCode + ' Live (P2P WebRTC)', true);
        });
        peer.on('connection', (conn) => {
          wireDataConn(conn, false);
        });
        peer.on('call', (call) => {
          call.answer(localStream || undefined);
          mediaCalls[call.peer] = call;
          call.on('stream', (rs) => attachRemoteStream(call.peer, rs));
        });
        peer.on('error', (err) => {
          const errType = err ? err.type : '';
          if (errType === 'unavailable-id') {
            notifyStatus('Room code in use! Try creating again.', false);
          } else {
            notifyStatus('P2P status: ' + (errType || 'waiting'), false);
          }
        });
      } catch (e) {
        notifyStatus('Local Lobby Ready', false);
      }
    },

    joinRoom: async function(code, name) {
      this.disconnect();
      myName = name || 'Guest';
      myRoomCode = (code || '').toUpperCase();
      if (typeof Peer === 'undefined') {
        notifyStatus('P2P Initializing...', false);
        return;
      }
      await ensureAudioStream();
      notifyStatus('Connecting to Room ' + myRoomCode + '...', false);
      try {
        peer = new Peer(null, PEER_CONFIG);
        peer.on('open', (myId) => {
          const hostId = 'bouchor-room-' + myRoomCode;
          const conn = peer.connect(hostId, { reliable: true });
          wireDataConn(conn, true);
        });
        peer.on('error', (err) => {
          const errType = err ? err.type : '';
          if (errType === 'peer-unavailable') {
            notifyStatus('Room ' + myRoomCode + ' not found! Host may still be starting.', false);
          } else {
            notifyStatus('Connecting... (' + (errType || 'P2P') + ')', false);
          }
        });
      } catch (e) {
        notifyStatus('Join failed', false);
      }
    },

    broadcast: function(jsonStr) {
      Object.values(dataConns).forEach(c => {
        if (c && c.open) { try { c.send(jsonStr); } catch(e) {} }
      });
    },

    setAudio: function(mic, muted) {
      micEnabled = !!mic;
      speakerMuted = !!muted;
      if (localStream) {
        localStream.getAudioTracks().forEach(t => t.enabled = micEnabled);
      }
      Object.values(peerGains).forEach(g => {
        if (g) g.gain.value = speakerMuted ? 0 : g.gain.value;
      });
    },

    setPeerGain: function(peerId, val) {
      const g = peerGains[peerId];
      if (g) g.gain.value = speakerMuted ? 0 : val;
    },

    disconnect: function() {
      Object.values(dataConns).forEach(c => { try { c.close(); } catch(e) {} });
      Object.values(mediaCalls).forEach(c => { try { c.close(); } catch(e) {} });
      dataConns = {};
      mediaCalls = {};
      peerGains = {};
      if (peer) { try { peer.destroy(); } catch(e) {} peer = null; }
    }
  };
})();
</script>
</body>
</html>
"""
