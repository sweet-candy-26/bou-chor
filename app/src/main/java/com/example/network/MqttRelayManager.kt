package com.example.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Pure-Kotlin, zero-dependency MQTT v3.1.1 Cloud Relay client over TCP.
 * Enables instant room creation & joining across any NAT, mobile carrier CGNAT,
 * Wi-Fi router, or Android emulator (e.g. MSI App Player / BlueStacks).
 */
class BouChorMqttRelayManager(
    private val localPeerId: String,
    private val onStatus: (String, Boolean) -> Unit,
    private val onPacketReceived: (senderId: String, payloadJson: String) -> Unit
) {
    private val brokers = listOf(
        "broker.emqx.io" to 1883,
        "test.mosquitto.org" to 1883
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val publishDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val writeLock = Any()

    private var connectionJob: Job? = null
    private var helloRetryJob: Job? = null
    private var activeSocket: Socket? = null
    private var activeOut: OutputStream? = null

    @Volatile
    private var isSubscribed = false

    @Volatile
    private var isHosting = false

    @Volatile
    private var guestHandshakeDone = false

    @Volatile
    private var currentRoomCode = ""

    @Volatile
    private var currentPlayerName = "Player"

    private var packetCounter = 1

    private fun topicForRoom(code: String): String = "bouchor/v1/room/${code.trim().uppercase()}/msg"

    fun startHosting(roomCode: String, playerName: String) {
        stopAll()
        isHosting = true
        guestHandshakeDone = true
        currentRoomCode = roomCode.trim().uppercase()
        currentPlayerName = playerName
        if (currentRoomCode.contains(".")) return
        startConnectionLoop()
    }

    fun startJoining(roomCode: String, playerName: String) {
        stopAll()
        isHosting = false
        guestHandshakeDone = false
        currentRoomCode = roomCode.trim().uppercase()
        currentPlayerName = playerName
        if (currentRoomCode.contains(".")) return
        startConnectionLoop()
    }

    fun markGuestHandshakeDone() {
        guestHandshakeDone = true
        helloRetryJob?.cancel()
        helloRetryJob = null
    }

    fun publish(jsonPayload: String) {
        val code = currentRoomCode
        if (code.isBlank() || code.contains(".") || !isSubscribed) return
        val out = activeOut ?: return
        scope.launch(publishDispatcher) {
            sendPublish(out, topicForRoom(code), jsonPayload)
        }
    }

    fun stopAll() {
        helloRetryJob?.cancel()
        helloRetryJob = null
        connectionJob?.cancel()
        connectionJob = null
        isSubscribed = false
        guestHandshakeDone = false
        closeSocket()
    }

    private fun closeSocket() {
        synchronized(writeLock) {
            activeOut = null
            try {
                activeSocket?.close()
            } catch (_: Throwable) {
            }
            activeSocket = null
        }
    }

    private fun startConnectionLoop() {
        val code = currentRoomCode
        connectionJob = scope.launch {
            var brokerIdx = 0
            while (isActive && currentRoomCode == code) {
                val (host, port) = brokers[brokerIdx % brokers.size]
                brokerIdx++
                try {
                    connectAndRun(host, port, code)
                } catch (_: Throwable) {
                    isSubscribed = false
                    closeSocket()
                }
                if (isActive && currentRoomCode == code) {
                    delay(1500)
                }
            }
        }
    }

    private suspend fun connectAndRun(brokerHost: String, brokerPort: Int, roomCode: String) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(brokerHost, brokerPort), 4500)
        socket.soTimeout = 45000

        val out = socket.getOutputStream()
        val input = BufferedInputStream(socket.getInputStream(), 16384)
        synchronized(writeLock) {
            activeSocket = socket
            activeOut = out
        }

        val clientId = "bc_${localPeerId}_${Random.nextInt(1000, 9999)}"
        sendConnect(out, clientId)

        // Read CONNACK
        val connAckHeader = input.read()
        if (connAckHeader < 0 || (connAckHeader and 0xF0) != 0x20) {
            throw IOException("Expected CONNACK, got $connAckHeader")
        }
        val connAckLen = readVarInt(input)
        val connAckBody = readExact(input, connAckLen)
        if (connAckBody.size < 2 || connAckBody[1].toInt() != 0) {
            throw IOException("MQTT CONNACK rejected: ${connAckBody.getOrNull(1)}")
        }

        // Subscribe to room topic
        val topic = topicForRoom(roomCode)
        sendSubscribe(out, packetCounter++, topic)

        // Launch Keepalive ping loop
        val pingJob = scope.launch {
            while (isActive && activeSocket === socket) {
                delay(15000)
                try {
                    synchronized(writeLock) {
                        out.write(byteArrayOf(0xC0.toByte(), 0x00))
                        out.flush()
                    }
                } catch (_: Throwable) {
                    closeSocket()
                    break
                }
            }
        }

        try {
            while (scope.isActive && activeSocket === socket && !socket.isClosed) {
                val fixedHeader = input.read()
                if (fixedHeader < 0) break
                val remainingLength = readVarInt(input)
                val body = if (remainingLength > 0) readExact(input, remainingLength) else ByteArray(0)

                when (fixedHeader and 0xF0) {
                    0x90 -> {
                        // SUBACK
                        isSubscribed = true
                        if (isHosting) {
                            onStatus("Room $roomCode Live (Online Relay + LAN)", true)
                        } else if (!guestHandshakeDone) {
                            onStatus("Relay Ready — Joining Room $roomCode...", false)
                            startGuestHelloRetries(roomCode)
                        }
                    }

                    0x30 -> {
                        // PUBLISH
                        handlePublishFrame(fixedHeader, body)
                    }

                    0xD0 -> {
                        // PINGRESP - alive
                    }
                }
            }
        } finally {
            pingJob.cancel()
            isSubscribed = false
            closeSocket()
        }
    }

    private fun startGuestHelloRetries(roomCode: String) {
        helloRetryJob?.cancel()
        helloRetryJob = scope.launch {
            var attempt = 0
            while (isActive && !guestHandshakeDone && currentRoomCode == roomCode && isSubscribed) {
                attempt++
                val helloJson = JSONObject().apply {
                    put("type", "hello")
                    put("senderId", localPeerId)
                    put("name", currentPlayerName)
                    put("isHost", false)
                    put("roomCode", roomCode)
                    put("pktId", "${localPeerId}_hello_$attempt")
                }.toString()
                activeOut?.let { sendPublish(it, topicForRoom(roomCode), helloJson) }

                if (attempt == 6 && !guestHandshakeDone) {
                    onStatus("Waiting for Host in $roomCode... (Check code)", false)
                }
                delay(1300)
            }
        }
    }

    private fun handlePublishFrame(fixedHeader: Int, body: ByteArray) {
        if (body.size < 2) return
        val qos = (fixedHeader and 0x06) shr 1
        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        var offset = 2 + topicLen
        if (qos > 0) offset += 2
        if (offset > body.size) return

        val payloadStr = String(body, offset, body.size - offset, StandardCharsets.UTF_8)
        try {
            val obj = JSONObject(payloadStr)
            val senderId = obj.optString("senderId", "")
            if (senderId.isNotEmpty() && senderId == localPeerId) return
            val targetRoom = obj.optString("roomCode", "")
            if (targetRoom.isNotEmpty() && !targetRoom.equals(currentRoomCode, ignoreCase = true)) return
            onPacketReceived(senderId.ifBlank { "relay_peer" }, payloadStr)
        } catch (_: Throwable) {
        }
    }

    private fun sendConnect(out: OutputStream, clientId: String) {
        val cidBytes = clientId.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArrayOutputStream()
        // Variable Header: Protocol Name "MQTT" (level 4), Clean Session (0x02), KeepAlive 30s
        payload.write(byteArrayOf(0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 0x04, 0x02, 0x00, 0x1E))
        // Client ID
        payload.write((cidBytes.size shr 8) and 0xFF)
        payload.write(cidBytes.size and 0xFF)
        payload.write(cidBytes)

        val body = payload.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(0x10)
        writeVarInt(packet, body.size)
        packet.write(body)

        synchronized(writeLock) {
            out.write(packet.toByteArray())
            out.flush()
        }
    }

    private fun sendSubscribe(out: OutputStream, packetId: Int, topic: String) {
        val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArrayOutputStream()
        payload.write((packetId shr 8) and 0xFF)
        payload.write(packetId and 0xFF)
        payload.write((topicBytes.size shr 8) and 0xFF)
        payload.write(topicBytes.size and 0xFF)
        payload.write(topicBytes)
        payload.write(0x00) // QoS 0

        val body = payload.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(0x82)
        writeVarInt(packet, body.size)
        packet.write(body)

        synchronized(writeLock) {
            out.write(packet.toByteArray())
            out.flush()
        }
    }

    private fun sendPublish(out: OutputStream, topic: String, message: String) {
        try {
            val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
            val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
            val payload = ByteArrayOutputStream(2 + topicBytes.size + msgBytes.size)
            payload.write((topicBytes.size shr 8) and 0xFF)
            payload.write(topicBytes.size and 0xFF)
            payload.write(topicBytes)
            payload.write(msgBytes)

            val body = payload.toByteArray()
            val packet = ByteArrayOutputStream(5 + body.size)
            packet.write(0x30) // PUBLISH QoS 0
            writeVarInt(packet, body.size)
            packet.write(body)

            synchronized(writeLock) {
                out.write(packet.toByteArray())
                out.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var x = value
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 128
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun readVarInt(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        var count = 0
        do {
            val digit = input.read()
            if (digit < 0) throw EOFException("Stream closed")
            value += (digit and 127) * multiplier
            multiplier *= 128
            count++
            if (count > 4) throw IOException("Malformed MQTT Remaining Length")
        } while ((digit and 128) != 0)
        return value
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val read = input.read(buf, offset, len - offset)
            if (read < 0) throw EOFException("Stream closed before reading $len bytes")
            offset += read
        }
        return buf
    }
}
