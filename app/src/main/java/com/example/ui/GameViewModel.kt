package com.example.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.SoundEngine
import com.example.data.BouChorDatabase
import com.example.data.LeaderboardEntry
import com.example.data.LeaderboardRepository
import com.example.game.ChatChannel
import com.example.game.ChatMessage
import com.example.game.GameEngine
import com.example.game.GameWorldState
import com.example.game.GraphicsQuality
import com.example.game.HoldActionType
import com.example.game.LobbyPlayer
import com.example.game.Role
import com.example.game.ScreenMode
import com.example.game.Team
import com.example.game.Vec3
import com.example.game.WeatherType
import com.example.network.NativeMicMonitor
import com.example.network.PeerBridgeController
import com.example.network.PeerEventsListener
import com.example.network.getPrimaryLocalIpAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

data class BouChorUiState(
    val screen: ScreenMode = ScreenMode.Title,
    val playerName: String = "Player",
    val roomCode: String = "",
    val isHost: Boolean = true,
    val isSoloMode: Boolean = true,
    val preferredRole: Role? = null,
    val lobbyPlayers: List<LobbyPlayer> = emptyList(),
    val world: GameWorldState = GameWorldState(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val activeChatChannel: ChatChannel = ChatChannel.ALL,
    val isChatExpanded: Boolean = false,
    val isMicEnabled: Boolean = true,
    val isSpeakerMuted: Boolean = false,
    val isTeamRadioEnabled: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val localMicLevel: Float = 0f,
    val hostIpAddress: String? = null,
    val peerStatusText: String = "Zero-Backend P2P WebRTC Ready",
    val isPeerConnected: Boolean = false,
    val graphicsQuality: GraphicsQuality = GraphicsQuality.SMOOTH,
    val currentFps: Int = 60,
    val leaderboardEntries: List<LeaderboardEntry> = emptyList(),
    val lastMatchRecordedBouEscape: Boolean = false,
    val lastMatchRecordedChorCatches: Int = 0,
    val toastMessage: String? = null
)

class GameViewModel(application: Application) : AndroidViewModel(application), PeerEventsListener {

    private val prefs = application.getSharedPreferences("bouchor_prefs", Context.MODE_PRIVATE)
    private val leaderboardRepository = LeaderboardRepository(
        BouChorDatabase.getInstance(application).leaderboardDao()
    )
    private val soundEngine = SoundEngine(application)
    val micMonitor = NativeMicMonitor(application)
    val peerController = PeerBridgeController(application, this)

    private val _uiState = MutableStateFlow(
        BouChorUiState(
            playerName = prefs.getString("last_player_name", "DhakaKing")?.ifBlank { "DhakaKing" } ?: "DhakaKing",
            micPermissionGranted = micMonitor.hasPermission()
        )
    )
    val uiState: StateFlow<BouChorUiState> = _uiState.asStateFlow()

    // Decoupled 60 FPS Render State: read directly inside ThreeDGameViewport Canvas draw phase
    // while Compose HUD state (_uiState) updates at ~10 Hz to eliminate UI re-render lag!
    @Volatile
    var latestWorld: GameWorldState = GameWorldState()
        private set

    val renderFrameTick = mutableLongStateOf(0L)

    private val gameEngine = GameEngine(
        soundEngine = soundEngine,
        onBotChat = { sender, text, channel ->
            appendChat(sender.id, sender.name, sender.role, text, channel, playSound = true)
        },
        onMatchFinished = { result ->
            if (_uiState.value.screen !is ScreenMode.Playing) return@GameEngine
            stopMatchLoop()
            val localPlayer = latestWorld.localPlayer
            val activeName = LeaderboardRepository.normalizeName(localPlayer?.name ?: _uiState.value.playerName)
            val bouEscaped = localPlayer?.role == Role.BOU && result.winningTeam == Team.WEDDING
            viewModelScope.launch {
                leaderboardRepository.recordMatchCompleted(activeName, bouEscaped)
            }
            _uiState.update {
                it.copy(
                    screen = ScreenMode.MatchEnd(result),
                    lastMatchRecordedBouEscape = bouEscaped
                )
            }
        },
        onLocalChorCaughtBou = {
            val activeName = LeaderboardRepository.normalizeName(
                latestWorld.localPlayer?.name ?: _uiState.value.playerName
            )
            _uiState.update {
                it.copy(lastMatchRecordedChorCatches = it.lastMatchRecordedChorCatches + 1)
            }
            viewModelScope.launch {
                leaderboardRepository.recordChorCatch(activeName)
            }
            showToast("+1 Chor Catch saved to Local Leaderboard!")
        },
        onGameplayEvent = { _, payloadJson ->
            if (!_uiState.value.isSoloMode) {
                peerController.broadcastJson(payloadJson)
            }
        }
    )

    // Real-time lock-free input registers for simultaneous multi-touch joystick + 360° camera orbit + buttons
    @Volatile private var joyX = 0f
    @Volatile private var joyZ = 0f
    @Volatile private var keyX = 0f
    @Volatile private var keyZ = 0f
    @Volatile private var targetCamYaw = 0f
    @Volatile private var targetCamPitch = 0.54f
    @Volatile private var targetCamDistance = 13.4f
    @Volatile private var pendingAbility1Press = false
    @Volatile private var ability1Held = false
    @Volatile private var pendingAbility2Press = false
    @Volatile private var pendingAbility3Press = false

    private var gameLoopJob: Job? = null
    private var nextChatId = 1L
    private var netBroadcastCounter = 0
    @Volatile private var lastCarryEventMs = 0L

    init {
        micMonitor.onEncodedVoiceFrame = { encodedFrame, sampleRate ->
            val st = _uiState.value
            if (!st.isSoloMode &&
                st.isMicEnabled &&
                st.micPermissionGranted &&
                (st.isPeerConnected || st.lobbyPlayers.any { !it.isLocal && !it.isBot })
            ) {
                peerController.broadcastVoiceFrame(encodedFrame, sampleRate)
            }
        }
        viewModelScope.launch {
            micMonitor.micLevel.collect { level ->
                _uiState.update { st ->
                    val updatedLobby = st.lobbyPlayers.map { lp ->
                        if (lp.isLocal) lp.copy(speakingLevel = if (st.isMicEnabled) level else 0f) else lp
                    }
                    st.copy(
                        localMicLevel = if (st.isMicEnabled) level else 0f,
                        lobbyPlayers = updatedLobby
                    )
                }
            }
        }
        viewModelScope.launch {
            leaderboardRepository.leaderboard.collect { entries ->
                _uiState.update { st -> st.copy(leaderboardEntries = entries) }
            }
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(micPermissionGranted = granted) }
        peerController.setAudioControls(_uiState.value.isMicEnabled, _uiState.value.isSpeakerMuted)
        if (granted && _uiState.value.isMicEnabled) {
            micMonitor.start()
        }
    }

    fun updatePlayerName(name: String) {
        val trimmed = name.take(16)
        _uiState.update { it.copy(playerName = trimmed) }
        prefs.edit().putString("last_player_name", trimmed).apply()
    }

    fun clearLocalLeaderboard() {
        viewModelScope.launch {
            leaderboardRepository.clearLeaderboard()
        }
        showToast("Local leaderboard cleared")
    }

    fun selectPreferredRole(role: Role?) {
        _uiState.update { it.copy(preferredRole = role) }
    }

    fun createMultiplayerRoom() {
        val code = generateRoomCode()
        val name = _uiState.value.playerName.ifBlank { "Host" }
        val localIp = getPrimaryLocalIpAddress()
        val localPlayer = LobbyPlayer(
            id = peerController.localPeerId,
            name = name,
            isHost = true,
            isLocal = true,
            micEnabled = _uiState.value.isMicEnabled
        )
        _uiState.update {
            it.copy(
                screen = ScreenMode.Lobby,
                roomCode = code,
                hostIpAddress = localIp,
                isHost = true,
                isSoloMode = false,
                isPeerConnected = false,
                peerStatusText = "Opening Room $code...",
                lobbyPlayers = listOf(localPlayer),
                chatMessages = listOf(
                    ChatMessage(nextChatId++, "sys", "Biye Bari", null, "Room $code created! Share code $code (or IP: $localIp).", ChatChannel.ALL)
                )
            )
        }
        peerController.setAudioControls(_uiState.value.isMicEnabled, _uiState.value.isSpeakerMuted)
        if (_uiState.value.micPermissionGranted && _uiState.value.isMicEnabled) micMonitor.start()
        peerController.createRoom(code, name)
    }

    fun joinMultiplayerRoom(rawCode: String) {
        val input = rawCode.trim().uppercase()
        if (input.isBlank()) {
            showToast("Enter a Room Code or Host IP!")
            return
        }
        val name = _uiState.value.playerName.ifBlank { "Guest" }
        val localPlayer = LobbyPlayer(
            id = peerController.localPeerId,
            name = name,
            isHost = false,
            isLocal = true,
            micEnabled = _uiState.value.isMicEnabled
        )
        _uiState.update {
            it.copy(
                screen = ScreenMode.Lobby,
                roomCode = input,
                isHost = false,
                isSoloMode = false,
                isPeerConnected = false,
                peerStatusText = "Connecting to Room $input...",
                lobbyPlayers = listOf(localPlayer),
                chatMessages = listOf(
                    ChatMessage(nextChatId++, "sys", "Biye Bari", null, "Connecting to Room $input via Cloud Relay / LAN...", ChatChannel.ALL)
                )
            )
        }
        peerController.setAudioControls(_uiState.value.isMicEnabled, _uiState.value.isSpeakerMuted)
        if (_uiState.value.micPermissionGranted && _uiState.value.isMicEnabled) micMonitor.start()
        peerController.joinRoom(input, name)
    }

    fun startSoloMatch(preferredRole: Role? = null) {
        val name = _uiState.value.playerName.ifBlank { "Player" }
        val localLobby = LobbyPlayer(
            id = peerController.localPeerId,
            name = name,
            isHost = true,
            isLocal = true,
            micEnabled = _uiState.value.isMicEnabled
        )
        _uiState.update {
            it.copy(
                isSoloMode = true,
                isHost = true,
                roomCode = "SOLO",
                preferredRole = preferredRole,
                lobbyPlayers = listOf(localLobby),
                chatMessages = emptyList()
            )
        }
        launchMatchInternal(preferredRole)
    }

    fun startMatchFromLobby() {
        val st = _uiState.value
        if (!st.isSoloMode) {
            val allRoles = Role.entries.toMutableList().apply { shuffle() }
            if (st.preferredRole != null) {
                allRoles.remove(st.preferredRole)
                allRoles.add(0, st.preferredRole)
            }
            val assignmentsArray = org.json.JSONArray()
            val roleMap = mutableMapOf<String, Role>()
            val humans = st.lobbyPlayers.take(5)
            humans.forEachIndexed { i, p ->
                val assignedRole = allRoles.getOrElse(i) { Role.entries[i % Role.entries.size] }
                roleMap[p.id] = assignedRole
                val assignObj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("role", assignedRole.name)
                }
                assignmentsArray.put(assignObj)
            }
            val botCount = (5 - humans.size).coerceAtLeast(0)
            for (bi in 0 until botCount) {
                val botId = "bot_$bi"
                val assignedRole = allRoles.getOrElse(humans.size + bi) { Role.entries[(humans.size + bi) % Role.entries.size] }
                roleMap[botId] = assignedRole
                assignmentsArray.put(
                    JSONObject().apply {
                        put("id", botId)
                        put("name", botId)
                        put("role", assignedRole.name)
                    }
                )
            }
            gameEngine.setPresetMultiplayerRoles(roleMap)

            val startJson = JSONObject().apply {
                put("type", "start_match")
                put("roomCode", st.roomCode)
                put("assignments", assignmentsArray)
            }.toString()
            peerController.broadcastJson(startJson)
        }
        launchMatchInternal(st.preferredRole)
    }

    fun playAgainShuffleRoles() {
        _uiState.update { it.copy(preferredRole = null) }
        launchMatchInternal(null)
    }

    fun returnToLobbyOrTitle() {
        stopMatchLoop()
        val st = _uiState.value
        st.lobbyPlayers.filter { !it.isLocal && !it.isBot }.forEach {
            peerController.updatePeerGain(it.id, 1.0f)
        }
        if (st.isSoloMode) {
            _uiState.update { it.copy(screen = ScreenMode.Title) }
        } else {
            _uiState.update { it.copy(screen = ScreenMode.Lobby) }
        }
    }

    fun leaveToTitle() {
        stopMatchLoop()
        peerController.disconnect()
        micMonitor.stop()
        _uiState.update {
            it.copy(
                screen = ScreenMode.Title,
                roomCode = "",
                isPeerConnected = false,
                peerStatusText = "Online Relay + LAN Ready",
                lobbyPlayers = emptyList(),
                chatMessages = emptyList(),
                isTeamRadioEnabled = false
            )
        }
    }

    private fun launchMatchInternal(preferredRole: Role?) {
        stopMatchLoop()
        val st = _uiState.value
        val initialWorld = gameEngine.createInitialWorld(
            lobbyPlayers = st.lobbyPlayers,
            isSoloMode = st.isSoloMode,
            roomCode = st.roomCode,
            preferredLocalRole = preferredRole
        )
        joyX = 0f; joyZ = 0f; keyX = 0f; keyZ = 0f
        targetCamYaw = initialWorld.cameraYaw
        targetCamPitch = initialWorld.cameraPitch
        targetCamDistance = initialWorld.cameraDistance
        pendingAbility1Press = false; ability1Held = false; pendingAbility2Press = false; pendingAbility3Press = false

        latestWorld = initialWorld
        renderFrameTick.longValue = renderFrameTick.longValue + 1L

        _uiState.update {
            it.copy(
                screen = ScreenMode.Playing,
                world = initialWorld,
                isChatExpanded = false,
                isTeamRadioEnabled = false,
                lastMatchRecordedBouEscape = false,
                lastMatchRecordedChorCatches = 0
            )
        }
        peerController.setAudioControls(st.isMicEnabled, st.isSpeakerMuted)
        if (st.micPermissionGranted && st.isMicEnabled) micMonitor.start()
        soundEngine.playTaskComplete()
        startMatchLoop()
    }

    private fun startMatchLoop() {
        gameLoopJob?.cancel()
        gameLoopJob = viewModelScope.launch {
            var lastNs = System.nanoTime()
            var hudAccumSec = 0f
            var fpsFrames = 0
            var fpsAccumSec = 0f
            var liveFps = 60
            while (isActive) {
                val now = System.nanoTime()
                val rawDt = ((now - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.25f)
                val dt = rawDt.coerceIn(0.008f, 0.05f)
                lastNs = now

                fpsFrames++
                fpsAccumSec += rawDt
                if (fpsAccumSec >= 0.35f) {
                    liveFps = (fpsFrames / fpsAccumSec).toInt().coerceIn(1, 120)
                    fpsFrames = 0
                    fpsAccumSec = 0f
                }

                val st = _uiState.value
                if (st.screen !is ScreenMode.Playing) break

                val mx = (joyX + keyX).coerceIn(-1f, 1f)
                val mz = (joyZ + keyZ).coerceIn(-1f, 1f)
                val a1Press = pendingAbility1Press
                val a2Press = pendingAbility2Press
                val a3Press = pendingAbility3Press
                pendingAbility1Press = false
                pendingAbility2Press = false
                pendingAbility3Press = false

                val prevWorld = latestWorld
                val nextWorld = gameEngine.step(
                    world = prevWorld,
                    dt = dt,
                    moveInputX = mx,
                    moveInputZ = mz,
                    targetCameraYaw = targetCamYaw,
                    targetCameraPitch = targetCamPitch,
                    targetCameraDist = targetCamDistance,
                    ability1Pressed = a1Press,
                    ability1Held = ability1Held,
                    ability2Pressed = a2Press,
                    ability3Pressed = a3Press,
                    localMicLevel = st.localMicLevel,
                    allowRandomWeatherTransition = st.isSoloMode || st.isHost,
                    simulateBotsLocally = st.isSoloMode || st.isHost
                )

                // Direct 60 FPS Canvas draw-only invalidation (zero Compose HUD tree recomposition!)
                latestWorld = nextWorld
                renderFrameTick.longValue = renderFrameTick.longValue + 1L

                // Broadcast weather transition to connected peers when Host shifts outdoor weather
                if (!st.isSoloMode && st.isHost && prevWorld.weather != nextWorld.weather) {
                    val weatherJson = JSONObject().apply {
                        put("type", "weather")
                        put("weather", nextWorld.weather.name)
                    }.toString()
                    peerController.broadcastJson(weatherJson)
                }

                // Throttle HUD state updates (timer, minimap, cooldowns, FPS, weather) to ~10 Hz (0.1s)
                // or flush immediately on high-priority UX transitions (sack, blind, banner, weather, hold)
                hudAccumSec += dt
                val localPrev = prevWorld.localPlayer
                val localNext = nextWorld.localPlayer
                val urgentHudChange = (localPrev?.isCarriedInSack != localNext?.isCarriedInSack) ||
                    (localPrev?.isCarriedByBor != localNext?.isCarriedByBor) ||
                    ((localPrev?.blindTimer ?: 0f) > 0f != (localNext?.blindTimer ?: 0f) > 0f) ||
                    (prevWorld.bannerAlertText != nextWorld.bannerAlertText) ||
                    (prevWorld.weather != nextWorld.weather) ||
                    (localPrev?.holdType != localNext?.holdType) ||
                    (localNext?.holdType != HoldActionType.NONE && hudAccumSec >= 0.05f)

                if (hudAccumSec >= 0.10f || urgentHudChange) {
                    hudAccumSec = 0f
                    _uiState.update { it.copy(world = nextWorld, currentFps = liveFps) }
                }

                // Sync spatial proximity voice, P2P human transforms & Host bots/tasks
                netBroadcastCounter++
                if (!st.isSoloMode && netBroadcastCounter % 6 == 0) {
                    syncMultiplayerAndProximityVoice(nextWorld, st.isTeamRadioEnabled, st.isHost)
                }

                delay(16L)
            }
        }
    }

    private fun stopMatchLoop() {
        gameLoopJob?.cancel()
        gameLoopJob = null
    }

    private fun syncMultiplayerAndProximityVoice(world: GameWorldState, teamRadioOn: Boolean, isHost: Boolean) {
        val local = world.localPlayer ?: return
        val json = JSONObject().apply {
            put("type", "pos")
            put("senderId", peerController.localPeerId)
            put("x", local.pos.x.toDouble())
            put("z", local.pos.z.toDouble())
            put("yaw", local.yaw.toDouble())
            put("moving", local.isMoving)
            put("carrying", local.isCarryingBou)
            put("sack", local.isCarriedInSack)
            put("carriedByBor", local.isCarriedByBor)
            put("boost", local.speedBoostTimer > 0f)
            put("stun", local.stunTimer > 0f)
            put("slow", local.slowTimer > 0f)
            put("blind", local.blindTimer > 0f)
            put("slip", local.slipTimer > 0f)
        }.toString()
        peerController.broadcastJson(json)

        if (isHost && netBroadcastCounter % 12 == 0) {
            val botsArr = org.json.JSONArray()
            for (p in world.players) {
                if (p.isBot) {
                    botsArr.put(
                        JSONObject().apply {
                            put("id", p.id)
                            put("x", p.pos.x.toDouble())
                            put("z", p.pos.z.toDouble())
                            put("yaw", p.yaw.toDouble())
                            put("moving", p.isMoving)
                            put("carrying", p.isCarryingBou)
                            put("sack", p.isCarriedInSack)
                            put("carriedByBor", p.isCarriedByBor)
                            put("boost", p.speedBoostTimer > 0f)
                            put("stun", p.stunTimer > 0f)
                            put("slow", p.slowTimer > 0f)
                            put("slowUsed", p.mamaSlowUsed)
                        }
                    )
                }
            }
            val tasksArr = org.json.JSONArray()
            for (t in world.tasks) {
                tasksArr.put(
                    JSONObject().apply {
                        put("p", t.progress.toDouble())
                        put("c", t.completed)
                    }
                )
            }
            val syncWorldJson = JSONObject().apply {
                put("type", "world_sync")
                put("senderId", peerController.localPeerId)
                put("time", world.timeRemainingSec.toDouble())
                put("bots", botsArr)
                put("tasks", tasksArr)
            }.toString()
            peerController.broadcastJson(syncWorldJson)
        }

        world.players.filter { !it.isLocal && !it.isBot }.forEach { remote ->
            val bothOnTeamRadio = teamRadioOn && local.role.hasTeamRadio() && remote.role.hasTeamRadio()
            val gain = if (bothOnTeamRadio) {
                1.0f
            } else {
                val dist = local.pos.dist2D(remote.pos)
                (1f - (dist / 110f) * 0.45f).coerceIn(0.55f, 1.0f)
            }
            peerController.updatePeerGain(remote.id, gain)
        }
    }

    // Input Hooks (Multi-Touch Joystick + 360° Camera Orbit + Mouse Wheel Zoom + Keyboard)
    fun setJoystickVector(x: Float, z: Float) {
        joyX = x
        joyZ = z
    }

    fun setKeyboardVector(x: Float, z: Float) {
        keyX = x
        keyZ = z
    }

    fun orbitCameraBy(deltaYawRad: Float, deltaPitchRad: Float) {
        targetCamYaw += deltaYawRad
        targetCamPitch = (targetCamPitch + deltaPitchRad).coerceIn(0.262f, 1.309f) // 15°..75°
    }

    fun zoomCameraBy(deltaDistance: Float) {
        targetCamDistance = (targetCamDistance + deltaDistance).coerceIn(7.5f, 22.0f)
    }

    fun triggerAbility1Press() {
        pendingAbility1Press = true
    }

    fun setAbility1Held(held: Boolean) {
        if (held && !ability1Held) pendingAbility1Press = true
        ability1Held = held
    }

    fun triggerAbility2Press() {
        pendingAbility2Press = true
    }

    fun triggerAbility3Press() {
        pendingAbility3Press = true
    }

    // Voice & Audio Controls
    fun toggleMic() {
        val next = !_uiState.value.isMicEnabled
        _uiState.update { it.copy(isMicEnabled = next) }
        peerController.setAudioControls(next, _uiState.value.isSpeakerMuted)
        if (next && _uiState.value.micPermissionGranted) micMonitor.start() else micMonitor.stop()
    }

    fun toggleSpeakerMute() {
        val nextMuted = !_uiState.value.isSpeakerMuted
        soundEngine.isSpeakerMuted = nextMuted
        _uiState.update { it.copy(isSpeakerMuted = nextMuted) }
        peerController.setAudioControls(_uiState.value.isMicEnabled, nextMuted)
    }

    fun toggleTeamRadio() {
        val localRole = latestWorld.localPlayer?.role
        if (localRole != null && !localRole.hasTeamRadio()) {
            showToast("Team Radio is only for Bor, Mama & Photographer!")
            return
        }
        _uiState.update { it.copy(isTeamRadioEnabled = !it.isTeamRadioEnabled) }
        soundEngine.playChatPop()
    }

    fun toggleGraphicsQuality() {
        val next = if (_uiState.value.graphicsQuality == GraphicsQuality.SMOOTH) {
            GraphicsQuality.HD
        } else {
            GraphicsQuality.SMOOTH
        }
        _uiState.update { it.copy(graphicsQuality = next) }
        soundEngine.playChatPop()
        showToast(
            if (next == GraphicsQuality.SMOOTH) "Graphics: SMOOTH (Max FPS)"
            else "Graphics: HD (High Detail)"
        )
    }

    // Chat & 3D Floating Speech Bubbles
    fun toggleChatExpanded() {
        _uiState.update { it.copy(isChatExpanded = !it.isChatExpanded) }
    }

    fun setChatChannel(channel: ChatChannel) {
        _uiState.update { it.copy(activeChatChannel = channel) }
    }

    fun sendLocalChat(rawText: String, overrideChannel: ChatChannel? = null) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val st = _uiState.value
        val channel = overrideChannel ?: st.activeChatChannel
        val local = latestWorld.localPlayer
        val senderId = local?.id ?: peerController.localPeerId
        val senderName = local?.name ?: st.playerName
        val senderRole = local?.role

        appendChat(senderId, senderName, senderRole, text, channel, playSound = true)

        if (st.screen is ScreenMode.Playing) {
            val updatedPlayers = latestWorld.players.map { p ->
                if (p.isLocal) p.copy(speechBubbleText = text, speechBubbleTimer = 4.0f) else p
            }
            latestWorld = latestWorld.copy(players = updatedPlayers)
            _uiState.update { cur -> cur.copy(world = latestWorld) }
        }

        if (!st.isSoloMode) {
            val chatJson = JSONObject().apply {
                put("type", "chat")
                put("senderId", peerController.localPeerId)
                put("name", senderName)
                put("text", text)
                put("channel", channel.name)
            }.toString()
            peerController.broadcastJson(chatJson)
        }
    }

    private fun appendChat(
        senderId: String,
        senderName: String,
        senderRole: Role?,
        text: String,
        channel: ChatChannel,
        playSound: Boolean
    ) {
        if (playSound) soundEngine.playChatPop()
        val msg = ChatMessage(nextChatId++, senderId, senderName, senderRole, text, channel)
        _uiState.update { st -> st.copy(chatMessages = (st.chatMessages + msg).takeLast(35)) }
    }

    fun showToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
        viewModelScope.launch {
            delay(2200)
            _uiState.update { if (it.toastMessage == msg) it.copy(toastMessage = null) else it }
        }
    }

    // PeerJS WebRTC, Cloud Relay & LAN Callbacks
    override fun onPeerStatus(status: String, isConnected: Boolean) {
        _uiState.update { it.copy(peerStatusText = status, isPeerConnected = isConnected) }
    }

    override fun onRemotePeerJoined(peerId: String, name: String) {
        if (peerId == peerController.localPeerId) return
        val st = _uiState.value
        val weAreHost = st.isHost

        var addedNew = false
        _uiState.update { current ->
            if (current.lobbyPlayers.any { it.id == peerId }) {
                return@update current.copy(
                    isPeerConnected = true,
                    peerStatusText = "Room ${current.roomCode} Live (${current.lobbyPlayers.size}/5)"
                )
            }
            if (current.lobbyPlayers.size >= 5) return@update current

            addedNew = true
            val newPlayer = LobbyPlayer(
                id = peerId,
                name = name,
                isHost = !weAreHost,
                isLocal = false
            )
            val updated = current.lobbyPlayers + newPlayer
            current.copy(
                lobbyPlayers = updated,
                isPeerConnected = true,
                peerStatusText = "Room ${current.roomCode} Live (${updated.size}/5)"
            )
        }

        if (addedNew) {
            appendChat("sys", "Biye Bari", null, "$name joined the wedding!", ChatChannel.ALL, playSound = true)
        }

        if (weAreHost) {
            broadcastLobbySync()
        }
    }

    override fun onRemotePeerLeft(peerId: String) {
        val st = _uiState.value
        val leavingPlayer = st.lobbyPlayers.firstOrNull { it.id == peerId } ?: return
        val wasHost = leavingPlayer.isHost

        _uiState.update { current ->
            current.copy(lobbyPlayers = current.lobbyPlayers.filterNot { it.id == peerId })
        }
        appendChat("sys", "Biye Bari", null, "${leavingPlayer.name} left the room.", ChatChannel.ALL, playSound = false)

        if (wasHost && !st.isHost) {
            showToast("Host left the room!")
            leaveToTitle()
        } else if (st.isHost) {
            broadcastLobbySync()
        }
    }

    private fun broadcastLobbySync() {
        val st = _uiState.value
        val playersArray = org.json.JSONArray()
        for (p in st.lobbyPlayers) {
            val pObj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("isHost", p.isHost)
                put("micEnabled", p.micEnabled)
                put("speakingLevel", p.speakingLevel.toDouble())
            }
            playersArray.put(pObj)
        }
        val syncJson = JSONObject().apply {
            put("type", "lobby_sync")
            put("senderId", peerController.localPeerId)
            put("roomCode", st.roomCode)
            put("players", playersArray)
        }.toString()
        peerController.broadcastJson(syncJson)
    }

    override fun onRemotePacket(peerId: String, payloadJson: String) {
        try {
            val obj = JSONObject(payloadJson)
            val senderId = obj.optString("senderId", "").ifBlank { peerId }
            if (senderId == peerController.localPeerId) return

            when (obj.optString("type")) {
                "lobby_sync" -> {
                    val playersArray = obj.optJSONArray("players") ?: return
                    val myId = peerController.localPeerId
                    val myName = _uiState.value.playerName
                    val syncedList = mutableListOf<LobbyPlayer>()

                    for (i in 0 until playersArray.length()) {
                        val pObj = playersArray.getJSONObject(i)
                        val id = pObj.getString("id")
                        val name = pObj.getString("name")
                        val isHost = pObj.optBoolean("isHost", false)
                        val isLocal = (id == myId)
                        syncedList.add(
                            LobbyPlayer(
                                id = id,
                                name = name,
                                isHost = isHost,
                                isLocal = isLocal,
                                micEnabled = pObj.optBoolean("micEnabled", true),
                                speakingLevel = pObj.optDouble("speakingLevel", 0.0).toFloat()
                            )
                        )
                    }

                    val hasLocal = syncedList.any { it.isLocal }
                    val finalList = if (!hasLocal) {
                        syncedList + LobbyPlayer(myId, myName, isHost = false, isLocal = true, micEnabled = _uiState.value.isMicEnabled)
                    } else {
                        syncedList
                    }
                    _uiState.update {
                        it.copy(
                            lobbyPlayers = finalList,
                            isPeerConnected = true,
                            peerStatusText = "Room ${it.roomCode} Live (${finalList.size}/5)"
                        )
                    }
                }

                "start_match" -> {
                    val assignmentsArray = obj.optJSONArray("assignments")
                    if (assignmentsArray != null) {
                        val myId = peerController.localPeerId
                        val myName = _uiState.value.playerName
                        var myAssignedRole: Role? = null
                        val roleMap = mutableMapOf<String, Role>()
                        for (i in 0 until assignmentsArray.length()) {
                            val aObj = assignmentsArray.getJSONObject(i)
                            val pid = aObj.getString("id")
                            val pname = aObj.getString("name")
                            val rName = aObj.getString("role")
                            val r = runCatching { Role.valueOf(rName) }.getOrNull()
                            if (r != null) {
                                roleMap[pid] = r
                                if (pid == myId || (myAssignedRole == null && pname == myName && !pid.startsWith("p_"))) {
                                    myAssignedRole = r
                                }
                            }
                        }
                        gameEngine.setPresetMultiplayerRoles(roleMap)
                        if (_uiState.value.screen is ScreenMode.Lobby) {
                            launchMatchInternal(myAssignedRole)
                        }
                    } else {
                        if (_uiState.value.screen is ScreenMode.Lobby) {
                            launchMatchInternal(null)
                        }
                    }
                }

                "chat" -> {
                    val text = obj.optString("text")
                    val name = obj.optString("name", "Guest")
                    val ch = runCatching { ChatChannel.valueOf(obj.optString("channel", "ALL")) }.getOrDefault(ChatChannel.ALL)
                    appendChat(senderId, name, null, text, ch, playSound = true)
                    latestWorld = latestWorld.copy(
                        players = latestWorld.players.map { p ->
                            if (p.id == senderId || p.name == name) p.copy(speechBubbleText = text, speechBubbleTimer = 4.0f) else p
                        }
                    )
                }

                "pos" -> {
                    val x = obj.optDouble("x", 0.0).toFloat()
                    val z = obj.optDouble("z", 0.0).toFloat()
                    val yaw = obj.optDouble("yaw", 0.0).toFloat()
                    val moving = obj.optBoolean("moving", true)
                    val carrying = obj.optBoolean("carrying", false)
                    val sack = obj.optBoolean("sack", false)
                    val carriedByBor = obj.optBoolean("carriedByBor", false)
                    val boost = obj.optBoolean("boost", false)
                    val stun = obj.optBoolean("stun", false)
                    val slowed = obj.optBoolean("slow", false)
                    val blind = obj.optBoolean("blind", false)
                    val slip = obj.optBoolean("slip", false)
                    val allowCarryOverwrite = (System.currentTimeMillis() - lastCarryEventMs) > 900L
                    latestWorld = latestWorld.copy(
                        players = latestWorld.players.map { p ->
                            if (p.id == senderId) {
                                p.copy(
                                    pos = Vec3(x, 0f, z),
                                    yaw = yaw,
                                    isMoving = moving,
                                    walkPhase = if (moving) p.walkPhase + 0.35f else p.walkPhase,
                                    isCarryingBou = if (allowCarryOverwrite) carrying else p.isCarryingBou,
                                    isCarriedInSack = if (allowCarryOverwrite) sack else p.isCarriedInSack,
                                    isCarriedByBor = if (allowCarryOverwrite) carriedByBor else p.isCarriedByBor,
                                    speedBoostTimer = maxOf(p.speedBoostTimer, if (boost) 0.3f else 0f),
                                    stunTimer = maxOf(p.stunTimer, if (stun) 0.35f else 0f),
                                    slowTimer = maxOf(p.slowTimer, if (slowed) 0.35f else 0f),
                                    blindTimer = maxOf(p.blindTimer, if (blind) 0.35f else 0f),
                                    slipTimer = maxOf(p.slipTimer, if (slip) 0.35f else 0f)
                                )
                            } else p
                        }
                    )
                }

                "game_event" -> {
                    val ev = obj.optString("ev", "")
                    if (ev in setOf("kidnap_bou", "rescue_bou", "bor_pickup_bou", "bou_hop_off", "chor_hit", "banana_slip")) {
                        lastCarryEventMs = System.currentTimeMillis()
                    }
                    latestWorld = gameEngine.applyRemoteGameplayEvent(latestWorld, obj)
                }

                "world_sync" -> {
                    if (!_uiState.value.isHost) {
                        val timeLeft = obj.optDouble("time", latestWorld.timeRemainingSec.toDouble()).toFloat()
                        val botsArr = obj.optJSONArray("bots")
                        val botMap = mutableMapOf<String, JSONObject>()
                        if (botsArr != null) {
                            for (i in 0 until botsArr.length()) {
                                val bObj = botsArr.getJSONObject(i)
                                botMap[bObj.getString("id")] = bObj
                            }
                        }
                        val allowCarryOverwrite = (System.currentTimeMillis() - lastCarryEventMs) > 900L
                        val updatedPlayers = latestWorld.players.map { p ->
                            val b = if (p.isBot) botMap[p.id] else null
                            if (b != null) {
                                val moving = b.optBoolean("moving", false)
                                val stun = b.optBoolean("stun", false)
                                val slowed = b.optBoolean("slow", false)
                                val boost = b.optBoolean("boost", false)
                                p.copy(
                                    pos = Vec3(b.optDouble("x", p.pos.x.toDouble()).toFloat(), 0f, b.optDouble("z", p.pos.z.toDouble()).toFloat()),
                                    yaw = b.optDouble("yaw", p.yaw.toDouble()).toFloat(),
                                    isMoving = moving,
                                    walkPhase = if (moving) p.walkPhase + 0.35f else p.walkPhase,
                                    isCarryingBou = if (allowCarryOverwrite) b.optBoolean("carrying", p.isCarryingBou) else p.isCarryingBou,
                                    isCarriedInSack = if (allowCarryOverwrite) b.optBoolean("sack", p.isCarriedInSack) else p.isCarriedInSack,
                                    isCarriedByBor = if (allowCarryOverwrite) b.optBoolean("carriedByBor", p.isCarriedByBor) else p.isCarriedByBor,
                                    speedBoostTimer = maxOf(p.speedBoostTimer, if (boost) 0.35f else 0f),
                                    stunTimer = maxOf(p.stunTimer, if (stun) 0.35f else 0f),
                                    slowTimer = maxOf(p.slowTimer, if (slowed) 0.35f else 0f),
                                    mamaSlowUsed = p.mamaSlowUsed || b.optBoolean("slowUsed", false)
                                )
                            } else p
                        }
                        val tasksArr = obj.optJSONArray("tasks")
                        val updatedTasks = if (tasksArr != null && tasksArr.length() == latestWorld.tasks.size) {
                            latestWorld.tasks.mapIndexed { idx, t ->
                                val tObj = tasksArr.getJSONObject(idx)
                                val comp = t.completed || tObj.optBoolean("c", false)
                                t.copy(
                                    progress = if (comp) 1f else maxOf(t.progress, tObj.optDouble("p", t.progress.toDouble()).toFloat()),
                                    completed = comp
                                )
                            }
                        } else latestWorld.tasks

                        latestWorld = latestWorld.copy(
                            timeRemainingSec = timeLeft,
                            players = updatedPlayers,
                            tasks = updatedTasks
                        )
                    }
                }

                "mama_slow" -> {
                    soundEngine.playBananaSlip()
                    latestWorld = latestWorld.copy(
                        players = latestWorld.players.map { p ->
                            when (p.role) {
                                Role.MAMA -> p.copy(slowTimer = 5.0f)
                                Role.BOU_CHOR -> p.copy(mamaSlowUsed = true)
                                else -> p
                            }
                        },
                        bannerAlertText = "CHOR SLOWED MAMA FOR 5s! (1x)",
                        bannerAlertTimer = 3.2f
                    )
                }

                "weather" -> {
                    val w = runCatching { WeatherType.valueOf(obj.optString("weather")) }.getOrNull()
                    if (w != null && latestWorld.weather != w) {
                        soundEngine.playWeatherShift(
                            isRain = w == WeatherType.MONSOON_RAIN,
                            isFog = w == WeatherType.FOGGY_NIGHT
                        )
                        latestWorld = latestWorld.copy(
                            weather = w,
                            bannerAlertText = w.bannerMessage,
                            bannerAlertTimer = 3.2f
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onRemoteVoiceLevel(peerId: String, level: Float) {
        latestWorld = latestWorld.copy(
            players = latestWorld.players.map { if (it.id == peerId) it.copy(speakingLevel = level) else it }
        )
        _uiState.update { st ->
            st.copy(
                lobbyPlayers = st.lobbyPlayers.map { if (it.id == peerId) it.copy(speakingLevel = level) else it },
                world = latestWorld
            )
        }
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    override fun onCleared() {
        super.onCleared()
        stopMatchLoop()
        micMonitor.stop()
        peerController.disconnect()
    }
}
