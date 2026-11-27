package com.example.game

import androidx.compose.ui.graphics.Color
import com.example.audio.SoundEngine
import com.example.ui.theme.BorGold
import com.example.ui.theme.BridalCrimson
import com.example.ui.theme.CngEmerald
import com.example.ui.theme.FairyCyan
import com.example.ui.theme.MamaOrange
import com.example.ui.theme.MarigoldGold
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * High-precision 60fps simulation engine for BOU CHOR.
 * - Strictly camera-relative movement for 360-degree orbital camera
 * - Frame-rate independent exponential damping (damp) for smooth acceleration, turning & camera follow
 * - Spring-arm raycast camera wall collision
 * - 7-ray multi-ray forward obstacle-avoiding Smart AI bots
 * - Rhythmic procedural footsteps, sprint dust particles, and MVP stat tracking
 */
class GameEngine(
    private val soundEngine: SoundEngine,
    private val onBotChat: (sender: PlayerEntity, text: String, channel: ChatChannel) -> Unit,
    private val onMatchFinished: (MatchResult) -> Unit,
    private val onLocalChorCaughtBou: () -> Unit = {},
    private val onGameplayEvent: (eventType: String, payloadJson: String) -> Unit = { _, _ -> }
) {
    private var nextEffectId = 1L
    private var stats = MatchStatsTracker()
    private var botChatCooldown = 3.5f
    private var pulseSoundTimer = 0f
    private var dustSpawnTimer = 0f
    private var footstepTimer = 0f
    private var rippleSpawnTimer = 0f
    private var simFrameCount = 0
    private var cachedSpringHitDist = 13.4f
    private val botWanderTargets = mutableMapOf<String, Vec3>()
    private val botStuckTimers = mutableMapOf<String, Float>()
    private val botLastPositions = mutableMapOf<String, Vec3>()
    private val botCachedSteerX = mutableMapOf<String, Float>()
    private val botCachedSteerZ = mutableMapOf<String, Float>()
    // Pre-allocated scratch buffers for zero-GC raycasting inside simulation loop
    private val rayOffsets = floatArrayOf(0f, 0.42f, -0.42f, 0.90f, -0.90f, 1.43f, -1.43f)
    private var scratchSteerX = 0f
    private var scratchSteerZ = 0f
    private var presetMultiplayerRoles: Map<String, Role>? = null

    fun setPresetMultiplayerRoles(roles: Map<String, Role>?) {
        presetMultiplayerRoles = roles
    }

    fun createInitialWorld(
        lobbyPlayers: List<LobbyPlayer>,
        isSoloMode: Boolean,
        roomCode: String,
        preferredLocalRole: Role? = null
    ): GameWorldState {
        stats = MatchStatsTracker()
        botWanderTargets.clear()
        botStuckTimers.clear()
        botLastPositions.clear()
        botCachedSteerX.clear()
        botCachedSteerZ.clear()
        botChatCooldown = 4.0f
        dustSpawnTimer = 0f
        footstepTimer = 0f
        rippleSpawnTimer = 0f
        simFrameCount = 0
        cachedSpringHitDist = 13.4f

        val localLobby = lobbyPlayers.firstOrNull { it.isLocal }
        val botNames = listOf("Chacha Karim", "Bhabi Rina", "Kaku Jamal", "Mami Shilpi", "Babu Vai").shuffled()
        val finalRoster = mutableListOf<LobbyPlayer>()
        if (localLobby != null) finalRoster.add(localLobby)
        lobbyPlayers.filter { !it.isLocal && !it.isBot }.take(4).forEach { finalRoster.add(it) }
        var botIdx = 0
        while (finalRoster.size < 5) {
            finalRoster.add(
                LobbyPlayer(
                    id = "bot_$botIdx",
                    name = botNames[botIdx % botNames.size],
                    isHost = false,
                    isLocal = false,
                    isBot = true
                )
            )
            botIdx++
        }

        val preset = presetMultiplayerRoles
        presetMultiplayerRoles = null

        val seenRoles = mutableSetOf<Role>()
        val assignedPresetRoles = finalRoster.map { lp ->
            val r = preset?.get(lp.id) ?: preset?.get(lp.name)
            if (r != null && seenRoles.add(r)) r else null
        }
        val remainingRoles = Role.entries.toMutableList().apply {
            removeAll(seenRoles)
            shuffle()
            if (preferredLocalRole != null && localLobby != null && assignedPresetRoles.firstOrNull() == null && contains(preferredLocalRole)) {
                remove(preferredLocalRole)
                add(0, preferredLocalRole)
            }
        }
        var remIdx = 0

        val entities = finalRoster.mapIndexed { i, lp ->
            val role = assignedPresetRoles[i] ?: remainingRoles[remIdx++]
            val spawn = VenueMap.spawnPositionFor(role)
            // Always construct an independent Vec3 copy for every entity
            val uniquePos = Vec3(spawn.x, spawn.y, spawn.z)
            PlayerEntity(
                id = lp.id,
                name = lp.name,
                role = role,
                isBot = lp.isBot,
                isLocal = lp.isLocal,
                pos = uniquePos,
                velX = 0f,
                velZ = 0f,
                yaw = if (role == Role.BOU_CHOR) PI.toFloat() else 0f,
                campAnchorX = uniquePos.x,
                campAnchorZ = uniquePos.z
            )
        }

        val localSpawn = entities.firstOrNull { it.isLocal }?.pos ?: Vec3()
        val initCamYaw = if (entities.firstOrNull { it.isLocal }?.role == Role.BOU_CHOR) 0f else 0f

        return GameWorldState(
            timeRemainingSec = 240f,
            elapsedTimeSec = 0f,
            players = entities,
            tasks = VenueMap.initialTasks(),
            gates = VenueMap.initialGates(),
            cameraX = localSpawn.x,
            cameraZ = localSpawn.z,
            cameraYaw = initCamYaw,
            cameraPitch = 0.54f, // ~31° action angle
            cameraDistance = 13.4f,
            effectiveCameraDistance = 13.4f,
            currentZoneName = VenueMap.zoneNameAt(localSpawn),
            roleRevealTimer = 3.8f,
            isSoloMode = isSoloMode,
            roomCode = roomCode
        )
    }

    fun step(
        world: GameWorldState,
        dt: Float,
        moveInputX: Float,
        moveInputZ: Float,
        targetCameraYaw: Float,
        targetCameraPitch: Float,
        targetCameraDist: Float,
        ability1Pressed: Boolean,
        ability1Held: Boolean,
        ability2Pressed: Boolean,
        localMicLevel: Float,
        allowRandomWeatherTransition: Boolean = true,
        ability3Pressed: Boolean = false,
        simulateBotsLocally: Boolean = true
    ): GameWorldState {
        val clampedDt = dt.coerceIn(0.001f, 0.05f)
        simFrameCount++
        val newElapsed = world.elapsedTimeSec + clampedDt
        val newTimeLeft = (world.timeRemainingSec - clampedDt).coerceAtLeast(0f)

        // Frame-rate independent exponential damping for 360° camera orbit & pitch & zoom!
        val smoothCamYaw = damp(world.cameraYaw, targetCameraYaw, 16f, clampedDt)
        val clampedTargetPitch = targetCameraPitch.coerceIn(0.262f, 1.309f) // Strictly clamped 15°..75°
        val smoothCamPitch = damp(world.cameraPitch, clampedTargetPitch, 16f, clampedDt)
        val smoothCamDist = damp(world.cameraDistance, targetCameraDist.coerceIn(7.5f, 22.0f), 12f, clampedDt)

        val players = world.players.toMutableList()
        val tasks = world.tasks.toMutableList()
        val sandals = world.sandals.toMutableList()
        val bananas = world.bananas.toMutableList()
        val shockwaves = world.shockwaves.toMutableList()
        val flashCones = world.flashCones.toMutableList()
        val dustParticles = world.dustParticles.toMutableList()
        val combatTexts = world.combatTexts.toMutableList()
        var screamBeacon = world.screamBeacon?.let {
            val nl = it.timeLeft - clampedDt
            if (nl > 0f) it.copy(timeLeft = nl) else null
        }
        var cameraShake = (world.cameraShake - clampedDt * 3.4f).coerceAtLeast(0f)
        var bannerText = world.bannerAlertText
        var bannerTimer = (world.bannerAlertTimer - clampedDt).coerceAtLeast(0f)
        if (bannerTimer <= 0f) bannerText = null
        val roleRevealTimer = (world.roleRevealTimer - clampedDt).coerceAtLeast(0f)

        var escapedVia: VehicleType? = null
        var bidayiEscapedVia: VehicleType? = null

        fun addCombatText(text: String, pos: Vec3, color: Color) {
            combatTexts.add(
                FloatingCombatText(
                    id = nextEffectId++,
                    text = text,
                    pos = Vec3(pos.x, pos.y + 2.7f, pos.z),
                    color = color
                )
            )
        }

        fun setBanner(msg: String, duration: Float = 2.8f) {
            bannerText = msg
            bannerTimer = duration
        }

        fun emitEv(ev: String, block: JSONObject.() -> Unit = {}) {
            val obj = JSONObject().apply {
                put("type", "game_event")
                put("ev", ev)
                block()
            }
            onGameplayEvent(ev, obj.toString())
        }

        // 0. Dynamic Outdoor Courtyard Weather Transitions (Clear Evening <-> Monsoon Rain <-> Foggy Night)
        var currentWeather = world.weather
        var nextWeatherTimer = world.weatherTimerSec - clampedDt
        if (allowRandomWeatherTransition && nextWeatherTimer <= 0f) {
            val candidates = WeatherType.entries.filter { it != currentWeather }
            currentWeather = candidates[Random.nextInt(candidates.size)]
            nextWeatherTimer = 22f + Random.nextFloat() * 14f
            soundEngine.playWeatherShift(
                isRain = currentWeather == WeatherType.MONSOON_RAIN,
                isFog = currentWeather == WeatherType.FOGGY_NIGHT
            )
            setBanner(currentWeather.bannerMessage, 3.2f)
        }
        val targetRain = if (currentWeather == WeatherType.MONSOON_RAIN) 1f else 0f
        val targetFog = if (currentWeather == WeatherType.FOGGY_NIGHT) 1f else 0f
        val nextRainIntensity = damp(world.rainIntensity, targetRain, 0.55f, clampedDt).coerceIn(0f, 1f)
        val nextFogIntensity = damp(world.fogIntensity, targetFog, 0.50f, clampedDt).coerceIn(0f, 1f)
        val outdoorSpeedMult = (1f - nextRainIntensity * 0.15f - nextFogIntensity * 0.09f).coerceIn(0.82f, 1.0f)
        val outdoorVisFactor = (1f - nextRainIntensity * 0.24f - nextFogIntensity * 0.58f).coerceIn(0.38f, 1.0f)
        val rainRipples = world.rainRipples.toMutableList()

        // 1. Tick per-player timers
        for (i in players.indices) {
            val p = players[i]
            val bubbleTimer = (p.speechBubbleTimer - clampedDt).coerceAtLeast(0f)
            players[i] = p.copy(
                stunTimer = (p.stunTimer - clampedDt).coerceAtLeast(0f),
                slipTimer = (p.slipTimer - clampedDt).coerceAtLeast(0f),
                blindTimer = (p.blindTimer - clampedDt).coerceAtLeast(0f),
                wallHackTimer = (p.wallHackTimer - clampedDt).coerceAtLeast(0f),
                swingAnim = (p.swingAnim - clampedDt * 2.8f).coerceAtLeast(0f),
                kidnapImmunityTimer = (p.kidnapImmunityTimer - clampedDt).coerceAtLeast(0f),
                speedBoostTimer = (p.speedBoostTimer - clampedDt).coerceAtLeast(0f),
                slowTimer = (p.slowTimer - clampedDt).coerceAtLeast(0f),
                ability1Cooldown = (p.ability1Cooldown - clampedDt).coerceAtLeast(0f),
                ability2Cooldown = (p.ability2Cooldown - clampedDt).coerceAtLeast(0f),
                speechBubbleTimer = bubbleTimer,
                speechBubbleText = if (bubbleTimer > 0f) p.speechBubbleText else null,
                speakingLevel = if (p.isLocal) localMicLevel else (if (bubbleTimer > 2.6f) 0.75f else 0f)
            )
        }

        val bouIdx = players.indexOfFirst { it.role == Role.BOU }
        val chorIdx = players.indexOfFirst { it.role == Role.BOU_CHOR }
        val borIdx = players.indexOfFirst { it.role == Role.BOR }
        if (bouIdx < 0 || chorIdx < 0) return world

        // Camera horizontal basis for strictly CAMERA-RELATIVE movement!
        // At cameraYaw = 0: camera is at +Z looking toward -Z.
        // Forward (stick Up / moveZ = -1) = (-sin(yaw), -cos(yaw))
        // Right   (stick Right / moveX = +1) = (cos(yaw), -sin(yaw))
        val camFwdX = -sin(smoothCamYaw)
        val camFwdZ = -cos(smoothCamYaw)
        val camRightX = cos(smoothCamYaw)
        val camRightZ = -sin(smoothCamYaw)

        dustSpawnTimer -= clampedDt
        val allowDustSpawn = dustSpawnTimer <= 0f
        if (allowDustSpawn) dustSpawnTimer = 0.08f

        // 2. Movement (with exponential acceleration/deceleration damping) & Role Abilities
        for (i in players.indices) {
            val p = players[i]
            if (p.isCarriedInSack) {
                val chor = players[chorIdx]
                players[i] = p.copy(
                    pos = Vec3(chor.pos.x, 1.45f, chor.pos.z),
                    velX = 0f,
                    velZ = 0f,
                    yaw = chor.yaw,
                    currentSpeed = 0f,
                    isMoving = false
                )
                val wantsScream = if (p.isLocal) ability1Pressed else (p.ability1Cooldown <= 0f && Random.nextFloat() < clampedDt * 0.45f)
                if (wantsScream && p.ability1Cooldown <= 0f) {
                    players[i] = players[i].copy(ability1Cooldown = 10f, ability1MaxCooldown = 10f)
                    shockwaves.add(ShockwaveEffect(nextEffectId++, chor.pos))
                    screamBeacon = ScreamBeacon(chor.pos, 7.0f)
                    soundEngine.playScream()
                    addCombatText("PACHAO!! SCREAM!", chor.pos, BridalCrimson)
                    setBanner("BOU SCREAMED FROM THE SACK! FOLLOW THE RED ARROW!")
                    cameraShake = 0.4f
                    if (p.isBot) triggerBotSpeech(players, i, "বাঁচাও! চোর ধরছে!", ChatChannel.ALL)
                    if (p.isLocal || (p.isBot && simulateBotsLocally)) {
                        emitEv("scream") { put("x", chor.pos.x.toDouble()); put("z", chor.pos.z.toDouble()) }
                    }
                }
                continue
            }

            if (p.isCarriedByBor) {
                val bor = if (borIdx >= 0) players[borIdx] else null
                if (bor == null || !bor.isCarryingBou || bor.isIncapacitated) {
                    val dropPos = VenueMap.resolveMovement(Vec3(p.pos.x, 0f, p.pos.z))
                    players[i] = p.copy(isCarriedByBor = false, pos = dropPos)
                } else {
                    players[i] = p.copy(
                        pos = Vec3(bor.pos.x, 1.25f, bor.pos.z),
                        velX = 0f,
                        velZ = 0f,
                        yaw = bor.yaw,
                        currentSpeed = 0f,
                        isMoving = false
                    )
                    if (p.isLocal && ability1Pressed) {
                        val hopPos = VenueMap.resolveMovement(
                            Vec3(bor.pos.x + sin(bor.yaw) * 1.4f, 0f, bor.pos.z + cos(bor.yaw) * 1.4f)
                        )
                        players[i] = players[i].copy(isCarriedByBor = false, pos = hopPos)
                        players[borIdx] = bor.copy(isCarryingBou = false, holdType = HoldActionType.NONE, holdProgress = 0f)
                        addCombatText("HOPPED DOWN!", hopPos, MarigoldGold)
                        emitEv("bou_hop_off") {
                            put("bouId", p.id); put("borId", bor.id)
                            put("hopX", hopPos.x.toDouble()); put("hopZ", hopPos.z.toDouble())
                        }
                    }
                    continue
                }
            }

            if (!p.isLocal && (!p.isBot || !simulateBotsLocally)) {
                // Remote human players (and Guest's remote bots) are driven directly by network sync
                continue
            }

            if (p.isIncapacitated) {
                val dvx = damp(p.velX, 0f, 12f, clampedDt)
                val dvz = damp(p.velZ, 0f, 12f, clampedDt)
                val dropBou = p.role == Role.BOR && p.isCarryingBou
                if (dropBou) {
                    val dropPos = VenueMap.resolveMovement(Vec3(p.pos.x + 1.2f, 0f, p.pos.z))
                    players[bouIdx] = players[bouIdx].copy(isCarriedByBor = false, pos = dropPos, kidnapImmunityTimer = 1.4f)
                    addCombatText("DROPPED BOU!", dropPos, MarigoldGold)
                }
                players[i] = p.copy(
                    velX = dvx,
                    velZ = dvz,
                    isMoving = false,
                    currentSpeed = 0f,
                    isCarryingBou = if (dropBou) false else p.isCarryingBou,
                    holdType = HoldActionType.NONE,
                    holdProgress = 0f
                )
                continue
            }

            val input = if (p.isLocal) {
                // Transform stick/WASD input into strictly camera-relative world vector!
                val forwardAmt = -moveInputZ
                val rightAmt = moveInputX
                val worldMoveX = camRightX * rightAmt + camFwdX * forwardAmt
                val worldMoveZ = camRightZ * rightAmt + camFwdZ * forwardAmt
                PlayerActionInput(
                    moveX = worldMoveX,
                    moveZ = worldMoveZ,
                    triggerAbility1 = ability1Pressed,
                    holdAction = ability1Held,
                    triggerAbility2 = ability2Pressed,
                    triggerAbility3 = ability3Pressed
                )
            } else {
                computeSmartBotInput(i, p, players, tasks, world.gates, clampedDt, outdoorVisFactor)
            }

            val isPlayerOutdoors = !VenueMap.isInsideBuilding(p.pos)
            var maxSpeed = p.role.baseSpeed
            if (p.speedBoostTimer > 0f) maxSpeed *= 1.40f
            if (p.slowTimer > 0f) maxSpeed *= 0.45f
            if (p.role == Role.BOU_CHOR && p.isCarryingBou) maxSpeed *= 0.93f
            if (p.role == Role.BOR && p.isCarryingBou) maxSpeed *= 0.90f
            if (p.blindTimer > 0f && p.isBot) maxSpeed *= 0.55f
            // Outdoor weather slows movement on rain-slicked courtyard grass/tarmac or in dense night fog
            if (isPlayerOutdoors) {
                maxSpeed *= outdoorSpeedMult
            }

            val mag = hypot(input.moveX, input.moveZ)
            val targetVx: Float
            val targetVz: Float
            if (mag > 0.06f) {
                val scale = mag.coerceAtMost(1f) / mag
                targetVx = input.moveX * scale * maxSpeed
                targetVz = input.moveZ * scale * maxSpeed
            } else {
                targetVx = 0f
                targetVz = 0f
            }

            // Smooth exponential acceleration & deceleration damping (slightly slicker outdoors in monsoon rain)
            val wetTractionFactor = if (isPlayerOutdoors && nextRainIntensity > 0.2f) (1f - nextRainIntensity * 0.32f) else 1f
            val accelLambda = (if (mag > 0.06f) 13.5f else 15.5f) * wetTractionFactor
            val newVx = damp(p.velX, targetVx, accelLambda, clampedDt)
            val newVz = damp(p.velZ, targetVz, accelLambda, clampedDt)
            val actualSpeed = hypot(newVx, newVz)
            val moving = actualSpeed > 0.35f

            var nextPos = p.pos
            var nextYaw = p.yaw
            var nextWalk = p.walkPhase

            if (moving) {
                val candidate = Vec3(p.pos.x + newVx * clampedDt, 0f, p.pos.z + newVz * clampedDt)
                nextPos = VenueMap.resolveMovement(candidate)
                val targetYaw = atan2(newVx, newVz)
                nextYaw = dampAngle(p.yaw, targetYaw, 14f, clampedDt)
                nextWalk += clampedDt * actualSpeed * 1.65f

                val nextIsIndoor = VenueMap.isInsideBuilding(nextPos)
                val isWetOutdoorStep = !nextIsIndoor && nextRainIntensity > 0.22f

                // Rhythmic footsteps for local player (with wet splash outdoors during monsoon rain!)
                if (p.isLocal && actualSpeed > 3.0f) {
                    footstepTimer -= clampedDt * (actualSpeed / p.role.baseSpeed).coerceIn(0.6f, 1.3f)
                    if (footstepTimer <= 0f) {
                        footstepTimer = 0.31f
                        soundEngine.playFootstep(nextIsIndoor, isWetOutdoorStep)
                        if (isWetOutdoorStep && rainRipples.size < 42) {
                            rainRipples.add(
                                WeatherRipple(
                                    id = nextEffectId++,
                                    pos = Vec3(nextPos.x, 0.03f, nextPos.z),
                                    age = 0f,
                                    maxAge = 0.58f,
                                    maxRadius = 1.35f
                                )
                            )
                        }
                    }
                }

                // Sprint dust puffs beneath running feet (suppressed outdoors when ground is soaked in rain)
                if (allowDustSpawn && actualSpeed > 4.6f && dustParticles.size < 64 && !isWetOutdoorStep) {
                    val backX = nextPos.x - sin(nextYaw) * 0.45f + (Random.nextFloat() - 0.5f) * 0.4f
                    val backZ = nextPos.z - cos(nextYaw) * 0.45f + (Random.nextFloat() - 0.5f) * 0.4f
                    dustParticles.add(
                        DustParticle(
                            id = nextEffectId++,
                            pos = Vec3(backX, 0.18f, backZ),
                            vx = (Random.nextFloat() - 0.5f) * 0.9f,
                            vy = 0.7f + Random.nextFloat() * 0.7f,
                            vz = (Random.nextFloat() - 0.5f) * 0.9f,
                            size = 0.28f + Random.nextFloat() * 0.18f,
                            life = 0.42f,
                            maxLife = 0.42f
                        )
                    )
                }

                if (p.speedBoostTimer > 0f && dustParticles.size < 64) {
                    val backX = nextPos.x - sin(nextYaw) * 0.45f + (Random.nextFloat() - 0.5f) * 0.4f
                    val backZ = nextPos.z - cos(nextYaw) * 0.45f + (Random.nextFloat() - 0.5f) * 0.4f
                    dustParticles.add(
                        DustParticle(
                            id = nextEffectId++,
                            pos = Vec3(backX, 0.25f, backZ),
                            vx = (Random.nextFloat() - 0.5f) * 1.6f,
                            vy = 0.8f + Random.nextFloat() * 1.0f,
                            vz = (Random.nextFloat() - 0.5f) * 1.6f,
                            size = 0.35f + Random.nextFloat() * 0.25f,
                            life = 0.5f,
                            maxLife = 0.5f
                        )
                    )
                }
            }

            players[i] = p.copy(
                pos = nextPos,
                velX = newVx,
                velZ = newVz,
                yaw = nextYaw,
                walkPhase = nextWalk,
                currentSpeed = actualSpeed,
                isMoving = moving
            )

            // Role Abilities
            val cur = players[i]
            when (cur.role) {
                Role.BOU -> {
                    val triggerEvent = cur.isLocal || (cur.isBot && simulateBotsLocally)
                    if (input.triggerAbility1 && cur.ability1Cooldown <= 0f) {
                        players[i] = cur.copy(ability1Cooldown = 10f, ability1MaxCooldown = 10f)
                        shockwaves.add(ShockwaveEffect(nextEffectId++, cur.pos))
                        screamBeacon = ScreamBeacon(cur.pos, 7.0f)
                        soundEngine.playScream()
                        addCombatText("PACHAO!!", cur.pos, BridalCrimson)
                        setBanner("BOU SCREAMED! FOLLOW THE RED ARROW!")
                        cameraShake = 0.3f
                        if (triggerEvent) {
                            emitEv("scream") { put("x", cur.pos.x.toDouble()); put("z", cur.pos.z.toDouble()) }
                        }
                    }
                    if (input.triggerAbility2 && players[i].ability2Cooldown <= 0f) {
                        val activeBou = players[i]
                        val chorTarget = players[chorIdx]
                        var throwYaw = activeBou.yaw
                        if (activeBou.isLocal) {
                            val dChor = activeBou.pos.dist2D(chorTarget.pos)
                            val angChor = atan2(chorTarget.pos.x - activeBou.pos.x, chorTarget.pos.z - activeBou.pos.z)
                            if (dChor <= 14.5f && abs(normalizeAngle(angChor - activeBou.yaw)) <= 1.55f && VenueMap.hasLineOfSight(activeBou.pos, chorTarget.pos)) {
                                throwYaw = angChor
                            }
                        }
                        players[i] = activeBou.copy(ability2Cooldown = 6f, ability2MaxCooldown = 6f, yaw = throwYaw)
                        val sx = sin(throwYaw)
                        val sz = cos(throwYaw)
                        val px = activeBou.pos.x + sx * 0.9f
                        val pz = activeBou.pos.z + sz * 0.9f
                        sandals.add(
                            SandalProjectile(
                                id = nextEffectId++,
                                ownerId = activeBou.id,
                                pos = Vec3(px, 1.15f, pz),
                                vx = sx * 21f,
                                vz = sz * 21f
                            )
                        )
                        soundEngine.playSandalThrow()
                        addCombatText("SANDAL!", activeBou.pos, MarigoldGold)
                        if (triggerEvent) {
                            emitEv("sandal_throw") {
                                put("ownerId", activeBou.id)
                                put("x", px.toDouble()); put("z", pz.toDouble())
                                put("vx", (sx * 21f).toDouble()); put("vz", (sz * 21f).toDouble())
                            }
                        }
                    }
                }

                Role.BOU_CHOR -> {
                    val triggerEvent = cur.isLocal || (cur.isBot && simulateBotsLocally)
                    val bou = players[bouIdx]
                    val distToBou = cur.pos.dist2D(bou.pos)
                    val escapePad = if (cur.holdType == HoldActionType.ESCAPE) 0.6f else 0f
                    val nearGate = if (cur.isCarryingBou) world.gates.firstOrNull { cur.pos.dist2D(it.pos) <= it.radius + escapePad } else null

                    val kidnapRange = if (cur.holdType == HoldActionType.KIDNAP) 5.4f else 4.2f
                    val isNearKidnapTarget = !cur.isCarryingBou && distToBou <= kidnapRange && bou.kidnapImmunityTimer <= 0f
                    val isNearEscapeTarget = cur.isCarryingBou && nearGate != null
                    val wantsDash = input.triggerAbility1 || (input.holdAction && !isNearKidnapTarget && !isNearEscapeTarget)

                    if (wantsDash && cur.ability1Cooldown <= 0f) {
                        players[i] = cur.copy(
                            speedBoostTimer = 2.8f,
                            stunTimer = 0f,
                            blindTimer = 0f,
                            slipTimer = 0f,
                            ability1Cooldown = 7.5f,
                            ability1MaxCooldown = 7.5f
                        )
                        soundEngine.playChatPop()
                        addCombatText("SHOTKAN! DASH!", cur.pos, MarigoldGold)
                        if (cur.isLocal) {
                            setBanner("CHOR DASHED WITH SPEED BOOST!", 2.0f)
                        }
                    }

                    var updatedCur = players[i]

                    if (!updatedCur.isCarryingBou) {
                        if (input.holdAction && isNearKidnapTarget) {
                            // Subtle lunge assist while stationary human holds KIDNAP so Bou doesn't slip 0.2m out of range
                            if (updatedCur.isLocal && mag < 0.25f && distToBou > 1.85f) {
                                val lx = (bou.pos.x - updatedCur.pos.x) / distToBou
                                val lz = (bou.pos.z - updatedCur.pos.z) / distToBou
                                val stepPos = VenueMap.resolveMovement(Vec3(updatedCur.pos.x + lx * clampedDt * 5.0f, 0f, updatedCur.pos.z + lz * clampedDt * 5.0f))
                                updatedCur = updatedCur.copy(pos = stepPos, yaw = atan2(lx, lz))
                            }
                            val prev = if (updatedCur.holdType == HoldActionType.KIDNAP) updatedCur.holdProgress else 0f
                            val nextProg = (prev + clampedDt / 1.3f).coerceAtMost(1f)
                            tickCapturePulse(clampedDt)
                            if (nextProg >= 1f) {
                                if (bou.isCarriedByBor && borIdx >= 0) {
                                    players[borIdx] = players[borIdx].copy(
                                        isCarryingBou = false,
                                        holdType = HoldActionType.NONE,
                                        holdProgress = 0f
                                    )
                                }
                                players[i] = updatedCur.copy(isCarryingBou = true, holdType = HoldActionType.NONE, holdProgress = 0f)
                                players[bouIdx] = bou.copy(isCarriedInSack = true, isCarriedByBor = false, auraExposed = false, campTimer = 0f)
                                screamBeacon = ScreamBeacon(updatedCur.pos, 8.0f)
                                stats.kidnapsCount++
                                stats.lastKidnapTimestampSec = newElapsed
                                if (updatedCur.isLocal) {
                                    onLocalChorCaughtBou()
                                }
                                soundEngine.playScream()
                                addCombatText("SACKED BOU!", updatedCur.pos, BridalCrimson)
                                setBanner("CHOR KIDNAPPED BOU! BLOCK THE 3 ESCAPE GATES!", 3.8f)
                                cameraShake = 0.55f
                                if (bou.isBot) triggerBotSpeech(players, bouIdx, "বাঁচাও! চোর ধরছে!", ChatChannel.ALL)
                                if (triggerEvent) {
                                    emitEv("kidnap_bou") { put("chorId", updatedCur.id); put("bouId", bou.id) }
                                }
                            } else {
                                players[i] = updatedCur.copy(holdType = HoldActionType.KIDNAP, holdProgress = nextProg)
                            }
                        } else {
                            players[i] = updatedCur.copy(holdType = HoldActionType.NONE, holdProgress = 0f)
                        }
                    } else {
                        if (input.holdAction && nearGate != null) {
                            val prev = if (updatedCur.holdType == HoldActionType.ESCAPE) updatedCur.holdProgress else 0f
                            val nextProg = (prev + clampedDt / 2.5f).coerceAtMost(1f)
                            tickCapturePulse(clampedDt)
                            if (nextProg >= 1f) {
                                soundEngine.playCngHorn()
                                escapedVia = nearGate.type
                            }
                            players[i] = updatedCur.copy(holdType = HoldActionType.ESCAPE, holdProgress = nextProg)
                        } else {
                            players[i] = updatedCur.copy(holdType = HoldActionType.NONE, holdProgress = 0f)
                        }
                    }

                    if (input.triggerAbility2 && players[i].ability2Cooldown <= 0f) {
                        val activeChor = players[i]
                        val sx = sin(activeChor.yaw)
                        val sz = cos(activeChor.yaw)
                        val cx = cos(activeChor.yaw)
                        val cz = -sin(activeChor.yaw)

                        val p1x = activeChor.pos.x - sx * 1.2f + cx * 0.7f
                        val p1z = activeChor.pos.z - sz * 1.2f + cz * 0.7f
                        val p2x = activeChor.pos.x - sx * 1.2f - cx * 0.7f
                        val p2z = activeChor.pos.z - sz * 1.2f - cz * 0.7f
                        bananas.add(BananaPeel(nextEffectId++, Vec3(p1x, 0.05f, p1z)))
                        bananas.add(BananaPeel(nextEffectId++, Vec3(p2x, 0.05f, p2z)))

                        players[i] = activeChor.copy(ability2Cooldown = 4.0f, ability2MaxCooldown = 4.0f)
                        soundEngine.playChatPop()
                        addCombatText("DOUBLE BANANA!", activeChor.pos, MarigoldGold)
                        if (triggerEvent) {
                            emitEv("banana_drop") {
                                put("x1", p1x.toDouble()); put("z1", p1z.toDouble())
                                put("x2", p2x.toDouble()); put("z2", p2z.toDouble())
                            }
                        }
                    }

                    // Ability 3 (Once per entire match): Slow Mama's speed for 5 seconds!
                    if (input.triggerAbility3 && !players[i].mamaSlowUsed) {
                        val mamaIdx = players.indexOfFirst { it.role == Role.MAMA }
                        players[i] = players[i].copy(mamaSlowUsed = true)
                        if (mamaIdx >= 0) {
                            players[mamaIdx] = players[mamaIdx].copy(slowTimer = 5.0f)
                            shockwaves.add(ShockwaveEffect(nextEffectId++, players[mamaIdx].pos))
                            addCombatText("SLOWED 5s!", players[mamaIdx].pos, Color(0xFFB388FF))
                        }
                        soundEngine.playBananaSlip()
                        setBanner("CHOR CURSED MAMA! 55% SLOWER FOR 5s (1x USED)!", 3.0f)
                        if (triggerEvent) {
                            emitEv("chor_slow_mama") { put("chorId", players[i].id) }
                        }
                    }
                }

                Role.BOR -> {
                    val triggerEvent = cur.isLocal || (cur.isBot && simulateBotsLocally)
                    val chor = players[chorIdx]
                    val bou = players[bouIdx]
                    val distToChor = cur.pos.dist2D(chor.pos)
                    val distToBou = cur.pos.dist2D(bou.pos)
                    val bidayiPad = if (cur.holdType == HoldActionType.BOR_ESCAPE) 0.6f else 0f
                    val nearGate = if (cur.isCarryingBou) world.gates.firstOrNull { cur.pos.dist2D(it.pos) <= it.radius + bidayiPad } else null
                    val rescueRange = if (cur.holdType == HoldActionType.RESCUE) 5.4f else 4.2f
                    val pickupRange = if (cur.holdType == HoldActionType.PICKUP_BOU) 5.4f else 4.2f

                    if (input.holdAction && chor.isCarryingBou && distToChor <= rescueRange) {
                        val prev = if (cur.holdType == HoldActionType.RESCUE) cur.holdProgress else 0f
                        val nextProg = (prev + clampedDt / 1.5f).coerceAtMost(1f)
                        tickCapturePulse(clampedDt)
                        if (nextProg >= 1f) {
                            val rescueTime = (newElapsed - stats.lastKidnapTimestampSec).coerceAtLeast(1.5f)
                            if (stats.fastestRescueSec == null || rescueTime < stats.fastestRescueSec!!) {
                                stats.fastestRescueSec = rescueTime
                            }
                            stats.rescuesCount++
                            val freedPos = VenueMap.resolveMovement(
                                Vec3(chor.pos.x + sin(cur.yaw) * 1.8f, 0f, chor.pos.z + cos(cur.yaw) * 1.8f)
                            )
                            players[chorIdx] = chor.copy(
                                isCarryingBou = false,
                                stunTimer = maxOf(chor.stunTimer, 1.3f),
                                holdType = HoldActionType.NONE,
                                holdProgress = 0f
                            )
                            players[bouIdx] = players[bouIdx].copy(
                                isCarriedInSack = false,
                                isCarriedByBor = false,
                                pos = freedPos,
                                kidnapImmunityTimer = 2.5f
                            )
                            players[i] = cur.copy(holdType = HoldActionType.NONE, holdProgress = 0f)
                            soundEngine.playTaskComplete()
                            addCombatText("BOU RESCUED!", cur.pos, CngEmerald)
                            setBanner("BOR RESCUED BOU FROM CHOR'S SACK!", 2.8f)
                            cameraShake = 0.4f
                            if (triggerEvent) {
                                emitEv("rescue_bou") {
                                    put("borId", cur.id); put("chorId", chor.id); put("bouId", bou.id)
                                    put("freedX", freedPos.x.toDouble()); put("freedZ", freedPos.z.toDouble())
                                }
                            }
                        } else {
                            players[i] = cur.copy(holdType = HoldActionType.RESCUE, holdProgress = nextProg)
                        }
                    } else if (cur.isCarryingBou && input.holdAction && nearGate != null) {
                        // Sit inside vehicle with Bou for 3.0s -> Jamai Wins!
                        val prev = if (cur.holdType == HoldActionType.BOR_ESCAPE) cur.holdProgress else 0f
                        val nextProg = (prev + clampedDt / 3.0f).coerceAtMost(1f)
                        tickCapturePulse(clampedDt)
                        if (nextProg >= 1f) {
                            soundEngine.playCngHorn()
                            bidayiEscapedVia = nearGate.type
                        }
                        players[i] = cur.copy(holdType = HoldActionType.BOR_ESCAPE, holdProgress = nextProg)
                    } else if (!cur.isCarryingBou && !chor.isCarryingBou && !bou.isCarriedInSack && !bou.isCarriedByBor && input.holdAction && distToBou <= pickupRange) {
                        // Scoop Bou into Jamai's arms (0.8s hold)
                        val prev = if (cur.holdType == HoldActionType.PICKUP_BOU) cur.holdProgress else 0f
                        val nextProg = (prev + clampedDt / 0.8f).coerceAtMost(1f)
                        tickCapturePulse(clampedDt)
                        if (nextProg >= 1f) {
                            players[i] = cur.copy(isCarryingBou = true, holdType = HoldActionType.NONE, holdProgress = 0f)
                            players[bouIdx] = bou.copy(isCarriedByBor = true, isCarriedInSack = false, auraExposed = false, campTimer = 0f)
                            soundEngine.playTaskComplete()
                            addCombatText("CARRYING BOU!", cur.pos, BorGold)
                            setBanner("JAMAI CARRYING BOU! SIT IN ANY VEHICLE FOR 3s TO WIN!", 3.4f)
                            if (triggerEvent) {
                                emitEv("bor_pickup_bou") { put("borId", cur.id); put("bouId", bou.id) }
                            }
                        } else {
                            players[i] = cur.copy(holdType = HoldActionType.PICKUP_BOU, holdProgress = nextProg)
                        }
                    } else {
                        players[i] = cur.copy(holdType = HoldActionType.NONE, holdProgress = 0f)
                    }

                    // Ability 2: Jamai Guard / Couple Escort Sprint
                    if (input.triggerAbility2 && players[i].ability2Cooldown <= 0f) {
                        val activeBor = players[i]
                        if (!bou.isCarriedInSack && !bou.isCarriedByBor && distToBou <= 4.8f) {
                            players[i] = activeBor.copy(
                                speedBoostTimer = 3.5f,
                                ability2Cooldown = 9.0f,
                                ability2MaxCooldown = 9.0f
                            )
                            players[bouIdx] = players[bouIdx].copy(
                                speedBoostTimer = maxOf(players[bouIdx].speedBoostTimer, 3.5f),
                                kidnapImmunityTimer = maxOf(players[bouIdx].kidnapImmunityTimer, 4.0f)
                            )
                            shockwaves.add(ShockwaveEffect(nextEffectId++, bou.pos))
                            soundEngine.playTaskComplete()
                            addCombatText("JAMAI GUARD! +SHIELD", bou.pos, BorGold)
                            setBanner("JAMAI ESCORTING BOU! SHIELD + COUPLE SPEED BOOST!", 2.6f)
                            if (triggerEvent) {
                                emitEv("bor_guard") { put("borId", activeBor.id); put("bouId", bou.id) }
                            }
                        } else {
                            players[i] = activeBor.copy(
                                speedBoostTimer = 2.6f,
                                ability2Cooldown = 9.0f,
                                ability2MaxCooldown = 9.0f
                            )
                            soundEngine.playChatPop()
                            addCombatText("JAMAI SPRINT!", activeBor.pos, BorGold)
                        }
                    }
                }

                Role.MAMA -> {
                    val triggerEvent = cur.isLocal || (cur.isBot && simulateBotsLocally)
                    if (input.triggerAbility1 && cur.ability1Cooldown <= 0f) {
                        val chor = players[chorIdx]
                        val dist = cur.pos.dist2D(chor.pos)
                        val toChorAngle = atan2(chor.pos.x - cur.pos.x, chor.pos.z - cur.pos.z)
                        val angleDiff = abs(normalizeAngle(toChorAngle - cur.yaw))
                        val hitDist = if (cur.isLocal) 4.6f else 4.3f
                        val hitCone = if (cur.isLocal) (if (dist <= 3.6f) 2.35f else 1.75f) else 1.35f
                        val landedHit = dist <= hitDist && angleDiff <= hitCone
                        val swingYaw = if (cur.isLocal && landedHit) toChorAngle else cur.yaw
                        players[i] = cur.copy(ability1Cooldown = 4.5f, ability1MaxCooldown = 4.5f, swingAnim = 1.0f, yaw = swingYaw)
                        if (landedHit) {
                            stats.bambooHits++
                            val droppedBou = chor.isCarryingBou
                            var dropPos = cur.pos
                            if (droppedBou) {
                                stats.bambooDropSaves++
                                dropPos = VenueMap.resolveMovement(
                                    Vec3(chor.pos.x - sin(swingYaw) * 1.6f, 0f, chor.pos.z - cos(swingYaw) * 1.6f)
                                )
                                players[bouIdx] = players[bouIdx].copy(
                                    isCarriedInSack = false,
                                    pos = dropPos,
                                    kidnapImmunityTimer = 2.5f
                                )
                            }
                            players[chorIdx] = chor.copy(
                                stunTimer = 3.0f,
                                isCarryingBou = false,
                                holdType = HoldActionType.NONE,
                                holdProgress = 0f
                            )
                            soundEngine.playBonk()
                            addCombatText("DHISHUM!! BONK!", chor.pos, MamaOrange)
                            setBanner(
                                if (droppedBou) "MAMA BONKED CHOR & DROPPED BOU!"
                                else "DHISHUM! MAMA LANDED A BAMBOO HIT!",
                                2.8f
                            )
                            cameraShake = 0.95f
                            if (triggerEvent) {
                                emitEv("chor_hit") {
                                    put("cause", "bonk"); put("stun", 3.0); put("blind", 0.0); put("wallHack", 0.0)
                                    put("droppedBou", droppedBou)
                                    put("dropX", dropPos.x.toDouble()); put("dropZ", dropPos.z.toDouble())
                                }
                            }
                        } else {
                            soundEngine.playBambooWhoosh()
                            addCombatText("WHOOSH!", cur.pos, MamaOrange)
                        }
                    }
                }

                Role.PHOTOGRAPHER -> {
                    val triggerEvent = cur.isLocal || (cur.isBot && simulateBotsLocally)
                    if (input.triggerAbility1 && cur.ability1Cooldown <= 0f) {
                        val chor = players[chorIdx]
                        val dist = cur.pos.dist2D(chor.pos)
                        val toChor = atan2(chor.pos.x - cur.pos.x, chor.pos.z - cur.pos.z)
                        val diff = abs(normalizeAngle(toChor - cur.yaw))
                        val hasLos = VenueMap.hasLineOfSight(cur.pos, chor.pos)
                        val hitFlash = dist <= 15.5f && hasLos && (if (cur.isLocal) (diff <= 0.92f || (dist <= 4.8f && diff <= 1.45f)) else diff <= 0.68f)
                        val coneYaw = if (cur.isLocal && hitFlash) toChor else cur.yaw
                        players[i] = cur.copy(ability1Cooldown = 9.0f, ability1MaxCooldown = 9.0f, yaw = coneYaw)
                        flashCones.add(FlashConeEffect(nextEffectId++, cur.pos, coneYaw))
                        soundEngine.playCameraFlash()
                        if (triggerEvent) {
                            emitEv("flash_cone") {
                                put("x", cur.pos.x.toDouble()); put("z", cur.pos.z.toDouble()); put("yaw", coneYaw.toDouble())
                            }
                        }
                        if (hitFlash) {
                            stats.flashHits++
                            players[chorIdx] = chor.copy(blindTimer = 2.5f, wallHackTimer = 10.0f)
                            addCombatText("FLASHED!", chor.pos, FairyCyan)
                            setBanner("PHOTOGRAPHER FLASHED CHOR! REVEALED FOR 10s!", 3.2f)
                            cameraShake = 0.45f
                            if (triggerEvent) {
                                emitEv("chor_hit") {
                                    put("cause", "flash"); put("stun", 0.0); put("blind", 2.5); put("wallHack", 10.0)
                                    put("droppedBou", false)
                                }
                            }
                        } else {
                            addCombatText("CLICK!", cur.pos, FairyCyan)
                        }
                    }
                }
            }
        }

        // 2b. Character-to-Character Circle Separation Physics (radius ~0.85 units each; disabled for carried Bou)
        resolveCharacterSeparation(players)
        if (players[bouIdx].isCarriedInSack) {
            val postSepChor = players[chorIdx]
            players[bouIdx] = players[bouIdx].copy(
                pos = Vec3(postSepChor.pos.x, 1.45f, postSepChor.pos.z),
                yaw = postSepChor.yaw
            )
        } else if (players[bouIdx].isCarriedByBor && borIdx >= 0) {
            val postSepBor = players[borIdx]
            players[bouIdx] = players[bouIdx].copy(
                pos = Vec3(postSepBor.pos.x, 1.25f, postSepBor.pos.z),
                yaw = postSepBor.yaw
            )
        }

        // 3. Bou Multi-Zone Wedding Tasks & Anti-Camp Rule
        val bou = players[bouIdx]
        val borPlayer = players.firstOrNull { it.role == Role.BOR }
        val jamaiAssisting = borPlayer != null && !borPlayer.isIncapacitated && bou.pos.dist2D(borPlayer.pos) <= 4.5f
        if (!bou.isCarriedInSack && !bou.isCarriedByBor) {
            var doingTask = false
            for (ti in tasks.indices) {
                val task = tasks[ti]
                if (!task.completed && bou.pos.dist2D(task.pos) <= task.radius) {
                    doingTask = true
                    val speedMult = if (jamaiAssisting) 1.6f else 1.0f
                    val nextProg = (task.progress + (clampedDt * speedMult) / 4.2f).coerceAtMost(1f)
                    val justDone = nextProg >= 1f && !task.completed
                    tasks[ti] = task.copy(progress = nextProg, completed = nextProg >= 1f)
                    if (justDone) {
                        stats.bouTasksDone++
                        soundEngine.playTaskComplete()
                        val assistTag = if (jamaiAssisting) " (JAMAI ASSIST!)" else ""
                        addCombatText("${task.type.label.uppercase()} DONE!$assistTag", task.pos, MarigoldGold)
                        setBanner("BOU COMPLETED ${task.type.label.uppercase()}!", 2.4f)
                        if (bou.isLocal || (bou.isBot && simulateBotsLocally)) {
                            emitEv("task_done") { put("idx", ti); put("assist", jamaiAssisting) }
                        }
                    }
                }
            }
            val distFromAnchor = hypot(bou.pos.x - bou.campAnchorX, bou.pos.z - bou.campAnchorZ)
            if (distFromAnchor > 3.5f || doingTask) {
                players[bouIdx] = bou.copy(campAnchorX = bou.pos.x, campAnchorZ = bou.pos.z, campTimer = 0f, auraExposed = false)
            } else {
                val nextCamp = bou.campTimer + clampedDt
                val exposed = nextCamp >= 6.0f
                if (exposed && !bou.auraExposed) {
                    addCombatText("AURA EXPOSED!", bou.pos, BridalCrimson)
                    if (bou.isLocal) setBanner("MOVE! CAMPING EXPOSED YOUR AURA TO CHOR!", 2.6f)
                }
                players[bouIdx] = bou.copy(campTimer = nextCamp, auraExposed = exposed)
            }
        }

        // 4. Sandal Projectiles
        val sandalIter = sandals.listIterator()
        while (sandalIter.hasNext()) {
            val s = sandalIter.next()
            val nx = s.pos.x + s.vx * clampedDt
            val nz = s.pos.z + s.vz * clampedDt
            val nextLife = s.life - clampedDt
            val chor = players[chorIdx]
            if (hypot(nx - chor.pos.x, nz - chor.pos.z) <= 1.35f) {
                stats.sandalHits++
                players[chorIdx] = chor.copy(stunTimer = maxOf(chor.stunTimer, 1.5f), holdType = HoldActionType.NONE, holdProgress = 0f)
                soundEngine.playSandalHit()
                addCombatText("SANDAL STUN! 1.5s", chor.pos, BridalCrimson)
                setBanner("BOU HIT CHOR WITH A HIGH-HEEL SANDAL!", 2.5f)
                cameraShake = 0.45f
                val bouOwner = players[bouIdx]
                if (bouOwner.isLocal || (bouOwner.isBot && simulateBotsLocally)) {
                    emitEv("chor_hit") {
                        put("cause", "sandal"); put("stun", 1.5); put("blind", 0.0); put("wallHack", 0.0)
                        put("droppedBou", false)
                    }
                }
                sandalIter.remove()
            } else if (nextLife <= 0f || VenueMap.hitsObstacle(nx, nz, 0.25f)) {
                sandalIter.remove()
            } else {
                sandalIter.set(s.copy(pos = Vec3(nx, 1.15f, nz), spin = s.spin + clampedDt * 18f, life = nextLife))
            }
        }

        // 5. Banana Peels
        val bananaIter = bananas.listIterator()
        while (bananaIter.hasNext()) {
            val b = bananaIter.next()
            val nextLife = b.life - clampedDt
            var triggered = false
            for (i in players.indices) {
                val p = players[i]
                if (p.role != Role.BOU_CHOR && !p.isCarriedInSack && !p.isCarriedByBor && !p.isIncapacitated) {
                    if (p.pos.dist2D(b.pos) <= 1.25f) {
                        val droppedByBor = p.role == Role.BOR && p.isCarryingBou
                        var dropPos = p.pos
                        if (droppedByBor) {
                            dropPos = VenueMap.resolveMovement(Vec3(p.pos.x + 1.2f, 0f, p.pos.z))
                            players[bouIdx] = players[bouIdx].copy(isCarriedByBor = false, pos = dropPos, kidnapImmunityTimer = 1.4f)
                            addCombatText("DROPPED BOU!", dropPos, MarigoldGold)
                        }
                        players[i] = p.copy(
                            slipTimer = 2.2f,
                            isCarryingBou = if (droppedByBor) false else p.isCarryingBou,
                            holdType = HoldActionType.NONE,
                            holdProgress = 0f
                        )
                        stats.bananaSlips++
                        soundEngine.playBananaSlip()
                        addCombatText("SLIPPED!!", p.pos, MarigoldGold)
                        if (p.isLocal || (p.isBot && simulateBotsLocally)) {
                            emitEv("banana_slip") {
                                put("victimId", p.id)
                                put("bx", b.pos.x.toDouble()); put("bz", b.pos.z.toDouble())
                                put("droppedByBor", droppedByBor)
                                put("dropX", dropPos.x.toDouble()); put("dropZ", dropPos.z.toDouble())
                            }
                        }
                        triggered = true
                        break
                    }
                }
            }
            if (triggered || nextLife <= 0f) bananaIter.remove() else bananaIter.set(b.copy(life = nextLife))
        }

        // 6. Visual Effects & Dust Particles
        val swIter = shockwaves.listIterator()
        while (swIter.hasNext()) {
            val sw = swIter.next()
            val nextR = sw.radius + clampedDt * 34f
            val alpha = (1f - nextR / sw.maxRadius).coerceAtLeast(0f)
            if (alpha <= 0f) swIter.remove() else swIter.set(sw.copy(radius = nextR, alpha = alpha))
        }

        val fcIter = flashCones.listIterator()
        while (fcIter.hasNext()) {
            val fc = fcIter.next()
            val nl = fc.life - clampedDt
            if (nl <= 0f) fcIter.remove() else fcIter.set(fc.copy(life = nl))
        }

        val dustIter = dustParticles.listIterator()
        while (dustIter.hasNext()) {
            val dp = dustIter.next()
            val nl = dp.life - clampedDt
            if (nl <= 0f) {
                dustIter.remove()
            } else {
                dustIter.set(
                    dp.copy(
                        pos = Vec3(dp.pos.x + dp.vx * clampedDt, dp.pos.y + dp.vy * clampedDt, dp.pos.z + dp.vz * clampedDt),
                        size = dp.size + clampedDt * 0.45f,
                        life = nl
                    )
                )
            }
        }

        val ctIter = combatTexts.listIterator()
        while (ctIter.hasNext()) {
            val ct = ctIter.next()
            val nl = ct.life - clampedDt
            if (nl <= 0f) ctIter.remove() else ctIter.set(ct.copy(pos = Vec3(ct.pos.x, ct.pos.y + clampedDt * 1.5f, ct.pos.z), life = nl))
        }

        // 6b. Outdoor Monsoon Rain Ground Ripples (strictly outside building footprint!)
        val rippleIter = rainRipples.listIterator()
        while (rippleIter.hasNext()) {
            val r = rippleIter.next()
            val nextAge = r.age + clampedDt
            if (nextAge >= r.maxAge) rippleIter.remove() else rippleIter.set(r.copy(age = nextAge))
        }

        // 7. Smooth Damped Chase Follow + Spring-Arm Raycast Wall Collision!
        val local = players.firstOrNull { it.isLocal } ?: players.first()
        val nextCamX = damp(world.cameraX, local.pos.x, 10.5f, clampedDt)
        val nextCamZ = damp(world.cameraZ, local.pos.z, 10.5f, clampedDt)

        val localOutdoors = !VenueMap.isInsideBuilding(local.pos)
        val nextOutdoorExposure = damp(world.outdoorExposure, if (localOutdoors) 1f else 0f, 3.2f, clampedDt).coerceIn(0f, 1f)

        if (nextRainIntensity > 0.08f) {
            rippleSpawnTimer -= clampedDt * nextRainIntensity
            while (rippleSpawnTimer <= 0f && rainRipples.size < 38) {
                rippleSpawnTimer += 0.042f
                val rx = (nextCamX + (Random.nextFloat() * 2f - 1f) * 34f).coerceIn(-66f, 66f)
                val rz = (nextCamZ + (Random.nextFloat() * 2f - 1f) * 34f).coerceIn(-66f, 66f)
                val candidatePos = Vec3(rx, 0.03f, rz)
                if (!VenueMap.isInsideBuilding(candidatePos)) {
                    rainRipples.add(
                        WeatherRipple(
                            id = nextEffectId++,
                            pos = candidatePos,
                            age = 0f,
                            maxAge = 0.62f + Random.nextFloat() * 0.34f,
                            maxRadius = 1.05f + Random.nextFloat() * 0.85f
                        )
                    )
                }
            }
        } else {
            rippleSpawnTimer = 0f
        }

        // Throttle spring-arm wall raycast to every 3rd frame against main walls only (cached & smoothly damped!)
        if (simFrameCount % 3 == 0) {
            val rayDirX = sin(smoothCamYaw) * cos(smoothCamPitch)
            val rayDirY = sin(smoothCamPitch)
            val rayDirZ = cos(smoothCamYaw) * cos(smoothCamPitch)
            cachedSpringHitDist = VenueMap.raycastCameraDistance(
                originX = nextCamX,
                originY = 2.0f,
                originZ = nextCamZ,
                dirX = rayDirX,
                dirY = rayDirY,
                dirZ = rayDirZ,
                maxDist = smoothCamDist
            )
        }
        val hitDist = cachedSpringHitDist.coerceAtMost(smoothCamDist)
        // Fast pull-in when wall blocks camera, smooth spring-out when clear!
        val springLambda = if (hitDist < world.effectiveCameraDistance) 26f else 8f
        val nextEffectiveDist = damp(world.effectiveCameraDistance, hitDist, springLambda, clampedDt)
        val zoneName = VenueMap.zoneNameAt(local.pos)

        // 8. Ambient Bot Bengali Quick-Chat Banter
        botChatCooldown -= clampedDt
        if (botChatCooldown <= 0f) {
            botChatCooldown = Random.nextFloat() * 7.5f + 7f
            emitContextualBotChat(players, world.gates)
        }

        val nextWorld = world.copy(
            timeRemainingSec = newTimeLeft,
            elapsedTimeSec = newElapsed,
            players = players,
            tasks = tasks,
            sandals = sandals,
            bananas = bananas,
            shockwaves = shockwaves,
            screamBeacon = screamBeacon,
            flashCones = flashCones,
            dustParticles = dustParticles,
            combatTexts = combatTexts,
            cameraX = nextCamX,
            cameraZ = nextCamZ,
            cameraYaw = smoothCamYaw,
            cameraPitch = smoothCamPitch,
            cameraDistance = smoothCamDist,
            effectiveCameraDistance = nextEffectiveDist,
            cameraShake = cameraShake,
            currentZoneName = zoneName,
            weather = currentWeather,
            weatherTimerSec = nextWeatherTimer,
            rainIntensity = nextRainIntensity,
            fogIntensity = nextFogIntensity,
            outdoorExposure = nextOutdoorExposure,
            rainRipples = rainRipples,
            bannerAlertText = bannerText,
            bannerAlertTimer = bannerTimer,
            roleRevealTimer = roleRevealTimer
        )

        // 9. Match End Conditions
        if (bidayiEscapedVia != null) {
            emitEv("match_end") {
                put("team", Team.WEDDING.name)
                put("via", bidayiEscapedVia!!.name)
                put("jamai", true)
                put("time", newTimeLeft.toDouble())
            }
            val result = buildMatchResult(Team.WEDDING, bidayiEscapedVia, newTimeLeft, players, jamaiBidayiWin = true)
            val localWon = players.firstOrNull { it.isLocal }?.role?.team == Team.WEDDING
            if (localWon) soundEngine.playVictoryFanfare() else soundEngine.playDefeatFanfare()
            onMatchFinished(result)
        } else if (escapedVia != null) {
            emitEv("match_end") {
                put("team", Team.THIEF.name)
                put("via", escapedVia!!.name)
                put("jamai", false)
                put("time", newTimeLeft.toDouble())
            }
            val result = buildMatchResult(Team.THIEF, escapedVia, newTimeLeft, players)
            val localWon = players.firstOrNull { it.isLocal }?.role?.team == Team.THIEF
            if (localWon) soundEngine.playVictoryFanfare() else soundEngine.playDefeatFanfare()
            onMatchFinished(result)
        } else if (newTimeLeft <= 0f && simulateBotsLocally) {
            emitEv("match_end") {
                put("team", Team.WEDDING.name)
                put("via", "")
                put("jamai", false)
                put("time", 0.0)
            }
            val result = buildMatchResult(Team.WEDDING, null, 0f, players)
            val localWon = players.firstOrNull { it.isLocal }?.role?.team == Team.WEDDING
            if (localWon) soundEngine.playVictoryFanfare() else soundEngine.playDefeatFanfare()
            onMatchFinished(result)
        }

        return nextWorld
    }

    fun applyRemoteGameplayEvent(world: GameWorldState, obj: JSONObject): GameWorldState {
        val ev = obj.optString("ev")
        val players = world.players.toMutableList()
        val tasks = world.tasks.toMutableList()
        val sandals = world.sandals.toMutableList()
        val bananas = world.bananas.toMutableList()
        val shockwaves = world.shockwaves.toMutableList()
        val flashCones = world.flashCones.toMutableList()
        val combatTexts = world.combatTexts.toMutableList()
        var screamBeacon = world.screamBeacon
        var bannerText = world.bannerAlertText
        var bannerTimer = world.bannerAlertTimer
        var shake = world.cameraShake

        val bouIdx = players.indexOfFirst { it.role == Role.BOU }
        val chorIdx = players.indexOfFirst { it.role == Role.BOU_CHOR }
        val borIdx = players.indexOfFirst { it.role == Role.BOR }
        val mamaIdx = players.indexOfFirst { it.role == Role.MAMA }

        fun addCt(text: String, pos: Vec3, col: Color) {
            combatTexts.add(FloatingCombatText(nextEffectId++, text, Vec3(pos.x, pos.y + 2.7f, pos.z), col))
        }

        when (ev) {
            "scream" -> {
                val p = Vec3(obj.optDouble("x", 0.0).toFloat(), 0f, obj.optDouble("z", 0.0).toFloat())
                shockwaves.add(ShockwaveEffect(nextEffectId++, p))
                screamBeacon = ScreamBeacon(p, 7.0f)
                soundEngine.playScream()
                addCt("PACHAO!!", p, BridalCrimson)
                bannerText = "BOU SCREAMED! FOLLOW THE RED ARROW!"
                bannerTimer = 2.8f
                shake = 0.35f
            }

            "sandal_throw" -> {
                sandals.add(
                    SandalProjectile(
                        id = nextEffectId++,
                        ownerId = obj.optString("ownerId", ""),
                        pos = Vec3(obj.optDouble("x", 0.0).toFloat(), 1.15f, obj.optDouble("z", 0.0).toFloat()),
                        vx = obj.optDouble("vx", 0.0).toFloat(),
                        vz = obj.optDouble("vz", 0.0).toFloat()
                    )
                )
                soundEngine.playSandalThrow()
            }

            "banana_drop" -> {
                bananas.add(BananaPeel(nextEffectId++, Vec3(obj.optDouble("x1", 0.0).toFloat(), 0.05f, obj.optDouble("z1", 0.0).toFloat())))
                bananas.add(BananaPeel(nextEffectId++, Vec3(obj.optDouble("x2", 0.0).toFloat(), 0.05f, obj.optDouble("z2", 0.0).toFloat())))
                soundEngine.playChatPop()
            }

            "banana_slip" -> {
                val vid = obj.optString("victimId", "")
                val droppedByBor = obj.optBoolean("droppedByBor", false)
                val vIdx = players.indexOfFirst { it.id == vid }
                if (vIdx >= 0) {
                    val vp = players[vIdx]
                    players[vIdx] = vp.copy(slipTimer = 2.2f, isCarryingBou = if (droppedByBor) false else vp.isCarryingBou, holdType = HoldActionType.NONE, holdProgress = 0f)
                    addCt("SLIPPED!!", vp.pos, MarigoldGold)
                }
                if (droppedByBor && bouIdx >= 0) {
                    val dp = Vec3(obj.optDouble("dropX", 0.0).toFloat(), 0f, obj.optDouble("dropZ", 0.0).toFloat())
                    players[bouIdx] = players[bouIdx].copy(isCarriedByBor = false, pos = dp, kidnapImmunityTimer = 1.4f)
                    addCt("DROPPED BOU!", dp, MarigoldGold)
                }
                val bx = obj.optDouble("bx", 0.0).toFloat()
                val bz = obj.optDouble("bz", 0.0).toFloat()
                val bi = bananas.indexOfFirst { hypot(it.pos.x - bx, it.pos.z - bz) < 1.5f }
                if (bi >= 0) bananas.removeAt(bi)
                soundEngine.playBananaSlip()
            }

            "chor_slow_mama" -> {
                soundEngine.playBananaSlip()
                if (chorIdx >= 0) players[chorIdx] = players[chorIdx].copy(mamaSlowUsed = true)
                if (mamaIdx >= 0) {
                    players[mamaIdx] = players[mamaIdx].copy(slowTimer = 5.0f)
                    shockwaves.add(ShockwaveEffect(nextEffectId++, players[mamaIdx].pos))
                    addCt("SLOWED 5s!", players[mamaIdx].pos, Color(0xFFB388FF))
                }
                bannerText = "CHOR CURSED MAMA! 55% SLOWER FOR 5s (1x USED)!"
                bannerTimer = 3.0f
            }

            "kidnap_bou" -> {
                if (borIdx >= 0 && players[borIdx].isCarryingBou) {
                    players[borIdx] = players[borIdx].copy(isCarryingBou = false, holdType = HoldActionType.NONE, holdProgress = 0f)
                }
                if (chorIdx >= 0) {
                    players[chorIdx] = players[chorIdx].copy(isCarryingBou = true, holdType = HoldActionType.NONE, holdProgress = 0f)
                    screamBeacon = ScreamBeacon(players[chorIdx].pos, 8.0f)
                    addCt("SACKED BOU!", players[chorIdx].pos, BridalCrimson)
                }
                if (bouIdx >= 0) {
                    players[bouIdx] = players[bouIdx].copy(isCarriedInSack = true, isCarriedByBor = false, auraExposed = false, campTimer = 0f)
                }
                soundEngine.playScream()
                bannerText = "CHOR KIDNAPPED BOU! BLOCK THE 3 ESCAPE GATES!"
                bannerTimer = 3.8f
                shake = 0.55f
            }

            "rescue_bou" -> {
                val freedPos = Vec3(obj.optDouble("freedX", 0.0).toFloat(), 0f, obj.optDouble("freedZ", 0.0).toFloat())
                if (chorIdx >= 0) {
                    players[chorIdx] = players[chorIdx].copy(
                        isCarryingBou = false,
                        stunTimer = maxOf(players[chorIdx].stunTimer, 1.3f),
                        holdType = HoldActionType.NONE,
                        holdProgress = 0f
                    )
                }
                if (bouIdx >= 0) {
                    players[bouIdx] = players[bouIdx].copy(
                        isCarriedInSack = false,
                        isCarriedByBor = false,
                        pos = freedPos,
                        kidnapImmunityTimer = 2.5f
                    )
                }
                soundEngine.playTaskComplete()
                addCt("BOU RESCUED!", freedPos, CngEmerald)
                bannerText = "BOR RESCUED BOU FROM CHOR'S SACK!"
                bannerTimer = 2.8f
            }

            "bor_pickup_bou" -> {
                if (borIdx >= 0) {
                    players[borIdx] = players[borIdx].copy(isCarryingBou = true, holdType = HoldActionType.NONE, holdProgress = 0f)
                    addCt("CARRYING BOU!", players[borIdx].pos, BorGold)
                }
                if (bouIdx >= 0) {
                    players[bouIdx] = players[bouIdx].copy(isCarriedByBor = true, isCarriedInSack = false, auraExposed = false, campTimer = 0f)
                }
                soundEngine.playTaskComplete()
                bannerText = "JAMAI CARRYING BOU! SIT IN ANY VEHICLE FOR 3s TO WIN!"
                bannerTimer = 3.4f
            }

            "bou_hop_off" -> {
                val hp = Vec3(obj.optDouble("hopX", 0.0).toFloat(), 0f, obj.optDouble("hopZ", 0.0).toFloat())
                if (bouIdx >= 0) {
                    players[bouIdx] = players[bouIdx].copy(isCarriedByBor = false, pos = hp)
                    addCt("HOPPED DOWN!", hp, MarigoldGold)
                }
                if (borIdx >= 0) {
                    players[borIdx] = players[borIdx].copy(isCarryingBou = false, holdType = HoldActionType.NONE, holdProgress = 0f)
                }
            }

            "bor_guard" -> {
                if (borIdx >= 0) players[borIdx] = players[borIdx].copy(speedBoostTimer = maxOf(players[borIdx].speedBoostTimer, 3.5f))
                if (bouIdx >= 0) {
                    players[bouIdx] = players[bouIdx].copy(
                        speedBoostTimer = maxOf(players[bouIdx].speedBoostTimer, 3.5f),
                        kidnapImmunityTimer = maxOf(players[bouIdx].kidnapImmunityTimer, 4.0f)
                    )
                    shockwaves.add(ShockwaveEffect(nextEffectId++, players[bouIdx].pos))
                    addCt("JAMAI GUARD! +SHIELD", players[bouIdx].pos, BorGold)
                }
                soundEngine.playTaskComplete()
                bannerText = "JAMAI ESCORTING BOU! SHIELD + COUPLE SPEED BOOST!"
                bannerTimer = 2.6f
            }

            "flash_cone" -> {
                val p = Vec3(obj.optDouble("x", 0.0).toFloat(), 0f, obj.optDouble("z", 0.0).toFloat())
                flashCones.add(FlashConeEffect(nextEffectId++, p, obj.optDouble("yaw", 0.0).toFloat()))
                soundEngine.playCameraFlash()
            }

            "chor_hit" -> {
                val cause = obj.optString("cause", "bonk")
                val stun = obj.optDouble("stun", 0.0).toFloat()
                val blind = obj.optDouble("blind", 0.0).toFloat()
                val wallHack = obj.optDouble("wallHack", 0.0).toFloat()
                val dropped = obj.optBoolean("droppedBou", false)
                if (chorIdx >= 0) {
                    val c = players[chorIdx]
                    players[chorIdx] = c.copy(
                        stunTimer = if (stun > 0f) maxOf(c.stunTimer, stun) else c.stunTimer,
                        blindTimer = if (blind > 0f) maxOf(c.blindTimer, blind) else c.blindTimer,
                        wallHackTimer = if (wallHack > 0f) maxOf(c.wallHackTimer, wallHack) else c.wallHackTimer,
                        isCarryingBou = if (dropped) false else c.isCarryingBou,
                        holdType = HoldActionType.NONE,
                        holdProgress = 0f
                    )
                    when (cause) {
                        "bonk" -> {
                            soundEngine.playBonk()
                            addCt("DHISHUM!! BONK!", c.pos, MamaOrange)
                            bannerText = if (dropped) "MAMA BONKED CHOR & DROPPED BOU!" else "DHISHUM! MAMA LANDED A BAMBOO HIT!"
                            shake = 0.95f
                        }
                        "sandal" -> {
                            soundEngine.playSandalHit()
                            addCt("SANDAL STUN! 1.5s", c.pos, BridalCrimson)
                            bannerText = "BOU HIT CHOR WITH A HIGH-HEEL SANDAL!"
                            shake = 0.45f
                        }
                        "flash" -> {
                            addCt("FLASHED!", c.pos, FairyCyan)
                            bannerText = "PHOTOGRAPHER FLASHED CHOR! REVEALED FOR 10s!"
                            shake = 0.45f
                        }
                    }
                    bannerTimer = 3.0f
                }
                if (dropped && bouIdx >= 0) {
                    val dp = Vec3(obj.optDouble("dropX", 0.0).toFloat(), 0f, obj.optDouble("dropZ", 0.0).toFloat())
                    players[bouIdx] = players[bouIdx].copy(isCarriedInSack = false, pos = dp, kidnapImmunityTimer = 2.5f)
                }
            }

            "task_done" -> {
                val ti = obj.optInt("idx", -1)
                if (ti in tasks.indices) {
                    tasks[ti] = tasks[ti].copy(progress = 1f, completed = true)
                    soundEngine.playTaskComplete()
                    addCt("${tasks[ti].type.label.uppercase()} DONE!", tasks[ti].pos, MarigoldGold)
                    bannerText = "BOU COMPLETED ${tasks[ti].type.label.uppercase()}!"
                    bannerTimer = 2.4f
                }
            }

            "match_end" -> {
                val team = runCatching { Team.valueOf(obj.optString("team", Team.WEDDING.name)) }.getOrDefault(Team.WEDDING)
                val viaStr = obj.optString("via", "")
                val via = if (viaStr.isNotEmpty()) runCatching { VehicleType.valueOf(viaStr) }.getOrNull() else null
                val jamaiWin = obj.optBoolean("jamai", false)
                val rem = obj.optDouble("time", world.timeRemainingSec.toDouble()).toFloat()
                val res = buildMatchResult(team, via, rem, players, jamaiBidayiWin = jamaiWin)
                val localWon = players.firstOrNull { it.isLocal }?.role?.team == team
                if (localWon) soundEngine.playVictoryFanfare() else soundEngine.playDefeatFanfare()
                onMatchFinished(res)
            }
        }

        return world.copy(
            players = players,
            tasks = tasks,
            sandals = sandals,
            bananas = bananas,
            shockwaves = shockwaves,
            screamBeacon = screamBeacon,
            flashCones = flashCones,
            combatTexts = combatTexts,
            cameraShake = shake,
            bannerAlertText = bannerText,
            bannerAlertTimer = bannerTimer
        )
    }

    /**
     * Smooth circle-to-circle push-apart separation between all active characters (radius = 0.85 units each,
     * min center spacing = 1.70 units) so players and bots can never walk inside or fuse into one another.
     * Skips Bou while she is actively carried by Chor or Bor.
     */
    private fun resolveCharacterSeparation(players: MutableList<PlayerEntity>) {
        val charRadius = 0.85f
        val minDist = charRadius * 2f
        val minDistSq = minDist * minDist
        val count = players.size
        if (count < 2) return

        repeat(2) {
            for (i in 0 until count) {
                val pA = players[i]
                if (pA.isCarriedInSack || pA.isCarriedByBor) continue
                for (j in i + 1 until count) {
                    val pB = players[j]
                    if (pB.isCarriedInSack || pB.isCarriedByBor) continue

                    val dx = pB.pos.x - pA.pos.x
                    val dz = pB.pos.z - pA.pos.z
                    val distSq = dx * dx + dz * dz
                    if (distSq < minDistSq) {
                        val dist: Float
                        val nx: Float
                        val nz: Float
                        if (distSq > 1e-6f) {
                            dist = kotlin.math.sqrt(distSq)
                            nx = dx / dist
                            nz = dz / dist
                        } else {
                            // Deterministic fallback angle if two characters ever start on identical coords
                            dist = 0f
                            val ang = i * 1.91f + j * 2.73f
                            nx = cos(ang)
                            nz = sin(ang)
                        }
                        val pushHalf = (minDist - dist) * 0.5f + 0.005f
                        val newPosA = VenueMap.resolveMovement(
                            Vec3(pA.pos.x - nx * pushHalf, pA.pos.y, pA.pos.z - nz * pushHalf)
                        )
                        val newPosB = VenueMap.resolveMovement(
                            Vec3(pB.pos.x + nx * pushHalf, pB.pos.y, pB.pos.z + nz * pushHalf)
                        )
                        players[i] = players[i].copy(pos = newPosA)
                        players[j] = players[j].copy(pos = newPosB)
                    }
                }
            }
        }
    }

    private fun tickCapturePulse(dt: Float) {
        pulseSoundTimer -= dt
        if (pulseSoundTimer <= 0f) {
            pulseSoundTimer = 0.42f
            soundEngine.playCapturePulse()
        }
    }

    private fun triggerBotSpeech(players: MutableList<PlayerEntity>, index: Int, text: String, channel: ChatChannel) {
        val p = players[index]
        players[index] = p.copy(speechBubbleText = text, speechBubbleTimer = 4.0f)
        onBotChat(players[index], text, channel)
    }

    private fun emitContextualBotChat(players: MutableList<PlayerEntity>, gates: List<EscapeGate>) {
        val chor = players.firstOrNull { it.role == Role.BOU_CHOR } ?: return
        val bots = players.mapIndexedNotNull { idx, p -> if (p.isBot) idx to p else null }
        if (bots.isEmpty()) return

        if (chor.isCarryingBou) {
            val cngGate = gates.firstOrNull { it.type == VehicleType.CNG }
            val nearCng = cngGate != null && chor.pos.dist2D(cngGate.pos) < 35f
            val speaker = bots.firstOrNull { it.second.role == Role.PHOTOGRAPHER || it.second.role == Role.BOR }
            if (speaker != null && nearCng) {
                triggerBotSpeech(players, speaker.first, "চোর CNG-র দিকে যাইতেছে!", ChatChannel.ALL)
                return
            }
            val bouBot = bots.firstOrNull { it.second.role == Role.BOU }
            if (bouBot != null) {
                triggerBotSpeech(players, bouBot.first, "বাঁচাও! চোর ধরছে!", ChatChannel.ALL)
                return
            }
        }

        val (idx, bot) = bots.random()
        val phrase = when (bot.role) {
            Role.BOU_CHOR -> "হাহা, ধরতে পারলি না!"
            Role.MAMA -> "মামা বাঁশ নিয়া তাড়াতাড়ি আয়!"
            Role.PHOTOGRAPHER -> "চোর CNG-র দিকে যাইতেছে!"
            Role.BOU -> if (bot.pos.dist2D(chor.pos) < 16f) "বাঁচাও! চোর ধরছে!" else "মামা বাঁশ নিয়া তাড়াতাড়ি আয়!"
            Role.BOR -> "মামা বাঁশ নিয়া তাড়াতাড়ি আয়!"
        }
        triggerBotSpeech(players, idx, phrase, ChatChannel.ALL)
    }

    private data class PlayerActionInput(
        val moveX: Float = 0f,
        val moveZ: Float = 0f,
        val triggerAbility1: Boolean = false,
        val holdAction: Boolean = false,
        val triggerAbility2: Boolean = false,
        val triggerAbility3: Boolean = false
    )

    private fun computeSmartBotInput(
        botIndex: Int,
        bot: PlayerEntity,
        players: List<PlayerEntity>,
        tasks: List<TaskStation>,
        gates: List<EscapeGate>,
        dt: Float,
        outdoorVisibilityFactor: Float
    ): PlayerActionInput {
        val bou = players.firstOrNull { it.role == Role.BOU } ?: return PlayerActionInput()
        val chor = players.firstOrNull { it.role == Role.BOU_CHOR } ?: return PlayerActionInput()
        val visScale = if (!VenueMap.isInsideBuilding(bot.pos) || !VenueMap.isInsideBuilding(chor.pos)) {
            outdoorVisibilityFactor
        } else 1f
        // Stagger & throttle 7-ray obstacle avoidance to every 3rd frame per bot for minimal CPU load
        val runRayCheck = (simFrameCount + botIndex) % 3 == 0

        fun steerToward(goal: Vec3) {
            if (runRayCheck || !botCachedSteerX.containsKey(bot.id)) {
                val nextPt = VenueMap.nextSteerTarget(bot.pos, goal)
                computeMultiRayAvoidance(bot.pos, nextPt.x - bot.pos.x, nextPt.z - bot.pos.z)
                botCachedSteerX[bot.id] = scratchSteerX
                botCachedSteerZ[bot.id] = scratchSteerZ
            } else {
                scratchSteerX = botCachedSteerX[bot.id] ?: 0f
                scratchSteerZ = botCachedSteerZ[bot.id] ?: 0f
            }
        }

        // Detect if bot barely moved to trigger clean doorway re-routing
        val prevPos = botLastPositions[bot.id]
        if (prevPos != null && bot.pos.dist2D(prevPos) < 0.04f) {
            botStuckTimers[bot.id] = (botStuckTimers[bot.id] ?: 0f) + dt
        } else {
            botStuckTimers[bot.id] = 0f
        }
        botLastPositions[bot.id] = bot.pos
        val isStuck = (botStuckTimers[bot.id] ?: 0f) > 0.7f
        if (isStuck) {
            botWanderTargets[bot.id] = VenueMap.navWaypoints.random()
            botCachedSteerX.remove(bot.id)
            botStuckTimers[bot.id] = 0f
        }

        return when (bot.role) {
            Role.BOU -> {
                val distChor = bot.pos.dist2D(chor.pos)
                if (distChor < 16f * visScale && VenueMap.hasLineOfSight(bot.pos, chor.pos)) {
                    val awayTarget = VenueMap.navWaypoints.maxByOrNull { it.dist2D(chor.pos) - it.dist2D(bot.pos) * 0.4f } ?: Vec3()
                    steerToward(awayTarget)
                    val throwSandal = distChor < 10.5f * visScale && bot.ability2Cooldown <= 0f
                    val scream = distChor < 7.5f && bot.ability1Cooldown <= 0f
                    PlayerActionInput(scratchSteerX, scratchSteerZ, triggerAbility1 = scream, triggerAbility2 = throwSandal)
                } else {
                    val targetTask = tasks.firstOrNull { !it.completed }
                    steerToward(targetTask?.pos ?: getOrPickWanderTarget(bot))
                    PlayerActionInput(scratchSteerX, scratchSteerZ)
                }
            }

            Role.BOU_CHOR -> {
                val nearestChaser = players
                    .filter { it.role != Role.BOU_CHOR && it.role != Role.BOU && !it.isIncapacitated }
                    .minByOrNull { it.pos.dist2D(bot.pos) }
                val chaserDist = nearestChaser?.pos?.dist2D(bot.pos) ?: 99f
                val mama = players.firstOrNull { it.role == Role.MAMA }
                val slowMamaNow = !bot.mamaSlowUsed && mama != null && !mama.isIncapacitated && mama.pos.dist2D(bot.pos) < 11f
                val dropBanana = chaserDist < 5.5f && bot.ability2Cooldown <= 0f
                val distBou = bot.pos.dist2D(bou.pos)
                val triggerDash = (chaserDist < 6.5f || (distBou > 8f && !bot.isCarryingBou)) && bot.ability1Cooldown <= 0f

                if (bot.isCarryingBou) {
                    val bestGate = gates.maxByOrNull { gate ->
                        val chaserD = nearestChaser?.pos?.dist2D(gate.pos) ?: 40f
                        chaserD - bot.pos.dist2D(gate.pos) * 0.72f
                    } ?: gates.first()
                    val distGate = bot.pos.dist2D(bestGate.pos)
                    if (distGate <= bestGate.radius - 0.4f) {
                        PlayerActionInput(0f, 0f, holdAction = true, triggerAbility1 = triggerDash, triggerAbility2 = dropBanana, triggerAbility3 = slowMamaNow)
                    } else {
                        steerToward(bestGate.pos)
                        PlayerActionInput(scratchSteerX, scratchSteerZ, holdAction = distGate <= bestGate.radius, triggerAbility1 = triggerDash, triggerAbility2 = dropBanana, triggerAbility3 = slowMamaNow)
                    }
                } else {
                    val bouVisibleInFog = bou.auraExposed || distBou < 24f * visScale
                    if (distBou <= 2.9f && bou.kidnapImmunityTimer <= 0f) {
                        val closeIn = if (distBou > 1.85f) 0.45f else 0f
                        PlayerActionInput(
                            (bou.pos.x - bot.pos.x) * closeIn,
                            (bou.pos.z - bot.pos.z) * closeIn,
                            holdAction = true,
                            triggerAbility1 = triggerDash,
                            triggerAbility2 = dropBanana,
                            triggerAbility3 = slowMamaNow
                        )
                    } else {
                        val chaseGoal = if (bouVisibleInFog) Vec3(bou.pos.x, 0f, bou.pos.z) else getOrPickWanderTarget(bot)
                        steerToward(chaseGoal)
                        PlayerActionInput(scratchSteerX, scratchSteerZ, triggerAbility1 = triggerDash, triggerAbility2 = dropBanana, triggerAbility3 = slowMamaNow)
                    }
                }
            }

            Role.BOR -> {
                val distChor = bot.pos.dist2D(chor.pos)
                val distBou = bot.pos.dist2D(bou.pos)
                val chorNearBou = chor.pos.dist2D(bou.pos) <= 13.0f
                if (chor.isCarryingBou) {
                    val sprintToRescue = distChor > 5.0f && bot.ability2Cooldown <= 0f
                    if (distChor <= 3.1f) {
                        val closeIn = if (distChor > 1.95f) 0.5f else 0f
                        PlayerActionInput(
                            (chor.pos.x - bot.pos.x) * closeIn,
                            (chor.pos.z - bot.pos.z) * closeIn,
                            holdAction = true
                        )
                    } else {
                        steerToward(Vec3(chor.pos.x, 0f, chor.pos.z))
                        PlayerActionInput(
                            scratchSteerX,
                            scratchSteerZ,
                            holdAction = distChor <= 3.6f,
                            triggerAbility2 = sprintToRescue
                        )
                    }
                } else if (bot.isCarryingBou) {
                    // Drive Bou safely to the best escape vehicle away from Chor
                    val bestGate = gates.maxByOrNull { it.pos.dist2D(chor.pos) - it.pos.dist2D(bot.pos) * 0.65f } ?: gates.first()
                    val distGate = bot.pos.dist2D(bestGate.pos)
                    val sprintCar = distGate > 8f && bot.ability2Cooldown <= 0f
                    if (distGate <= bestGate.radius - 0.4f) {
                        PlayerActionInput(0f, 0f, holdAction = true, triggerAbility2 = sprintCar)
                    } else {
                        steerToward(bestGate.pos)
                        PlayerActionInput(scratchSteerX, scratchSteerZ, holdAction = distGate <= bestGate.radius, triggerAbility2 = sprintCar)
                    }
                } else {
                    val guardNow = distBou <= 4.5f && chorNearBou && bot.ability2Cooldown <= 0f
                    // If Bou is also a Bot and Chor is stunned/far, Bot Jamai attempts Bidayi carry-to-car
                    val shouldTryCarry = bou.isBot && !bou.isCarriedInSack && !bou.isCarriedByBor && (chor.isIncapacitated || distChor > 16f)
                    if (shouldTryCarry && distBou <= 3.2f) {
                        PlayerActionInput(0f, 0f, holdAction = true, triggerAbility2 = guardNow)
                    } else if (distBou > 4.0f) {
                        steerToward(Vec3(bou.pos.x, 0f, bou.pos.z))
                        val sprintToBou = distBou > 12f && chorNearBou && bot.ability2Cooldown <= 0f
                        PlayerActionInput(scratchSteerX, scratchSteerZ, triggerAbility2 = sprintToBou)
                    } else if (chorNearBou) {
                        steerToward(Vec3(bou.pos.x, 0f, bou.pos.z))
                        PlayerActionInput(scratchSteerX * 0.5f, scratchSteerZ * 0.5f, triggerAbility2 = guardNow)
                    } else {
                        steerToward(getOrPickWanderTarget(bot))
                        PlayerActionInput(scratchSteerX * 0.65f, scratchSteerZ * 0.65f, triggerAbility2 = guardNow)
                    }
                }
            }

            Role.MAMA -> {
                val distChor = bot.pos.dist2D(chor.pos)
                val knowsChor = chor.isCarryingBou || chor.wallHackTimer > 0f || distChor < 24f * visScale
                val bashNow = distChor <= 3.9f && bot.ability1Cooldown <= 0f
                if (knowsChor && distChor <= 2.2f) {
                    PlayerActionInput(0f, 0f, triggerAbility1 = bashNow)
                } else {
                    val rawGoal = if (knowsChor) Vec3(chor.pos.x, 0f, chor.pos.z) else getOrPickWanderTarget(bot)
                    steerToward(rawGoal)
                    PlayerActionInput(scratchSteerX, scratchSteerZ, triggerAbility1 = bashNow)
                }
            }

            Role.PHOTOGRAPHER -> {
                val distChor = bot.pos.dist2D(chor.pos)
                val knowsChor = chor.isCarryingBou || chor.wallHackTimer > 0f || distChor < 26f * visScale
                val flashNow = distChor in 2.5f..(12.5f * visScale).coerceAtLeast(5.5f) &&
                    bot.ability1Cooldown <= 0f && VenueMap.hasLineOfSight(bot.pos, chor.pos)
                if (knowsChor && distChor < 5.5f && VenueMap.hasLineOfSight(bot.pos, chor.pos)) {
                    // Maintain photo-flash distance instead of walking into Chor
                    PlayerActionInput(0f, 0f, triggerAbility1 = flashNow)
                } else {
                    val rawGoal = if (knowsChor) Vec3(chor.pos.x, 0f, chor.pos.z) else getOrPickWanderTarget(bot)
                    steerToward(rawGoal)
                    PlayerActionInput(scratchSteerX, scratchSteerZ, triggerAbility1 = flashNow)
                }
            }
        }
    }

    private fun getOrPickWanderTarget(bot: PlayerEntity): Vec3 {
        val current = botWanderTargets[bot.id]
        if (current == null || bot.pos.dist2D(current) < 3.5f) {
            val next = VenueMap.navWaypoints.random()
            botWanderTargets[bot.id] = next
            return next
        }
        return current
    }

    /**
     * Zero-allocation multi-ray forward feeler obstacle avoidance:
     * Reuses pre-allocated rayOffsets and writes results into scratchSteerX/scratchSteerZ.
     */
    private fun computeMultiRayAvoidance(pos: Vec3, dirX: Float, dirZ: Float) {
        val len = hypot(dirX, dirZ)
        if (len < 0.05f) {
            scratchSteerX = 0f
            scratchSteerZ = 0f
            return
        }
        val baseAngle = atan2(dirX, dirZ)
        var bestAngle = baseAngle
        var bestScore = -999f

        for (i in rayOffsets.indices) {
            val offset = rayOffsets[i]
            val ang = baseAngle + offset
            val rx = sin(ang)
            val rz = cos(ang)
            val nearHit = VenueMap.hitsObstacle(pos.x + rx * 1.55f, pos.z + rz * 1.55f, 0.72f)
            val midHit = VenueMap.hitsObstacle(pos.x + rx * 3.1f, pos.z + rz * 3.1f, 0.65f)
            val clearanceScore = when {
                !nearHit && !midHit -> 3.0f
                !nearHit -> 1.2f
                else -> -4.0f
            }
            val alignScore = cos(offset) * 1.6f
            val total = clearanceScore + alignScore
            if (total > bestScore) {
                bestScore = total
                bestAngle = ang
            }
        }
        scratchSteerX = sin(bestAngle)
        scratchSteerZ = cos(bestAngle)
    }

    private fun buildMatchResult(
        winningTeam: Team,
        escapedVia: VehicleType?,
        timeRemaining: Float,
        players: List<PlayerEntity>,
        jamaiBidayiWin: Boolean = false
    ): MatchResult {
        val mama = players.firstOrNull { it.role == Role.MAMA }
        val bor = players.firstOrNull { it.role == Role.BOR }
        val photo = players.firstOrNull { it.role == Role.PHOTOGRAPHER }
        val bou = players.firstOrNull { it.role == Role.BOU }
        val chor = players.firstOrNull { it.role == Role.BOU_CHOR }

        val awards = buildList {
            if (mama != null) {
                add(MvpAward("Best Bamboo Hit", mama.name, Role.MAMA, "${stats.bambooHits} DHISHUM Bonks (${stats.bambooDropSaves} Saves)"))
            }
            if (jamaiBidayiWin && bor != null) {
                add(MvpAward("Bidayi Hero", bor.name, Role.BOR, "Carried Bou to ${escapedVia?.label ?: "Wedding Car"} in 3s!"))
            } else if (bor != null) {
                val rescueText = stats.fastestRescueSec?.let { String.format("%.1fs Fastest", it) } ?: "${stats.rescuesCount} Rescues"
                add(MvpAward("Fastest Rescue", bor.name, Role.BOR, rescueText))
            }
            if (photo != null) {
                add(MvpAward("Flash Paparazzi", photo.name, Role.PHOTOGRAPHER, "${stats.flashHits} Chor Blinds"))
            }
            if (bou != null && stats.sandalHits > 0) {
                add(MvpAward("Sandal Sniper", bou.name, Role.BOU, "${stats.sandalHits} High-Heel Stuns • ${stats.bouTasksDone}/3 Tasks"))
            } else if (chor != null) {
                add(MvpAward("Chaotic Bandit", chor.name, Role.BOU_CHOR, "${stats.kidnapsCount} Sacks • ${stats.bananaSlips} Banana Slips"))
            }
        }

        val headline = when {
            jamaiBidayiWin -> "JAMAI ESCAPED WITH BOU!"
            winningTeam == Team.THIEF -> "CHOR ESCAPED WITH BOU!"
            else -> "BIYE BARI SAVED!"
        }
        val subtitle = when {
            jamaiBidayiWin -> "Grand Bidayi! Jamai carried Bou safely into the ${escapedVia?.label ?: "Getaway Vehicle"} for 3s!"
            winningTeam == Team.THIEF -> "Zoomed away down Dhaka street in the ${escapedVia?.label ?: "Getaway Vehicle"}!"
            else -> "The Groom's squad defended all 4 wedding zones until time ran out!"
        }

        return MatchResult(
            winningTeam = winningTeam,
            headline = headline,
            subtitle = subtitle,
            escapedVia = escapedVia,
            timeRemainingSec = timeRemaining,
            awards = awards,
            playerRoles = players.map { it.name to it.role }
        )
    }

    /** Frame-rate independent exponential damping (identical to THREE.MathUtils.damp). */
    private fun damp(current: Float, target: Float, lambda: Float, dt: Float): Float {
        return current + (target - current) * (1f - exp(-lambda * dt))
    }

    private fun dampAngle(current: Float, target: Float, lambda: Float, dt: Float): Float {
        val diff = normalizeAngle(target - current)
        return current + diff * (1f - exp(-lambda * dt))
    }

    private fun normalizeAngle(a: Float): Float {
        var r = a
        while (r > PI) r -= (2 * PI).toFloat()
        while (r < -PI) r += (2 * PI).toFloat()
        return r
    }
}
