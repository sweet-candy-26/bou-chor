package com.example.game

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.BorGold
import com.example.ui.theme.BridalCrimson
import com.example.ui.theme.ChorCharcoal
import com.example.ui.theme.MamaOrange
import com.example.ui.theme.PhotoTeal
import kotlin.math.hypot
import kotlin.math.sqrt

data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val len = length()
        return if (len > 1e-5f) Vec3(x / len, y / len, z / len) else Vec3(0f, 1f, 0f)
    }
    fun dist2D(o: Vec3): Float = hypot(x - o.x, z - o.z)
}

enum class Team {
    WEDDING,
    THIEF
}

enum class Role(
    val displayName: String,
    val badgeName: String,
    val bengaliTitle: String,
    val team: Team,
    val accentColor: Color,
    val baseSpeed: Float,
    val ability1Name: String,
    val ability2Name: String?,
    val shortGoal: String
) {
    BOU(
        displayName = "BOU",
        badgeName = "BOU",
        bengaliTitle = "Bride • কনে",
        team = Team.WEDDING,
        accentColor = BridalCrimson,
        baseSpeed = 7.6f,
        ability1Name = "SCREAM",
        ability2Name = "SANDAL",
        shortGoal = "Complete Stage, VIP Bridal & Kacchi tasks — dodge Chor!"
    ),
    BOU_CHOR(
        displayName = "CHOR",
        badgeName = "CHOR",
        bengaliTitle = "Thief • বউ চোর",
        team = Team.THIEF,
        accentColor = ChorCharcoal,
        baseSpeed = 8.9f,
        ability1Name = "KIDNAP",
        ability2Name = "BANANA",
        shortGoal = "Sack Bou (2s hold) & escape via CNG, Microbus or Rickshaw!"
    ),
    BOR(
        displayName = "BOR",
        badgeName = "BOR",
        bengaliTitle = "Groom • বর",
        team = Team.WEDDING,
        accentColor = BorGold,
        baseSpeed = 10.1f,
        ability1Name = "RESCUE",
        ability2Name = null,
        shortGoal = "Sprint across the venue & hold Rescue (1.5s) to free Bou!"
    ),
    MAMA(
        displayName = "MAMA",
        badgeName = "MAMA",
        bengaliTitle = "Angry Uncle • মামা",
        team = Team.WEDDING,
        accentColor = MamaOrange,
        baseSpeed = 8.6f,
        ability1Name = "BASH!",
        ability2Name = null,
        shortGoal = "Swing Bamboo Stick to BONK Chor (3s stun + drops Bou)!"
    ),
    PHOTOGRAPHER(
        displayName = "PHOTOGRAPHER",
        badgeName = "PHOTO",
        bengaliTitle = "DSLR Pro • ফটোগ্রাফার",
        team = Team.WEDDING,
        accentColor = PhotoTeal,
        baseSpeed = 8.9f,
        ability1Name = "FLASH",
        ability2Name = null,
        shortGoal = "Blind Chor (2.5s) & reveal through walls for 10s!"
    );

    fun hasTeamRadio(): Boolean = this == BOR || this == MAMA || this == PHOTOGRAPHER
}

enum class HoldActionType {
    NONE,
    KIDNAP,
    ESCAPE,
    RESCUE
}

data class PlayerEntity(
    val id: String,
    val name: String,
    val role: Role,
    val isBot: Boolean,
    val isLocal: Boolean,
    val pos: Vec3,
    val velX: Float = 0f,
    val velZ: Float = 0f,
    val yaw: Float = 0f,
    val walkPhase: Float = 0f,
    val currentSpeed: Float = 0f,
    val isMoving: Boolean = false,
    val stunTimer: Float = 0f,
    val slipTimer: Float = 0f,
    val blindTimer: Float = 0f,
    val wallHackTimer: Float = 0f,
    val swingAnim: Float = 0f,
    val isCarryingBou: Boolean = false,
    val isCarriedInSack: Boolean = false,
    val kidnapImmunityTimer: Float = 0f,
    val ability1Cooldown: Float = 0f,
    val ability1MaxCooldown: Float = 1f,
    val ability2Cooldown: Float = 0f,
    val ability2MaxCooldown: Float = 1f,
    val holdType: HoldActionType = HoldActionType.NONE,
    val holdProgress: Float = 0f,
    val speakingLevel: Float = 0f,
    val speechBubbleText: String? = null,
    val speechBubbleTimer: Float = 0f,
    val campAnchorX: Float = 0f,
    val campAnchorZ: Float = 0f,
    val campTimer: Float = 0f,
    val auraExposed: Boolean = false
) {
    val isIncapacitated: Boolean get() = stunTimer > 0f || slipTimer > 0f
}

enum class TaskType(val label: String, val iconTag: String) {
    STAGE("Grand Stage", "STAGE"),
    VIP_MAKEUP("VIP Bridal Room", "VIP"),
    KACCHI_TABLE("Kacchi Buffet", "KACCHI")
}

data class TaskStation(
    val type: TaskType,
    val pos: Vec3,
    val radius: Float = 4.2f,
    val progress: Float = 0f,
    val completed: Boolean = false
)

enum class VehicleType(val label: String, val shortTag: String) {
    CNG("Green CNG", "CNG"),
    MICROBUS("Black Microbus", "MICROBUS"),
    RICKSHAW("Decorated Rickshaw", "RICKSHAW")
}

data class EscapeGate(
    val type: VehicleType,
    val pos: Vec3,
    val radius: Float = 5.2f
)

data class SandalProjectile(
    val id: Long,
    val ownerId: String,
    val pos: Vec3,
    val vx: Float,
    val vz: Float,
    val spin: Float = 0f,
    val life: Float = 1.6f
)

data class BananaPeel(
    val id: Long,
    val pos: Vec3,
    val life: Float = 28f
)

data class ShockwaveEffect(
    val id: Long,
    val origin: Vec3,
    val radius: Float = 0.8f,
    val maxRadius: Float = 46f,
    val alpha: Float = 1f
)

data class ScreamBeacon(
    val pos: Vec3,
    val timeLeft: Float
)

data class FlashConeEffect(
    val id: Long,
    val origin: Vec3,
    val yaw: Float,
    val life: Float = 0.52f
)

data class DustParticle(
    val id: Long,
    val pos: Vec3,
    val vx: Float,
    val vy: Float,
    val vz: Float,
    val size: Float,
    val life: Float,
    val maxLife: Float
)

data class FloatingCombatText(
    val id: Long,
    val text: String,
    val pos: Vec3,
    val color: Color,
    val life: Float = 1.5f,
    val maxLife: Float = 1.5f
)

enum class WeatherType(
    val displayName: String,
    val shortLabel: String,
    val outdoorSpeedMultiplier: Float,
    val outdoorVisibilityFactor: Float,
    val bannerMessage: String
) {
    CLEAR_EVENING(
        displayName = "Clear Evening",
        shortLabel = "CLEAR",
        outdoorSpeedMultiplier = 1.0f,
        outdoorVisibilityFactor = 1.0f,
        bannerMessage = "Clear Evening Skies Over the Courtyard!"
    ),
    MONSOON_RAIN(
        displayName = "Monsoon Rain",
        shortLabel = "RAIN",
        outdoorSpeedMultiplier = 0.85f,
        outdoorVisibilityFactor = 0.76f,
        bannerMessage = "Light Monsoon Rain Hits the Courtyard! Slippery Ground!"
    ),
    FOGGY_NIGHT(
        displayName = "Foggy Night",
        shortLabel = "FOG",
        outdoorSpeedMultiplier = 0.91f,
        outdoorVisibilityFactor = 0.42f,
        bannerMessage = "Dense Night Fog Rolls Over the Courtyard! Low Visibility!"
    )
}

data class WeatherRipple(
    val id: Long,
    val pos: Vec3,
    val age: Float = 0f,
    val maxAge: Float = 0.78f,
    val maxRadius: Float = 1.55f
)

enum class GraphicsQuality(
    val label: String,
    val pixelRatioCap: Float,
    val enableAntialias: Boolean
) {
    SMOOTH(label = "SMOOTH", pixelRatioCap = 1.15f, enableAntialias = false),
    HD(label = "HD", pixelRatioCap = 1.75f, enableAntialias = true)
}

enum class ChatChannel(val tag: String) {
    ALL("All"),
    TEAM("Team")
}

data class ChatMessage(
    val id: Long,
    val senderId: String,
    val senderName: String,
    val senderRole: Role?,
    val text: String,
    val channel: ChatChannel,
    val timestampMs: Long = System.currentTimeMillis()
)

data class LobbyPlayer(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val isLocal: Boolean,
    val isBot: Boolean = false,
    val micEnabled: Boolean = true,
    val speakingLevel: Float = 0f
)

data class MvpAward(
    val title: String,
    val winnerName: String,
    val role: Role,
    val valueText: String
)

data class MatchResult(
    val winningTeam: Team,
    val headline: String,
    val subtitle: String,
    val escapedVia: VehicleType?,
    val timeRemainingSec: Float,
    val awards: List<MvpAward>,
    val playerRoles: List<Pair<String, Role>>
)

data class MatchStatsTracker(
    var bambooHits: Int = 0,
    var bambooDropSaves: Int = 0,
    var fastestRescueSec: Float? = null,
    var rescuesCount: Int = 0,
    var flashHits: Int = 0,
    var sandalHits: Int = 0,
    var bananaSlips: Int = 0,
    var kidnapsCount: Int = 0,
    var bouTasksDone: Int = 0,
    var lastKidnapTimestampSec: Float = 0f
)

sealed interface ScreenMode {
    data object Title : ScreenMode
    data object Lobby : ScreenMode
    data object Playing : ScreenMode
    data class MatchEnd(val result: MatchResult) : ScreenMode
}

data class GameWorldState(
    val timeRemainingSec: Float = 240f,
    val elapsedTimeSec: Float = 0f,
    val players: List<PlayerEntity> = emptyList(),
    val tasks: List<TaskStation> = emptyList(),
    val gates: List<EscapeGate> = emptyList(),
    val sandals: List<SandalProjectile> = emptyList(),
    val bananas: List<BananaPeel> = emptyList(),
    val shockwaves: List<ShockwaveEffect> = emptyList(),
    val screamBeacon: ScreamBeacon? = null,
    val flashCones: List<FlashConeEffect> = emptyList(),
    val dustParticles: List<DustParticle> = emptyList(),
    val combatTexts: List<FloatingCombatText> = emptyList(),
    val cameraX: Float = 0f,
    val cameraZ: Float = 0f,
    val cameraYaw: Float = 0f,              // Full 360° horizontal orbit angle (radians)
    val cameraPitch: Float = 0.54f,         // Vertical pitch clamped 15°..75° (0.262..1.309 rad)
    val cameraDistance: Float = 13.4f,      // Desired spring-arm zoom distance (8..22)
    val effectiveCameraDistance: Float = 13.4f, // Raycast wall-clamped spring-arm distance
    val cameraShake: Float = 0f,
    val currentZoneName: String = "Grand Wedding Hall",
    val weather: WeatherType = WeatherType.CLEAR_EVENING,
    val weatherTimerSec: Float = 15f,
    val rainIntensity: Float = 0f,           // Smoothly damped 0..1 for monsoon rain & ground ripples
    val fogIntensity: Float = 0f,            // Smoothly damped 0..1 for foggy night visibility reduction
    val outdoorExposure: Float = 0f,         // Smoothly damped 0..1 based on whether local player is outdoors
    val rainRipples: List<WeatherRipple> = emptyList(),
    val bannerAlertText: String? = null,
    val bannerAlertTimer: Float = 0f,
    val roleRevealTimer: Float = 4.0f,
    val isSoloMode: Boolean = true,
    val roomCode: String = ""
) {
    val localPlayer: PlayerEntity? get() = players.firstOrNull { it.isLocal }
    val bouPlayer: PlayerEntity? get() = players.firstOrNull { it.role == Role.BOU }
    val chorPlayer: PlayerEntity? get() = players.firstOrNull { it.role == Role.BOU_CHOR }
    val completedTasksRatio: Float
        get() = if (tasks.isEmpty()) 0f else tasks.sumOf { it.progress.toDouble() }.toFloat() / tasks.size
    val outdoorSpeedMultiplier: Float
        get() = (1f - rainIntensity * 0.15f - fogIntensity * 0.09f).coerceIn(0.82f, 1.0f)
    val outdoorVisibilityFactor: Float
        get() = (1f - rainIntensity * 0.24f - fogIntensity * 0.58f).coerceIn(0.38f, 1.0f)
}

object QuickChatPresets {
    val BENGALI_PHRASES = listOf(
        "বাঁচাও! চোর ধরছে!",
        "মামা বাঁশ নিয়া তাড়াতাড়ি আয়!",
        "চোর CNG-র দিকে যাইতেছে!",
        "হাহা, ধরতে পারলি না!"
    )
}
