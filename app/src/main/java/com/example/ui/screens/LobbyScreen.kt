package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.QuickChatPresets
import com.example.ui.BouChorUiState
import com.example.ui.theme.BridalCrimson
import com.example.ui.theme.CngEmerald
import com.example.ui.theme.FairyCyan
import com.example.ui.theme.MarigoldGold
import com.example.ui.theme.MaroonCard
import com.example.ui.theme.MaroonNight
import com.example.ui.theme.MaroonSurface
import com.example.ui.theme.WarmIvory
import com.example.ui.theme.WarmMuted

@Composable
fun LobbyScreen(
    uiState: BouChorUiState,
    onLeaveLobby: () -> Unit,
    onStartMatch: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onSendChat: (String) -> Unit,
    onCopyToast: (String) -> Unit
) {
    BackHandler { onLeaveLobby() }

    val context = LocalContext.current
    var chatInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF280B17), MaroonNight)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Top Bar with Back, Room Code & Audio Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onLeaveLobby,
                            modifier = Modifier.testTag("lobby_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave Lobby", tint = WarmIvory)
                        }
                        Column {
                            Text(
                                text = "BIYE BARI LOBBY",
                                style = MaterialTheme.typography.titleLarge,
                                color = MarigoldGold
                            )
                            Text(
                                text = uiState.peerStatusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isPeerConnected) CngEmerald else WarmMuted
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                if (!uiState.micPermissionGranted) onRequestMicPermission()
                                onToggleMic()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isMicEnabled) CngEmerald else BridalCrimson)
                                .testTag("lobby_mic_toggle")
                        ) {
                            Icon(
                                if (uiState.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Toggle Microphone",
                                tint = MaroonNight
                            )
                        }
                        IconButton(
                            onClick = onToggleSpeaker,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (!uiState.isSpeakerMuted) MarigoldGold else MaroonCard)
                                .testTag("lobby_speaker_toggle")
                        ) {
                            Icon(
                                if (!uiState.isSpeakerMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Toggle Speaker",
                                tint = if (!uiState.isSpeakerMuted) MaroonNight else WarmMuted
                            )
                        }
                    }
                }

                // Room Code + Copy Invite Link Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaroonSurface),
                    border = BorderStroke(1.5.dp, MarigoldGold.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ROOM CODE", style = MaterialTheme.typography.labelSmall, color = WarmMuted)
                            Text(
                                text = uiState.roomCode.ifBlank { "XXXX" },
                                style = MaterialTheme.typography.headlineLarge,
                                letterSpacing = 4.sp,
                                color = MarigoldGold
                            )
                            if (uiState.isHost && !uiState.hostIpAddress.isNullOrBlank()) {
                                Text(
                                    text = "IP: ${uiState.hostIpAddress}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CngEmerald
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val link = "Join my BOU CHOR wedding match! Room Code: ${uiState.roomCode} (https://bouchor.app/join/${uiState.roomCode})"
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Bou Chor Room", link))
                                    onCopyToast("Room ${uiState.roomCode} invite copied!")
                                },
                                border = BorderStroke(1.dp, MarigoldGold),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("copy_invite_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = MarigoldGold)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy Link", color = MarigoldGold)
                            }
                            IconButton(
                                onClick = {
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "Join my BOU CHOR 3D match! Room Code: ${uiState.roomCode}")
                                    }
                                    context.startActivity(Intent.createChooser(share, "Share Bou Chor Room"))
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaroonCard)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share Invite", tint = WarmIvory)
                            }
                        }
                    }
                }

                // Live Mic Check Meter
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaroonCard),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            if (uiState.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = if (uiState.localMicLevel > 0.15f) CngEmerald else MarigoldGold
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    !uiState.micPermissionGranted -> "Tap Mic icon to enable Live Voice Check"
                                    !uiState.isMicEnabled -> "Microphone Muted"
                                    uiState.localMicLevel > 0.15f -> "Voice Detected! Loud & Clear"
                                    else -> "Live Mic Check Active — Say something!"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = WarmIvory
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { uiState.localMicLevel.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(99.dp)),
                                color = CngEmerald,
                                trackColor = MaroonNight
                            )
                        }
                    }
                }

                // 5 Connected Player Slots (Empty slots auto-fill with Smart AI Bots)
                Text(
                    text = "Wedding Squad (${uiState.lobbyPlayers.size}/5 Humans • AI Bots fill remaining)",
                    style = MaterialTheme.typography.titleMedium,
                    color = WarmIvory
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (slot in 0 until 5) {
                        val p = uiState.lobbyPlayers.getOrNull(slot)
                        val speaking = (p?.speakingLevel ?: 0f) > 0.15f
                        Surface(
                            color = if (p != null) MaroonSurface else MaroonCard.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                width = if (speaking) 2.dp else 1.dp,
                                color = if (speaking) CngEmerald else if (p != null) MarigoldGold.copy(alpha = 0.35f) else WarmMuted.copy(alpha = 0.2f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(if (p != null) BridalCrimson else MaroonNight)
                                            .border(
                                                width = if (speaking) 2.5.dp else 1.dp,
                                                color = if (speaking) CngEmerald else MarigoldGold.copy(alpha = 0.5f),
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (p != null) {
                                            Text(
                                                text = p.name.take(1).uppercase(),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = WarmIvory
                                            )
                                        } else {
                                            Icon(Icons.Default.SmartToy, contentDescription = null, tint = WarmMuted, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    Column {
                                        Text(
                                            text = p?.name ?: "Smart AI Bot (Auto-Fill)",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (p != null) WarmIvory else WarmMuted
                                        )
                                        Text(
                                            text = when {
                                                p == null -> "Will take a random role on Start"
                                                p.isHost -> "Host • Random Role on Start"
                                                else -> "Connected via P2P"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (p?.isHost == true) MarigoldGold else WarmMuted
                                        )
                                    }
                                }
                                if (p != null) {
                                    Icon(
                                        if (p.micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = null,
                                        tint = if (speaking) CngEmerald else WarmMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Lobby Text Chat + Bengali Quick Chat
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaroonSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(uiState.chatMessages, key = { it.id }) { msg ->
                                Text(
                                    text = "${msg.senderName}: ${msg.text}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (msg.senderId == "sys") FairyCyan else WarmIvory
                                )
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(QuickChatPresets.BENGALI_PHRASES) { phrase ->
                                Surface(
                                    color = MaroonCard,
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, MarigoldGold.copy(alpha = 0.45f)),
                                    onClick = { onSendChat(phrase) }
                                ) {
                                    Text(
                                        text = phrase,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WarmIvory,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = chatInput,
                                onValueChange = { chatInput = it },
                                placeholder = { Text("Say hi in lobby...") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("lobby_chat_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MarigoldGold,
                                    unfocusedBorderColor = WarmMuted.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                            IconButton(
                                onClick = {
                                    if (chatInput.isNotBlank()) {
                                        onSendChat(chatInput)
                                        chatInput = ""
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MarigoldGold)
                                    .testTag("lobby_chat_send")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Chat", tint = MaroonNight)
                            }
                        }
                    }
                }

                // Start Match CTA
                Button(
                    onClick = onStartMatch,
                    enabled = uiState.isHost,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("start_match_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CngEmerald,
                        contentColor = MaroonNight
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isHost) "Start Match (Assign 5 Random Roles)" else "Waiting for Host to Start...",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
