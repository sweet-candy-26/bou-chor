package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
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
import java.util.Collections
import java.util.UUID
import kotlin.math.sqrt

/**
 * Real Android AudioRecord live voice capture & G.711 u-law encoder.
 * Captures 8000 Hz Mono PCM-16 with AEC + automatic MIC fallback,
 * drives Lobby/HUD mic meters, and streams compact VAD-gated u-law chunks.
 */
class NativeMicMonitor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    @Volatile
    var onEncodedVoiceFrame: ((String) -> Unit)? = null

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
            val sampleRate = 8000
            val frameSamples = 640 // 80ms per frame
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                _isMicActive.value = false
                return@launch
            }
            val bufBytes = maxOf(minBuf * 2, frameSamples * 4)
            var recorder: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            try {
                for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
                    val candidate = runCatching {
                        AudioRecord(
                            source,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufBytes
                        )
                    }.getOrNull()
                    if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                        val started = runCatching { candidate.startRecording(); true }.getOrDefault(false)
                        if (started) {
                            recorder = candidate
                            break
                        }
                    }
                    runCatching { candidate?.release() }
                }
                if (recorder == null) {
                    _isMicActive.value = false
                    return@launch
                }
                runCatching {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                    }
                }
                val buffer = ShortArray(frameSamples)
                val uLawBytes = ByteArray(frameSamples)
                var hangoverFrames = 0
                while (isActive) {
                    var offset = 0
                    while (offset < frameSamples && isActive) {
                        val read = recorder.read(buffer, offset, frameSamples - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    if (offset > 0) {
                        var sumSq = 0.0
                        for (i in 0 until offset) {
                            val norm = buffer[i] / 32768.0
                            sumSq += norm * norm
                        }
                        val rms = sqrt(sumSq / offset).toFloat()
                        val boosted = (rms * 10.5f).coerceIn(0f, 1f)
                        _micLevel.value = _micLevel.value * 0.42f + boosted * 0.58f

                        val callback = onEncodedVoiceFrame
                        if (callback != null && offset == frameSamples) {
                            if (rms > 0.012f) hangoverFrames = 6
                            if (hangoverFrames > 0) {
                                hangoverFrames--
                                encodeMuLawFrame(buffer, uLawBytes)
                                val b64 = Base64.encodeToString(uLawBytes, Base64.NO_WRAP)
                                callback(b64)
                            }
                        }
                    } else {
                        delay(40)
                    }
                }
            } catch (_: Throwable) {
                _micLevel.value = 0f
            } finally {
                runCatching { aec?.release() }
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
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

private fun encodeMuLawFrame(pcm: ShortArray, out: ByteArray) {
    for (i in pcm.indices) {
        var sample = pcm[i].toInt()
        val sign = if (sample < 0) {
            sample = -sample
            0x80
        } else {
            0
        }
        if (sample > 32635) sample = 32635
        sample += 0x84
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (sample and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (sample shr (exponent + 3)) and 0x0F
        out[i] = (sign or (exponent shl 4) or mantissa).inv().toByte()
    }
}

private fun decodeMuLawFrame(uLaw: ByteArray): ShortArray {
    val out = ShortArray(uLaw.size)
    for (i in uLaw.indices) {
        val u = uLaw[i].toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u and 0x70) shr 4
        val mantissa = u and 0x0F
        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84
        out[i] = (if (sign != 0) -sample else sample).coerceIn(-32768, 32767).toShort()
    }
    return out
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
 * Passes the established BufferedReader seamlessly from handshake to session reader
 * so zero packets (like immediate lobby_sync) are ever lost in buffers.
 */
class BouChorLanManager(
    private val localPeerId: String,
    private val onStatus: (String, Boolean) -> Unit,
    private val onPacketReceived: (senderId: String, payloadJson: String) -> Unit,
    private val onPeerDisconnected: (peerId: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var clientSocket: Socket? = null
    private val hostClientSockets = Collections.synchronizedList(mutableListOf<Socket>())
    private var beaconJob: Job? = null
    private var searchJob: Job? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null

    @Volatile
    private var isHosting = false

    @Volatile
    private var currentRoomCode = ""

    @Volatile
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
                    client.tcpNoDelay = true
                    hostClientSockets.add(client)
                    launch { handleHostClient(client) }
                }
            } catch (_: Throwable) {
            }
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
                            ds.send(DatagramPacket(msg, msg.size, target, 8989))
                        } catch (_: Throwable) {
                        }
                    }
                    delay(900)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableSetOf<InetAddress>()
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        runCatching { list.add(InetAddress.getByName("192.168.43.255")) }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                for (interfaceAddress in netIf.interfaceAddresses) {
                    interfaceAddress.broadcast?.let { list.add(it) }
                }
            }
        }
        return list.toList()
    }

    private suspend fun handleHostClient(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        var remoteSenderId = "lan_guest_${socket.port}"
        try {
            while (scope.isActive && socket.isConnected && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val sid = obj.optString("senderId", "").ifBlank { remoteSenderId }
                remoteSenderId = sid

                onPacketReceived(sid, line)
                if (obj.optString("type") != "hello") {
                    broadcastExcept(line, socket)
                }
            }
        } catch (_: Throwable) {
        } finally {
            hostClientSockets.remove(socket)
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            onPeerDisconnected(remoteSenderId)
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
                discoveredIps.add(targetInput.substringBefore(":"))
            }
            discoveredIps.add("192.168.43.1")
            discoveredIps.add("10.0.2.2")
            discoveredIps.add("127.0.0.1")

            val beaconSocket = try {
                DatagramSocket(8989).apply {
                    soTimeout = 1000
                    broadcast = true
                }
            } catch (_: Throwable) {
                null
            }

            val startTime = System.currentTimeMillis()

            // UDP listener for local Wi-Fi / Hotspot beacons
            val udpListenerJob = launch {
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
                                packet.address?.hostAddress?.let { discoveredIps.add(it) }
                            }
                        } catch (_: Throwable) {
                        }
                    } else {
                        delay(400)
                    }
                }
            }

            // Only scan the device's real active IPv4 subnets (no blind 1200-coroutine flood)
            val activeSubnets = getActiveSubnetPrefixes()

            while (isActive && !connected && (System.currentTimeMillis() - startTime < 15000)) {
                val snapshot = discoveredIps.toList()
                for (ip in snapshot) {
                    if (connected) break
                    val connResult = tryConnectIp(ip)
                    if (connResult != null) {
                        val (sock, reader) = connResult
                        clientSocket = sock
                        connected = true
                        onStatus("Connected to Room $currentRoomCode (LAN)", true)
                        clientJob = launch { handleClientSession(sock, reader) }
                        break
                    }
                }

                if (!connected) {
                    for (prefix in activeSubnets) {
                        if (connected) break
                        val jobs = (1..254).map { lastOctet ->
                            async {
                                if (connected) return@async null
                                tryConnectIp("$prefix$lastOctet")
                            }
                        }
                        for (j in jobs) {
                            val res = j.await()
                            if (res != null && !connected) {
                                val (sock, reader) = res
                                clientSocket = sock
                                connected = true
                                onStatus("Connected to Room $currentRoomCode (LAN)", true)
                                clientJob = launch { handleClientSession(sock, reader) }
                                break
                            }
                        }
                    }
                }

                delay(700)
            }

            udpListenerJob.cancel()
            try {
                beaconSocket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun tryConnectIp(ip: String): Pair<Socket, BufferedReader>? = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, 8988), 320)
            val out = PrintWriter(OutputStreamWriter(s.getOutputStream()), true)
            val hello = JSONObject().apply {
                put("type", "hello")
                put("senderId", localPeerId)
                put("name", myName)
                put("isHost", false)
                put("roomCode", currentRoomCode)
                put("pktId", "${localPeerId}_lan_hello")
            }.toString()
            out.println(hello)

            s.soTimeout = 900
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val respLine = reader.readLine()
            if (respLine == null) {
                try { s.close() } catch (_: Throwable) {}
                return@withContext null
            }
            val obj = JSONObject(respLine)
            val respType = obj.optString("type")
            val respCode = obj.optString("roomCode")

            if ((respType == "hello_reply" || respType == "lobby_sync") &&
                (currentRoomCode.contains(".") || respCode.equals(currentRoomCode, ignoreCase = true))
            ) {
                s.soTimeout = 0
                val sid = obj.optString("senderId", "lan_host")
                onPacketReceived(sid, respLine)
                Pair(s, reader)
            } else {
                try { s.close() } catch (_: Throwable) {}
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getActiveSubnetPrefixes(): List<String> {
        val prefixes = mutableSetOf<String>()
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
                            prefixes.add(host.substring(0, lastDot + 1))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return prefixes.toList()
    }

    private suspend fun handleClientSession(socket: Socket, reader: BufferedReader) {
        var hostSenderId = "lan_host"
        try {
            while (scope.isActive && socket.isConnected && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val sid = obj.optString("senderId", "").ifBlank { hostSenderId }
                hostSenderId = sid
                onPacketReceived(sid, line)
            }
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            onPeerDisconnected(hostSenderId)
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
            synchronized(s) {
                if (s.isConnected && !s.isClosed) {
                    val out = PrintWriter(OutputStreamWriter(s.getOutputStream()), true)
                    out.println(data)
                    true
                } else {
                    false
                }
            }
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
    } catch (_: Throwable) {
    }
    return "127.0.0.1"
}

/**
 * Unified P2P + Cloud Relay + LAN Networking Controller.
 * Combines:
 * 1) Pure-Kotlin MQTT Cloud Relay (works across emulators, mobile CGNAT, and global internet)
 * 2) Local Network LAN/Hotspot Sockets (works offline with zero latency)
 * 3) WebRTC / PeerJS Bridge (handles proximity voice audio + backup data channel)
 */
class PeerBridgeController(
    private val context: Context,
    private val listener: PeerEventsListener
) {
    val localPeerId: String = "p_" + UUID.randomUUID().toString().replace("-", "").take(10)

    private var webView: WebView? = null
    private var isPageReady = false
    private val pendingScripts = mutableListOf<String>()
    private val seenPacketIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private var localPacketSeq = 1L

    @Volatile
    private var isHostingMode = false

    @Volatile
    private var activeRoomCode = ""

    @Volatile
    private var activePlayerName = "Player"

    @Volatile
    private var hasPrimaryConnection = false

    @Volatile
    private var nativeMicEnabled = true

    @Volatile
    private var nativeSpeakerMuted = false

    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val remotePeerGains = Collections.synchronizedMap(mutableMapOf<String, Float>())
    private val remoteAudioTracks = Collections.synchronizedMap(mutableMapOf<String, AudioTrack>())
    private var voiceSeq = 1L

    private val lanManager = BouChorLanManager(
        localPeerId = localPeerId,
        onStatus = { msg, ok -> updateTransportStatus("LAN", msg, ok) },
        onPacketReceived = { sid, payload -> handleUnifiedPacket(sid, payload) },
        onPeerDisconnected = { sid ->
            if (!hasPrimaryConnection) {
                listener.onRemotePeerLeft(sid)
            }
        }
    )

    private val relayManager = BouChorMqttRelayManager(
        localPeerId = localPeerId,
        onStatus = { msg, ok -> updateTransportStatus("RELAY", msg, ok) },
        onPacketReceived = { sid, payload -> handleUnifiedPacket(sid, payload) }
    )

    private val peerJsSource: String by lazy {
        try {
            context.assets.open("peerjs.min.js").bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            ""
        }
    }

    fun getBridgeHtml(): String = buildBridgeHtml(peerJsSource, localPeerId)

    fun attachWebView(view: WebView) {
        webView = view
    }

    fun onPageReady() {
        if (isPageReady) return
        isPageReady = true
        pendingScripts.forEach { evalJs(it) }
        pendingScripts.clear()
    }

    fun createRoom(roomCode: String, playerName: String) {
        seenPacketIds.clear()
        isHostingMode = true
        activeRoomCode = roomCode.trim().uppercase()
        activePlayerName = playerName
        hasPrimaryConnection = false

        lanManager.startHosting(activeRoomCode, playerName)
        relayManager.startHosting(activeRoomCode, playerName)

        val safeName = JSONObject.quote(playerName)
        val safeCode = JSONObject.quote(activeRoomCode)
        evalJs("window.BouChorNet && window.BouChorNet.createRoom($safeCode, $safeName);")
    }

    fun joinRoom(roomCode: String, playerName: String) {
        seenPacketIds.clear()
        isHostingMode = false
        activeRoomCode = roomCode.trim().uppercase()
        activePlayerName = playerName
        hasPrimaryConnection = false

        lanManager.startJoining(activeRoomCode, playerName)
        relayManager.startJoining(activeRoomCode, playerName)

        val safeName = JSONObject.quote(playerName)
        val safeCode = JSONObject.quote(activeRoomCode)
        evalJs("window.BouChorNet && window.BouChorNet.joinRoom($safeCode, $safeName);")
    }

    fun broadcastJson(json: String) {
        val enriched = try {
            val obj = JSONObject(json)
            if (!obj.has("senderId")) obj.put("senderId", localPeerId)
            if (!obj.has("roomCode") && activeRoomCode.isNotEmpty()) obj.put("roomCode", activeRoomCode)
            if (obj.optString("type") != "pos" && !obj.has("pktId")) {
                obj.put("pktId", "${localPeerId}_${localPacketSeq++}")
            }
            obj.toString()
        } catch (_: Throwable) {
            json
        }

        lanManager.broadcast(enriched)
        relayManager.publish(enriched)
        val quoted = JSONObject.quote(enriched)
        evalJs("window.BouChorNet && window.BouChorNet.broadcast($quoted);")
    }

    fun broadcastVoiceFrame(base64MuLaw: String) {
        if (!nativeMicEnabled || activeRoomCode.isEmpty()) return
        val pkt = JSONObject().apply {
            put("type", "v")
            put("senderId", localPeerId)
            put("roomCode", activeRoomCode)
            put("pktId", "${localPeerId}_v_${voiceSeq++}")
            put("d", base64MuLaw)
        }.toString()
        lanManager.broadcast(pkt)
        relayManager.publish(pkt)
        val quoted = JSONObject.quote(pkt)
        evalJs("window.BouChorNet && window.BouChorNet.broadcast($quoted);")
    }

    fun setAudioControls(micOn: Boolean, speakerMuted: Boolean) {
        nativeMicEnabled = micOn
        nativeSpeakerMuted = speakerMuted
        evalJs("window.BouChorNet && window.BouChorNet.setAudio($micOn, $speakerMuted);")
    }

    fun updatePeerGain(peerId: String, gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        remotePeerGains[peerId] = clamped
        val safePeer = JSONObject.quote(peerId)
        evalJs("window.BouChorNet && window.BouChorNet.setPeerGain($safePeer, $clamped);")
    }

    private fun releaseRemoteTrack(peerId: String) {
        remotePeerGains.remove(peerId)
        val tr = remoteAudioTracks.remove(peerId) ?: return
        runCatching { tr.stop() }
        runCatching { tr.release() }
    }

    private fun releaseAllRemoteTracks() {
        remotePeerGains.clear()
        val snapshot = synchronized(remoteAudioTracks) {
            val list = remoteAudioTracks.values.toList()
            remoteAudioTracks.clear()
            list
        }
        snapshot.forEach { tr ->
            runCatching { tr.stop() }
            runCatching { tr.release() }
        }
    }

    private fun handleIncomingVoiceFrame(senderId: String, base64MuLaw: String) {
        if (base64MuLaw.isEmpty()) return
        voiceScope.launch {
            try {
                val uLaw = Base64.decode(base64MuLaw, Base64.DEFAULT)
                if (uLaw.isEmpty()) return@launch
                val pcm = decodeMuLawFrame(uLaw)
                var sumSq = 0.0
                for (i in pcm.indices) {
                    val n = pcm[i] / 32768.0
                    sumSq += n * n
                }
                val rms = sqrt(sumSq / pcm.size).toFloat()
                val level = (rms * 10.5f).coerceIn(0f, 1f)
                listener.onRemoteVoiceLevel(senderId, level)

                val effectiveGain = if (nativeSpeakerMuted) 0f else (remotePeerGains[senderId] ?: 1f)
                if (effectiveGain <= 0.01f) return@launch

                for (i in pcm.indices) {
                    val scaled = (pcm[i] * effectiveGain).toInt().coerceIn(-32768, 32767)
                    pcm[i] = scaled.toShort()
                }

                val track = remoteAudioTracks[senderId] ?: synchronized(remoteAudioTracks) {
                    remoteAudioTracks[senderId] ?: createVoiceAudioTrack().also {
                        if (it != null) remoteAudioTracks[senderId] = it
                    }
                } ?: return@launch

                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { track.play() }
                }
                track.write(pcm, 0, pcm.size)
            } catch (_: Throwable) {
            }
        }
    }

    private fun createVoiceAudioTrack(): AudioTrack? {
        return runCatching {
            val sampleRate = 8000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return null
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, 3200))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
    }

    fun disconnect() {
        if (activeRoomCode.isNotEmpty()) {
            val bye = JSONObject().apply {
                put("type", "bye")
                put("senderId", localPeerId)
                put("name", activePlayerName)
                put("roomCode", activeRoomCode)
            }.toString()
            lanManager.broadcast(bye)
            relayManager.publish(bye)
        }
        hasPrimaryConnection = false
        activeRoomCode = ""
        releaseAllRemoteTracks()
        lanManager.stopAll()
        relayManager.stopAll()
        evalJs("window.BouChorNet && window.BouChorNet.disconnect();")
    }

    private fun updateTransportStatus(source: String, status: String, isConnected: Boolean) {
        if (isConnected) {
            hasPrimaryConnection = true
            listener.onPeerStatus(status, true)
        } else if (!hasPrimaryConnection || source != "WEBRTC") {
            listener.onPeerStatus(status, hasPrimaryConnection)
        }
    }

    private fun handleUnifiedPacket(fallbackPeerId: String, rawPayload: String) {
        val obj = runCatching { JSONObject(rawPayload) }.getOrNull() ?: return
        val type = obj.optString("type", "")
        val senderId = obj.optString("senderId", "").ifBlank { fallbackPeerId }
        if (senderId == localPeerId) return

        val pktId = obj.optString("pktId", "")
        if (pktId.isNotEmpty() && type != "pos") {
            synchronized(seenPacketIds) {
                if (seenPacketIds.contains(pktId)) return
                seenPacketIds.add(pktId)
                if (seenPacketIds.size > 256) {
                    val iter = seenPacketIds.iterator()
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
            }
        }

        when (type) {
            "hello" -> {
                val guestName = obj.optString("name", "Guest")
                if (isHostingMode) {
                    val reply = JSONObject().apply {
                        put("type", "hello_reply")
                        put("senderId", localPeerId)
                        put("name", activePlayerName)
                        put("isHost", true)
                        put("roomCode", activeRoomCode)
                        put("pktId", "${localPeerId}_reply_${System.currentTimeMillis()}")
                    }.toString()
                    lanManager.broadcast(reply)
                    relayManager.publish(reply)
                    val quotedReply = JSONObject.quote(reply)
                    evalJs("window.BouChorNet && window.BouChorNet.broadcast($quotedReply);")
                }
                listener.onRemotePeerJoined(senderId, guestName)
            }

            "hello_reply" -> {
                val hostName = obj.optString("name", "Host")
                relayManager.markGuestHandshakeDone()
                hasPrimaryConnection = true
                listener.onPeerStatus("Connected to Room $activeRoomCode!", true)
                listener.onRemotePeerJoined(senderId, hostName)
            }

            "lobby_sync" -> {
                if (!isHostingMode) {
                    relayManager.markGuestHandshakeDone()
                    hasPrimaryConnection = true
                }
                listener.onRemotePacket(senderId, rawPayload)
            }

            "v" -> {
                handleIncomingVoiceFrame(senderId, obj.optString("d", ""))
            }

            "bye" -> {
                releaseRemoteTrack(senderId)
                listener.onRemotePeerLeft(senderId)
            }

            else -> {
                listener.onRemotePacket(senderId, rawPayload)
            }
        }
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
        fun onBridgeReady() {
            onPageReady()
        }

        @JavascriptInterface
        fun onStatus(status: String, connected: Boolean) {
            updateTransportStatus("WEBRTC", status, connected)
        }

        @JavascriptInterface
        fun onPeerJoined(peerId: String, name: String) {
            val wrapped = JSONObject().apply {
                put("type", "hello")
                put("senderId", peerId)
                put("name", name)
            }.toString()
            handleUnifiedPacket(peerId, wrapped)
        }

        @JavascriptInterface
        fun onPeerLeft(peerId: String) {
            if (!hasPrimaryConnection) {
                listener.onRemotePeerLeft(peerId)
            }
        }

        @JavascriptInterface
        fun onPacket(peerId: String, payload: String) {
            handleUnifiedPacket(peerId, payload)
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

                    override fun onPageFinished(view: WebView?, url: String?) {
                        controller.onPageReady()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        val hasRuntimeMic = ContextCompat.checkSelfPermission(
                            ctx,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (wantsAudio && (micPermissionGranted || hasRuntimeMic)) {
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
                postDelayed({ controller.onPageReady() }, 500)
            }
        }
    )
}

private fun buildBridgeHtml(peerJsContent: String, localPeerId: String): String = """
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
  const LOCAL_PEER_ID = "$localPeerId";
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
    debug: 0,
    config: {
      iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
        { urls: 'stun:stun2.l.google.com:19302' },
        { urls: 'stun:stun.cloudflare.com:3478' },
        { urls: 'stun:stun.services.mozilla.com' }
      ],
      iceCandidatePoolSize: 4
    }
  };

  function notifyStatus(msg, ok) {
    if (window.AndroidBridge) window.AndroidBridge.onStatus(msg, !!ok);
  }

  async function ensureAudioStream() {
    if (localStream) return localStream;
    try {
      if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        const mediaPromise = navigator.mediaDevices.getUserMedia({ audio: true, video: false }).then(s => {
          localStream = s;
          localStream.getAudioTracks().forEach(t => t.enabled = micEnabled);
          return s;
        }).catch(() => null);
        const timeoutPromise = new Promise(resolve => setTimeout(() => resolve(null), 1500));
        await Promise.race([mediaPromise, timeoutPromise]);
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
      notifyStatus(isOutgoing ? ('Connected to Room ' + myRoomCode + ' (P2P)') : 'Player joined!', true);
      try {
        conn.send(JSON.stringify({
          type: 'hello',
          senderId: LOCAL_PEER_ID,
          name: myName,
          isHost: !isOutgoing,
          roomCode: myRoomCode,
          pktId: LOCAL_PEER_ID + '_rtc_hello_' + Date.now()
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
        const str = typeof raw === 'string' ? raw : JSON.stringify(raw);
        if (window.AndroidBridge) {
          window.AndroidBridge.onPacket(conn.peer, str);
        }
      } catch (e) {}
    });

    conn.on('close', () => {
      delete dataConns[conn.peer];
      delete peerGains[conn.peer];
      if (window.AndroidBridge) window.AndroidBridge.onPeerLeft(conn.peer);
    });

    conn.on('error', () => {});
  }

  window.BouChorNet = {
    createRoom: function(code, name) {
      this.disconnect();
      myName = name || 'Host';
      myRoomCode = (code || '').toUpperCase();
      if (!myRoomCode || myRoomCode.indexOf('.') >= 0 || typeof Peer === 'undefined') return;
      try {
        const hostPeerId = 'bouchor-room-' + myRoomCode;
        peer = new Peer(hostPeerId, PEER_CONFIG);
        peer.on('open', () => {
          notifyStatus('Room ' + myRoomCode + ' Live (Relay + WebRTC)', true);
        });
        peer.on('connection', (conn) => {
          wireDataConn(conn, false);
        });
        peer.on('error', () => {});
      } catch (e) {}
    },

    joinRoom: function(code, name) {
      this.disconnect();
      myName = name || 'Guest';
      myRoomCode = (code || '').toUpperCase();
      if (!myRoomCode || myRoomCode.indexOf('.') >= 0 || typeof Peer === 'undefined') return;
      try {
        peer = new Peer(PEER_CONFIG);
        peer.on('open', () => {
          const hostId = 'bouchor-room-' + myRoomCode;
          const conn = peer.connect(hostId, { reliable: true, serialization: 'json' });
          wireDataConn(conn, true);
        });
        peer.on('error', () => {});
      } catch (e) {}
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

  if (window.AndroidBridge && window.AndroidBridge.onBridgeReady) {
    window.AndroidBridge.onBridgeReady();
  }
})();
</script>
</body>
</html>
"""
