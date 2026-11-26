package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.ChatChannel
import com.example.game.GameWorldState
import com.example.game.GraphicsQuality
import com.example.game.HoldActionType
import com.example.game.PlayerEntity
import com.example.game.QuickChatPresets
import com.example.game.Role
import com.example.game.Team
import com.example.game.ThreeDGameViewport
import com.example.game.VehicleType
import com.example.game.VenueMap
import com.example.game.WeatherType
import com.example.ui.BouChorUiState
import com.example.ui.theme.BridalCrimson
import com.example.ui.theme.ChorCharcoal
import com.example.ui.theme.CngEmerald
import com.example.ui.theme.FairyCyan
import com.example.ui.theme.MamaOrange
import com.example.ui.theme.MarigoldGold
import com.example.ui.theme.MaroonCard
import com.example.ui.theme.MaroonNight
import com.example.ui.theme.MaroonSurface
import com.example.ui.theme.WarmIvory
import com.example.ui.theme.WarmMuted
import kotlin.math.hypot

@Composable
fun GameHudScreen(
    uiState: BouChorUiState,
    worldProvider: () -> GameWorldState,
    frameTickProvider: () -> Long,
    onJoystickMove: (Float, Float) -> Unit,
    onKeyboardMove: (Float, Float) -> Unit,
    onCameraOrbit: (Float, Float) -> Unit,
    onCameraZoom: (Float) -> Unit,
    onAbility1Press: () -> Unit,
    onAbility1HoldChanged: (Boolean) -> Unit,
    onAbility2Press: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleTeamRadio: () -> Unit,
    onToggleGraphicsQuality: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onToggleChat: () -> Unit,
    onSelectChatChannel: (ChatChannel) -> Unit,
    onSendChat: (String) -> Unit,
    onExitMatch: () -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }
    var chatDraft by remember { mutableStateOf("") }
    val pressedKeys = remember { mutableSetOf<Key>() }

    BackHandler { showExitConfirm = true }

    val world = uiState.world
    val local = world.localPlayer

    fun updateKeys() {
        var kx = 0f
        var kz = 0f
        if (Key.W in pressedKeys || Key.DirectionUp in pressedKeys) kz -= 1f
        if (Key.S in pressedKeys || Key.DirectionDown in pressedKeys) kz += 1f
        if (Key.A in pressedKeys || Key.DirectionLeft in pressedKeys) kx -= 1f
        if (Key.D in pressedKeys || Key.DirectionRight in pressedKeys) kx += 1f
        onKeyboardMove(kx, kz)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                val isDown = ev.type == KeyEventType.KeyDown
                val isUp = ev.type == KeyEventType.KeyUp
                when (ev.key) {
                    Key.W, Key.A, Key.S, Key.D,
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                        if (isDown) pressedKeys.add(ev.key) else if (isUp) pressedKeys.remove(ev.key)
                        updateKeys()
                        true
                    }
                    Key.Spacebar, Key.E -> {
                        if (isDown) onAbility1HoldChanged(true) else if (isUp) onAbility1HoldChanged(false)
                        true
                    }
                    Key.F -> {
                        if (isDown) onAbility2Press()
                        true
                    }
                    Key.Enter -> {
                        if (isDown && !uiState.isChatExpanded) {
                            onToggleChat()
                            true
                        } else false
                    }
                    else -> false
                }
            }
    ) {
        // 1. Full-Screen Live 60 FPS 3D Biye Bari World + Independent Multi-Touch 360° Orbit & Mouse Scroll Zoom
        ThreeDGameViewport(
            worldProvider = worldProvider,
            frameTickProvider = frameTickProvider,
            graphicsQuality = uiState.graphicsQuality,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var activeOrbitPointerId: PointerId? = null
                        var lastPinchDist: Float? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            // PC Mouse Scroll Wheel Zoom
                            if (event.type == PointerEventType.Scroll) {
                                val scrollY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (scrollY != 0f) {
                                    onCameraZoom(scrollY * 1.15f)
                                    event.changes.forEach { it.consume() }
                                }
                                continue
                            }

                            val pressed = event.changes.filter { it.pressed && !it.isConsumed }
                            if (pressed.size >= 2) {
                                // Two-finger pinch zoom on 3D viewport
                                val p0 = pressed[0].position
                                val p1 = pressed[1].position
                                val dist = hypot(p1.x - p0.x, p1.y - p0.y)
                                val prev = lastPinchDist
                                if (prev != null && dist > 10f) {
                                    val pinchDelta = dist - prev
                                    if (kotlin.math.abs(pinchDelta) > 0.5f) {
                                        onCameraZoom(-pinchDelta * 0.025f)
                                    }
                                }
                                lastPinchDist = dist
                                activeOrbitPointerId = null
                                pressed.forEach { it.consume() }
                            } else if (pressed.size == 1) {
                                lastPinchDist = null
                                val ptr = pressed[0]
                                if (activeOrbitPointerId == null || activeOrbitPointerId != ptr.id) {
                                    activeOrbitPointerId = ptr.id
                                } else {
                                    val delta = ptr.positionChange()
                                    if (delta.x != 0f || delta.y != 0f) {
                                        // Drag right -> look right (yaw decreases); drag down -> tilt camera higher
                                        onCameraOrbit(-delta.x * 0.0068f, delta.y * 0.0048f)
                                        ptr.consume()
                                    }
                                }
                            } else {
                                activeOrbitPointerId = null
                                lastPinchDist = null
                            }
                        }
                    }
                }
        )

        // 2. Funny Semi-Dark "INSIDE SACK!" Overlay when Bou is carried by Bou Chor!
        if (local?.role == Role.BOU && local.isCarriedInSack) {
            InsideSackOverlay()
        }

        // 3. Blinding White Camera Flash Overlay (2.5s fade) when Bou Chor is flashed by Photographer!
        if (local != null && local.blindTimer > 0f) {
            val flashAlpha = (local.blindTimer / 2.5f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = (0.35f + flashAlpha * 0.65f).coerceAtMost(1f))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "FLASHED BY PHOTOGRAPHER!",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFF1A0A10).copy(alpha = flashAlpha)
                )
            }
        }

        // 4. Safe-Area HUD Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(10.dp)
        ) {
            // CLEAN 3-ROW NON-OVERLAPPING TOP HUD
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .testTag("hud_top_container"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // ROW 1 (TOP BAR): Timer, Role Badge, FPS & Graphics Toggle on Left; Radio, Mic, Speaker & Exit on Right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hud_top_row_1"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Countdown Timer Pill (4:00)
                        val mins = (world.timeRemainingSec.toInt() / 60).coerceAtLeast(0)
                        val secs = (world.timeRemainingSec.toInt() % 60).coerceAtLeast(0)
                        val urgent = world.timeRemainingSec < 35f
                        Surface(
                            color = if (urgent) BridalCrimson.copy(alpha = 0.90f) else Color(0xBF14060C),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.25.dp, if (urgent) MarigoldGold else MarigoldGold.copy(alpha = 0.7f)),
                            modifier = Modifier.testTag("hud_timer")
                        ) {
                            Text(
                                text = String.format("%02d:%02d", mins, secs),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = WarmIvory,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }

                        // Role Badge Pill (uses badgeName so Thief always displays "CHOR")
                        if (local != null) {
                            Surface(
                                color = local.role.accentColor.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.25.dp, WarmIvory.copy(alpha = 0.75f)),
                                modifier = Modifier.testTag("hud_role_badge")
                            ) {
                                Text(
                                    text = local.role.badgeName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (local.role == Role.BOR) MaroonNight else WarmIvory,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Live FPS Counter
                        val fpsColor = when {
                            uiState.currentFps >= 50 -> CngEmerald
                            uiState.currentFps >= 34 -> MarigoldGold
                            else -> BridalCrimson
                        }
                        Surface(
                            color = Color(0xBF12060B),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, fpsColor.copy(alpha = 0.75f)),
                            modifier = Modifier.testTag("hud_fps_counter")
                        ) {
                            Text(
                                text = "${uiState.currentFps} FPS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = fpsColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                            )
                        }

                        // 1-Tap SMOOTH / HD Graphics Toggle
                        val isHd = uiState.graphicsQuality == GraphicsQuality.HD
                        Surface(
                            onClick = onToggleGraphicsQuality,
                            color = if (isHd) MarigoldGold else Color(0xBF12060B),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isHd) WarmIvory else FairyCyan.copy(alpha = 0.75f)),
                            modifier = Modifier.testTag("hud_graphics_toggle")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Toggle Graphics Quality",
                                    tint = if (isHd) MaroonNight else FairyCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = uiState.graphicsQuality.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isHd) MaroonNight else WarmIvory
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // Right Controls: Team Radio, Mic, Speaker, and Exit Match
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (local?.role?.hasTeamRadio() == true) {
                            IconButton(
                                onClick = onToggleTeamRadio,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (uiState.isTeamRadioEnabled) FairyCyan else Color(0xCC17070D))
                                    .border(1.5.dp, FairyCyan, CircleShape)
                                    .testTag("team_radio_toggle")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Radio,
                                    contentDescription = if (uiState.isTeamRadioEnabled) "Team Radio On" else "Team Radio Off",
                                    tint = if (uiState.isTeamRadioEnabled) MaroonNight else FairyCyan,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                if (!uiState.micPermissionGranted) onRequestMicPermission()
                                onToggleMic()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isMicEnabled) CngEmerald else BridalCrimson)
                                .border(
                                    width = if (uiState.localMicLevel > 0.18f) 2.5.dp else 1.5.dp,
                                    color = WarmIvory,
                                    shape = CircleShape
                                )
                                .testTag("hud_mic_toggle")
                        ) {
                            Icon(
                                imageVector = if (uiState.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Toggle Microphone",
                                tint = if (uiState.isMicEnabled) MaroonNight else WarmIvory,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(
                            onClick = onToggleSpeaker,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (!uiState.isSpeakerMuted) MarigoldGold else Color(0xCC2A1520))
                                .border(1.5.dp, WarmIvory.copy(alpha = 0.65f), CircleShape)
                                .testTag("hud_speaker_toggle")
                        ) {
                            Icon(
                                imageVector = if (!uiState.isSpeakerMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Mute or Unmute Audio",
                                tint = if (!uiState.isSpeakerMuted) MaroonNight else WarmMuted,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(
                            onClick = { showExitConfirm = true },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xBF14060C))
                                .border(1.dp, WarmIvory.copy(alpha = 0.4f), CircleShape)
                                .testTag("hud_exit_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Leave Match",
                                tint = WarmIvory,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // ROW 2 (BELOW TOP BAR): Minimap on Top-Left, Wedding Tasks + Zone/Weather beside it, Compact Player List on Right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hud_top_row_2"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    // Left & Middle: Minimap + Wedding Tasks / Zone / Weather stack
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        TransparentVenueMiniMap(world = world)

                        Column(
                            modifier = Modifier.widthIn(max = 148.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Bou's Wedding Tasks Bar & Anti-Camp Meter
                            Surface(
                                color = Color(0x8814060C),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, WarmIvory.copy(alpha = 0.25f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 7.dp, vertical = 5.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    val doneCount = world.tasks.count { it.completed }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Tasks",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = MarigoldGold
                                        )
                                        Text(
                                            text = "$doneCount/3",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = WarmIvory
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { world.completedTasksRatio },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(99.dp)),
                                        color = CngEmerald,
                                        trackColor = MaroonCard
                                    )
                                    if (local?.role == Role.BOU && local.campTimer > 2.2f && !local.isCarriedInSack) {
                                        Text(
                                            text = if (local.auraExposed) "AURA EXPOSED!" else "Keep moving!",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            color = BridalCrimson,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else if (world.chorPlayer?.isCarryingBou == true) {
                                        Text(
                                            text = "BOU IN SACK!",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            color = BridalCrimson,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Current Zone Pill
                            Surface(
                                color = Color(0x8416070F),
                                shape = RoundedCornerShape(9.dp),
                                border = BorderStroke(1.dp, MarigoldGold.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = world.currentZoneName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.5.sp,
                                    color = MarigoldGold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }

                            // Dynamic Outdoor Weather Pill
                            val isLocalOutdoors = local != null && !VenueMap.isInsideBuilding(local.pos)
                            val weatherIcon = when (world.weather) {
                                WeatherType.CLEAR_EVENING -> Icons.Default.NightsStay
                                WeatherType.MONSOON_RAIN -> Icons.Default.WaterDrop
                                WeatherType.FOGGY_NIGHT -> Icons.Default.Cloud
                            }
                            val weatherAccent = when (world.weather) {
                                WeatherType.CLEAR_EVENING -> MarigoldGold
                                WeatherType.MONSOON_RAIN -> FairyCyan
                                WeatherType.FOGGY_NIGHT -> Color(0xFFB8C7E0)
                            }
                            val weatherDetail = when (world.weather) {
                                WeatherType.CLEAR_EVENING -> world.weather.displayName
                                WeatherType.MONSOON_RAIN -> if (isLocalOutdoors) "Rain -15% Spd" else "Rain (Dry)"
                                WeatherType.FOGGY_NIGHT -> if (isLocalOutdoors) "Fog Low Vis" else "Fog (Indoors)"
                            }
                            Surface(
                                color = Color(0x88140915),
                                shape = RoundedCornerShape(9.dp),
                                border = BorderStroke(1.dp, weatherAccent.copy(alpha = 0.6f)),
                                modifier = Modifier.testTag("hud_weather_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = weatherIcon,
                                        contentDescription = world.weather.displayName,
                                        tint = weatherAccent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = weatherDetail,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isLocalOutdoors && world.weather != WeatherType.CLEAR_EVENING) weatherAccent else WarmIvory
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // Compact Voice Activity Player List on Right
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.testTag("hud_player_list")
                    ) {
                        world.players.forEach { p ->
                            val speaking = p.speakingLevel > 0.16f
                            Surface(
                                color = if (speaking) CngEmerald.copy(alpha = 0.9f) else Color(0xA617070D),
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(
                                    width = if (speaking) 1.5.dp else 0.75.dp,
                                    color = if (speaking) WarmIvory else p.role.accentColor.copy(alpha = 0.7f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (speaking) MaroonNight else p.role.accentColor)
                                    )
                                    Text(
                                        text = "${p.name.take(8)} (${p.role.badgeName})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (speaking) MaroonNight else WarmIvory
                                    )
                                }
                            }
                        }
                    }
                }

                // ROW 3 (CENTERED TOAST / EVENT ALERT BANNER BELOW MINIMAP ROW — NON-BLOCKING / PASS-THROUGH TOUCHES)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .testTag("hud_top_row_3"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AnimatedVisibility(visible = world.roleRevealTimer > 0f && local != null) {
                        if (local != null) {
                            Surface(
                                color = Color(0xCC1B0812),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.5.dp, MarigoldGold),
                                modifier = Modifier.widthIn(max = 340.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "YOU ARE ${local.role.displayName}!",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MarigoldGold
                                    )
                                    Text(
                                        text = local.role.shortGoal,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontSize = 12.sp,
                                        color = WarmIvory,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "Drag right side to orbit 360° • Pinch / Scroll to zoom",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = FairyCyan,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = !world.bannerAlertText.isNullOrBlank()) {
                        Surface(
                            color = BridalCrimson.copy(alpha = 0.94f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.5.dp, MarigoldGold),
                            modifier = Modifier
                                .widthIn(max = 340.dp)
                                .testTag("hud_event_banner")
                        ) {
                            Text(
                                text = world.bannerAlertText.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = 12.sp,
                                color = WarmIvory,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // BOTTOM-LEFT: Collapsible Chat Box + 1-Tap Bengali Quick-Chat + Virtual Thumb Joystick
            Column(
                modifier = Modifier.align(Alignment.BottomStart),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Collapsible In-Game Chat & Bengali Quick-Chat Drawer
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnimatedVisibility(visible = uiState.isChatExpanded) {
                        Surface(
                            color = Color(0xDD1A0810),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MarigoldGold.copy(alpha = 0.5f)),
                            modifier = Modifier.width(275.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // [All] vs [Team] Channel Toggle
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ChatChannel.entries.forEach { ch ->
                                            val sel = uiState.activeChatChannel == ch
                                            Surface(
                                                color = if (sel) MarigoldGold else MaroonCard,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .clickable { onSelectChatChannel(ch) }
                                                    .testTag("chat_channel_${ch.name.lowercase()}")
                                            ) {
                                                Text(
                                                    text = "[${ch.tag}]",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (sel) MaroonNight else WarmIvory,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = onToggleChat,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close Chat", tint = WarmMuted, modifier = Modifier.size(16.dp))
                                    }
                                }

                                // Recent Chat Messages
                                LazyColumn(
                                    modifier = Modifier
                                        .height(82.dp)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(uiState.chatMessages.takeLast(8), key = { it.id }) { m ->
                                        Text(
                                            text = "[${m.channel.tag}] ${m.senderName}: ${m.text}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (m.channel == ChatChannel.TEAM) FairyCyan else WarmIvory
                                        )
                                    }
                                }

                                // 1-Tap Bengali Quick-Chat Buttons!
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(QuickChatPresets.BENGALI_PHRASES) { phrase ->
                                        Surface(
                                            onClick = { onSendChat(phrase) },
                                            color = BridalCrimson.copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(999.dp),
                                            border = BorderStroke(1.dp, MarigoldGold.copy(alpha = 0.6f))
                                        ) {
                                            Text(
                                                text = phrase,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = WarmIvory,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }

                                // Custom Text Input Row
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = chatDraft,
                                        onValueChange = { chatDraft = it },
                                        placeholder = { Text("Type message...", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("ingame_chat_input"),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MarigoldGold,
                                            unfocusedBorderColor = WarmMuted.copy(alpha = 0.35f)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            if (chatDraft.isNotBlank()) {
                                                onSendChat(chatDraft)
                                                chatDraft = ""
                                            }
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MarigoldGold)
                                            .testTag("ingame_chat_send")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaroonNight, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Collapsed Quick Chat Toggle Pill + 1-tap instant Bengali panic button
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            onClick = onToggleChat,
                            color = Color(0xCC1A0810),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, MarigoldGold),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("toggle_chat_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat", tint = MarigoldGold, modifier = Modifier.size(16.dp))
                                Text("Chat / বাংলা", style = MaterialTheme.typography.labelSmall, color = WarmIvory)
                            }
                        }
                        if (!uiState.isChatExpanded) {
                            val quickPhrase = when (local?.role) {
                                Role.BOU -> QuickChatPresets.BENGALI_PHRASES[0]
                                Role.BOU_CHOR -> QuickChatPresets.BENGALI_PHRASES[3]
                                Role.PHOTOGRAPHER -> QuickChatPresets.BENGALI_PHRASES[2]
                                else -> QuickChatPresets.BENGALI_PHRASES[1]
                            }
                            Surface(
                                onClick = { onSendChat(quickPhrase) },
                                color = BridalCrimson.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, MarigoldGold.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .height(38.dp)
                                    .testTag("quick_bengali_button")
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(quickPhrase, style = MaterialTheme.typography.labelSmall, color = WarmIvory)
                                }
                            }
                        }
                    }
                }

                // Responsive Virtual Thumb Joystick (disabled while Bou is inside the sack)
                if (local?.isCarriedInSack != true) {
                    VirtualThumbJoystick(onMove = onJoystickMove)
                }
            }

            // BOTTOM-RIGHT: Big Tactile Role Ability & Hold Action Buttons!
            if (local != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 8.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Ability 2 (Sandal for Bou, Banana Peel for Bou Chor)
                    val a2Name = local.role.ability2Name
                    if (a2Name != null && !local.isCarriedInSack) {
                        TactileActionButton(
                            label = a2Name,
                            subLabel = "TAP [F]",
                            cooldown = local.ability2Cooldown,
                            maxCooldown = local.ability2MaxCooldown,
                            holdProgress = 0f,
                            color = MarigoldGold,
                            contentColor = MaroonNight,
                            sizeDp = 78,
                            testTag = "ability2_button",
                            isHoldButton = false,
                            onTap = onAbility2Press,
                            onHoldChanged = {}
                        )
                    }

                    // Primary Ability 1 / Hold Action Button
                    val primaryMeta = computePrimaryButtonMeta(local, world)
                    TactileActionButton(
                        label = primaryMeta.label,
                        subLabel = primaryMeta.subLabel,
                        cooldown = local.ability1Cooldown,
                        maxCooldown = local.ability1MaxCooldown,
                        holdProgress = local.holdProgress,
                        color = primaryMeta.color,
                        contentColor = primaryMeta.textColor,
                        sizeDp = 96,
                        testTag = "ability1_button",
                        isHoldButton = primaryMeta.isHold,
                        onTap = onAbility1Press,
                        onHoldChanged = onAbility1HoldChanged
                    )
                }
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            containerColor = MaroonSurface,
            title = { Text("Leave Biye Bari Match?", color = MarigoldGold) },
            text = { Text("Return to the lobby or title screen?", color = WarmIvory) },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        onExitMatch()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BridalCrimson)
                ) { Text("Leave", color = WarmIvory) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text("Keep Playing", color = MarigoldGold)
                }
            }
        )
    }
}

private data class PrimaryActionMeta(
    val label: String,
    val subLabel: String,
    val color: Color,
    val textColor: Color,
    val isHold: Boolean
)

private fun computePrimaryButtonMeta(local: PlayerEntity, world: GameWorldState): PrimaryActionMeta {
    return when (local.role) {
        Role.BOU -> PrimaryActionMeta("SCREAM!", "ALERT [E]", BridalCrimson, WarmIvory, false)
        Role.BOU_CHOR -> {
            val bou = world.players.firstOrNull { it.role == Role.BOU }
            val distBou = if (bou != null) local.pos.dist2D(bou.pos) else 99f
            if (local.isCarryingBou) {
                val nearGate = world.gates.any { local.pos.dist2D(it.pos) <= it.radius }
                PrimaryActionMeta(
                    label = if (nearGate) "ESCAPE!" else "CARRYING",
                    subLabel = if (nearGate) "HOLD 2.5s" else "GO TO GATE",
                    color = if (nearGate) CngEmerald else MaroonCard,
                    textColor = if (nearGate) MaroonNight else WarmIvory,
                    isHold = true
                )
            } else if (distBou <= 3.8f) {
                PrimaryActionMeta("KIDNAP", "HOLD 1.3s", BridalCrimson, WarmIvory, true)
            } else {
                PrimaryActionMeta("DASH!", "SPEED BOOST", ChorCharcoal, WarmIvory, false)
            }
        }
        Role.BOR -> PrimaryActionMeta("RESCUE", "HOLD 1.5s", CngEmerald, MaroonNight, true)
        Role.MAMA -> PrimaryActionMeta("BASH!", "DHISHUM!", MamaOrange, WarmIvory, false)
        Role.PHOTOGRAPHER -> PrimaryActionMeta("FLASH!", "BLIND 2.5s", FairyCyan, MaroonNight, false)
    }
}

@Composable
private fun VirtualThumbJoystick(
    onMove: (Float, Float) -> Unit
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadiusPx = 115f

    Box(
        modifier = Modifier
            .size(136.dp)
            .clip(CircleShape)
            .background(Color(0x5517070D))
            .border(1.75.dp, MarigoldGold.copy(alpha = 0.58f), CircleShape)
            .pointerInput(Unit) {
                // True independent PointerId tracking so left thumb moves smoothly while right thumb orbits 360° camera
                awaitPointerEventScope {
                    var activePointerId: PointerId? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val center = Offset(size.width * 0.5f, size.height * 0.5f)
                        if (activePointerId == null) {
                            val down = event.changes.firstOrNull { it.pressed && !it.isConsumed }
                            if (down != null) {
                                activePointerId = down.id
                                down.consume()
                                val delta = down.position - center
                                val dist = hypot(delta.x, delta.y).coerceAtLeast(1f)
                                val clamped = if (dist > maxRadiusPx) delta * (maxRadiusPx / dist) else delta
                                knobOffset = clamped
                                onMove(clamped.x / maxRadiusPx, clamped.y / maxRadiusPx)
                            }
                        } else {
                            val tracked = event.changes.firstOrNull { it.id == activePointerId }
                            if (tracked == null || !tracked.pressed) {
                                activePointerId = null
                                knobOffset = Offset.Zero
                                onMove(0f, 0f)
                            } else {
                                tracked.consume()
                                val delta = tracked.position - center
                                val dist = hypot(delta.x, delta.y).coerceAtLeast(1f)
                                val clamped = if (dist > maxRadiusPx) delta * (maxRadiusPx / dist) else delta
                                knobOffset = clamped
                                onMove(clamped.x / maxRadiusPx, clamped.y / maxRadiusPx)
                            }
                        }
                    }
                }
            }
            .testTag("virtual_joystick"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            drawCircle(MarigoldGold.copy(alpha = 0.14f), radius = size.minDimension * 0.35f, center = c)
            drawCircle(WarmIvory.copy(alpha = 0.18f), radius = size.minDimension * 0.35f, center = c, style = Stroke(width = 1.5f))
            val thumbCenter = c + Offset(knobOffset.x * 0.48f, knobOffset.y * 0.48f)
            drawCircle(MarigoldGold.copy(alpha = 0.92f), radius = size.minDimension * 0.21f, center = thumbCenter)
            drawCircle(WarmIvory, radius = size.minDimension * 0.21f, center = thumbCenter, style = Stroke(width = 3f))
        }
    }
}

@Composable
private fun TactileActionButton(
    label: String,
    subLabel: String,
    cooldown: Float,
    maxCooldown: Float,
    holdProgress: Float,
    color: Color,
    contentColor: Color,
    sizeDp: Int,
    testTag: String,
    isHoldButton: Boolean,
    onTap: () -> Unit,
    onHoldChanged: (Boolean) -> Unit
) {
    val onCooldown = cooldown > 0.05f
    val cooldownFraction = if (onCooldown && maxCooldown > 0.05f) {
        (cooldown / maxCooldown).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(if (onCooldown) Color(0x991B0C14) else color.copy(alpha = 0.92f))
            .border(
                width = 2.5.dp,
                color = if (onCooldown) WarmIvory.copy(alpha = 0.25f) else WarmIvory.copy(alpha = 0.88f),
                shape = CircleShape
            )
            .pointerInput(isHoldButton, onCooldown) {
                detectTapGestures(
                    onPress = {
                        if (isHoldButton) {
                            onHoldChanged(true)
                            tryAwaitRelease()
                            onHoldChanged(false)
                        } else if (!onCooldown) {
                            onTap()
                        }
                    }
                )
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        // Smooth radial sweeping cooldown progress ring & hold-action ring
        if (onCooldown || holdProgress > 0.01f) {
            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                if (onCooldown) {
                    // Subtle radial pie sweep showing remaining cooldown
                    drawArc(
                        color = color.copy(alpha = 0.22f),
                        startAngle = -90f,
                        sweepAngle = cooldownFraction * 360f,
                        useCenter = true
                    )
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = (1f - cooldownFraction) * 360f,
                        useCenter = false,
                        style = Stroke(width = 7f, cap = StrokeCap.Round)
                    )
                }
                if (holdProgress > 0.01f) {
                    drawArc(
                        color = WarmIvory,
                        startAngle = -90f,
                        sweepAngle = holdProgress * 360f,
                        useCenter = false,
                        style = Stroke(width = 9f, cap = StrokeCap.Round)
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (onCooldown) String.format("%.1fs", cooldown) else label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (onCooldown) WarmIvory else contentColor,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (onCooldown) "READYING" else subLabel,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = (if (onCooldown) WarmMuted else contentColor).copy(alpha = 0.88f)
            )
        }
    }
}

@Composable
private fun InsideSackOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0x993D2614),
                        Color(0xDD241509),
                        Color(0xF5120904)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Subtle woven burlap sack grid lines + two funny peepholes
            val step = 36f
            var x = 0f
            while (x < size.width) {
                drawLine(Color(0x28D4A373), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(Color(0x28D4A373), Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
                y += step
            }
        }
        Surface(
            color = Color(0xCC2A120A),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(2.dp, MarigoldGold)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "INSIDE SACK! (বস্তার ভেতরে!)",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MarigoldGold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Bou Chor is carrying you! Hit SCREAM or talk on Mic so Bor & Mama rescue you!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarmIvory,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TransparentVenueMiniMap(world: GameWorldState) {
    val localPlayer = world.localPlayer
    val localRole = localPlayer?.role
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Surface(
        color = Color(0x6612060B), // Transparent dark glass (~40% opacity)
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.25.dp, MarigoldGold.copy(alpha = 0.65f)),
        modifier = Modifier
            .size(width = 112.dp, height = 124.dp)
            .testTag("venue_minimap")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(6.dp)
            ) {
                val mapSpan = VenueMap.HALF_EXTENT * 2f // 140x140 units
                val scale = size.minDimension / mapSpan
                val offsetX = (size.width - mapSpan * scale) * 0.5f
                val offsetY = (size.height - mapSpan * scale) * 0.5f

                fun mapPt(x: Float, z: Float): Offset = Offset(
                    offsetX + (x + VenueMap.HALF_EXTENT) * scale,
                    offsetY + (z + VenueMap.HALF_EXTENT) * scale
                )

                fun mapRect(minX: Float, minZ: Float, maxX: Float, maxZ: Float, color: Color) {
                    val tl = mapPt(minX, minZ)
                    val br = mapPt(maxX, maxZ)
                    drawRect(
                        color = color,
                        topLeft = tl,
                        size = Size((br.x - tl.x).coerceAtLeast(1f), (br.y - tl.y).coerceAtLeast(1f))
                    )
                }

                // 1. Subtle 4-Zone Floorplan Tints (Transparent so 3D view peeks through)
                // Dynamic outdoor weather wash over courtyard & street areas
                if (world.rainIntensity > 0.05f || world.fogIntensity > 0.05f) {
                    val washColor = if (world.rainIntensity >= world.fogIntensity) {
                        FairyCyan.copy(alpha = 0.14f * world.rainIntensity)
                    } else {
                        Color(0xFFB8C7E0).copy(alpha = 0.22f * world.fogIntensity)
                    }
                    mapRect(-70f, -70f, 70f, 70f, washColor)
                }
                // South & North Escape Street Roads
                mapRect(-70f, 46f, 70f, 68f, Color(0x55363B48))
                mapRect(-70f, -68f, 70f, -52f, Color(0x55363B48))
                // Zone 1: Grand Wedding Hall + Red Persian Carpet
                mapRect(-24f, -26f, 24f, 16f, Color(0x44E7D3C5))
                mapRect(-4.5f, -18f, 4.5f, 22f, Color(0x66D91E43))
                // Zone 2: VIP Bridal Room (West Wing)
                mapRect(-56f, -26f, -24f, 16f, Color(0x48A62E69))
                // Zone 3: Kacchi Dining & Buffet Area (East Wing)
                mapRect(24f, -26f, 56f, 16f, Color(0x48C27834))

                // 2. Interior & Boundary Walls (clearly shows arch doorways!)
                VenueMap.walls.forEach { wall ->
                    val wallColor = if (wall.isInterior) WarmIvory.copy(alpha = 0.72f) else WarmMuted.copy(alpha = 0.45f)
                    val tl = mapPt(wall.minX, wall.minZ)
                    val br = mapPt(wall.maxX, wall.maxZ)
                    val rw = (br.x - tl.x).coerceAtLeast(2.2f)
                    val rh = (br.y - tl.y).coerceAtLeast(2.2f)
                    drawRect(color = wallColor, topLeft = tl, size = Size(rw, rh))
                }

                // Subtle Zone Initials
                labelPaint.textSize = 15f
                labelPaint.color = Color(0x88FFF6EA).toArgb()
                val pHall = mapPt(0f, -4f)
                val pVip = mapPt(-40f, -4f)
                val pKacchi = mapPt(40f, -4f)
                drawContext.canvas.nativeCanvas.drawText("HALL", pHall.x, pHall.y, labelPaint)
                drawContext.canvas.nativeCanvas.drawText("VIP", pVip.x, pVip.y, labelPaint)
                drawContext.canvas.nativeCanvas.drawText("KACCHI", pKacchi.x, pKacchi.y, labelPaint)

                // 3. Wedding Tasks (Small gold/emerald diamonds)
                world.tasks.forEach { t ->
                    val tp = mapPt(t.pos.x, t.pos.z)
                    drawCircle(
                        color = if (t.completed) CngEmerald.copy(alpha = 0.85f) else MarigoldGold.copy(alpha = 0.85f),
                        radius = 3.5f,
                        center = tp
                    )
                }

                // 4. All 3 Escape Vehicles (CNG, Microbus, Decorated Rickshaw) with crisp icons & labels!
                val pulse = 0.5f + 0.5f * sin(world.elapsedTimeSec * 5f)
                val chorCarrying = world.chorPlayer?.isCarryingBou == true
                world.gates.forEach { g ->
                    val gp = mapPt(g.pos.x, g.pos.z)
                    val (vc, tag) = when (g.type) {
                        VehicleType.CNG -> CngEmerald to "CNG"
                        VehicleType.MICROBUS -> FairyCyan to "BUS"
                        VehicleType.RICKSHAW -> MarigoldGold to "RICK"
                    }
                    if (chorCarrying) {
                        drawCircle(vc.copy(alpha = 0.25f + 0.25f * pulse), radius = 12f, center = gp)
                    }
                    drawRoundRect(
                        color = vc,
                        topLeft = Offset(gp.x - 7f, gp.y - 4.5f),
                        size = Size(14f, 9f),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                    drawRoundRect(
                        color = MaroonNight,
                        topLeft = Offset(gp.x - 7f, gp.y - 4.5f),
                        size = Size(14f, 9f),
                        cornerRadius = CornerRadius(3f, 3f),
                        style = Stroke(width = 1.6f)
                    )
                    labelPaint.textSize = 15f
                    labelPaint.color = vc.toArgb()
                    val labelY = if (g.pos.z > 0f) gp.y - 8f else gp.y + 18f
                    drawContext.canvas.nativeCanvas.drawText(tag, gp.x, labelY, labelPaint)
                }

                // 5. Scream Shockwave Ripples on Mini-Map
                world.shockwaves.forEach { sw ->
                    drawCircle(
                        color = BridalCrimson.copy(alpha = sw.alpha * 0.9f),
                        radius = (sw.radius * scale).coerceAtMost(size.minDimension * 0.48f),
                        center = mapPt(sw.origin.x, sw.origin.z),
                        style = Stroke(width = 2.4f)
                    )
                }

                // 6. Teammates & Flashed Chor
                world.players.forEach { p ->
                    if (p.isLocal || p.role == Role.BOU) return@forEach
                    val show = (p.role.team == localRole?.team) ||
                        (p.role == Role.BOU_CHOR && (p.wallHackTimer > 0f || p.isCarryingBou))
                    if (show) {
                        val pt = mapPt(p.pos.x, p.pos.z)
                        val col = if (p.role == Role.BOU_CHOR) Color(0xFFFF1744) else p.role.accentColor
                        if (p.role == Role.BOU_CHOR) {
                            drawCircle(col.copy(alpha = 0.35f + 0.3f * pulse), radius = 9.5f, center = pt)
                        }
                        drawCircle(col, radius = 4.5f, center = pt)
                        drawCircle(WarmIvory, radius = 4.5f, center = pt, style = Stroke(width = 1.2f))
                    }
                }

                // 7. BOU'S LOCATION (Distinct pulsing crimson & gold bridal diamond marker!)
                world.bouPlayer?.let { bou ->
                    val bp = mapPt(bou.pos.x, bou.pos.z)
                    val alertRadius = if (bou.isCarriedInSack) 13f + 4f * pulse else 9f + 2.5f * pulse
                    drawCircle(
                        color = BridalCrimson.copy(alpha = if (bou.isCarriedInSack) 0.45f else 0.28f),
                        radius = alertRadius,
                        center = bp
                    )
                    val diamond = Path().apply {
                        moveTo(bp.x, bp.y - 7.5f)
                        lineTo(bp.x + 6.5f, bp.y)
                        lineTo(bp.x, bp.y + 7.5f)
                        lineTo(bp.x - 6.5f, bp.y)
                        close()
                    }
                    drawPath(diamond, color = BridalCrimson)
                    drawPath(diamond, color = MarigoldGold, style = Stroke(width = 2.2f))
                    labelPaint.textSize = 16f
                    labelPaint.color = MarigoldGold.toArgb()
                    drawContext.canvas.nativeCanvas.drawText("BOU", bp.x, bp.y - 10f, labelPaint)
                }

                // 8. LOCAL PLAYER POSITION + 360° CAMERA VIEW CONE + DIRECTIONAL ARROW
                if (localPlayer != null) {
                    val lp = mapPt(localPlayer.pos.x, localPlayer.pos.z)
                    // Translucent 360° Camera FOV Cone showing where the camera is currently facing
                    val camLookAngle = kotlin.math.atan2(-sin(world.cameraYaw), -cos(world.cameraYaw))
                    val coneLen = 22f
                    val coneHalfFov = 0.52f
                    val conePath = Path().apply {
                        moveTo(lp.x, lp.y)
                        lineTo(lp.x + sin(camLookAngle - coneHalfFov) * coneLen, lp.y + cos(camLookAngle - coneHalfFov) * coneLen)
                        lineTo(lp.x + sin(camLookAngle + coneHalfFov) * coneLen, lp.y + cos(camLookAngle + coneHalfFov) * coneLen)
                        close()
                    }
                    drawPath(conePath, color = FairyCyan.copy(alpha = 0.24f))

                    drawCircle(WarmIvory.copy(alpha = 0.28f), radius = 10f, center = lp)
                    val fx = sin(localPlayer.yaw)
                    val fz = cos(localPlayer.yaw)
                    val rx = cos(localPlayer.yaw)
                    val rz = -sin(localPlayer.yaw)
                    val tip = Offset(lp.x + fx * 8.5f, lp.y + fz * 8.5f)
                    val leftWing = Offset(lp.x - fx * 5.5f - rx * 5.2f, lp.y - fz * 5.5f - rz * 5.2f)
                    val rightWing = Offset(lp.x - fx * 5.5f + rx * 5.2f, lp.y - fz * 5.5f + rz * 5.2f)
                    val arrow = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(leftWing.x, leftWing.y)
                        lineTo(lp.x - fx * 2.5f, lp.y - fz * 2.5f)
                        lineTo(rightWing.x, rightWing.y)
                        close()
                    }
                    drawPath(arrow, color = WarmIvory)
                    drawPath(arrow, color = MaroonNight, style = Stroke(width = 1.6f))
                }
            }

            // Compact Transparent Legend Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x5512060B))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("▲You", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = WarmIvory)
                Text("◆Bou", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = BridalCrimson)
                Text("■Gates", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = CngEmerald)
            }
        }
    }
}


