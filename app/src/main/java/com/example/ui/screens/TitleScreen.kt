package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.LeaderboardEntry
import com.example.data.LeaderboardRepository
import com.example.game.Role
import com.example.ui.BouChorUiState
import com.example.ui.theme.BridalCrimson
import com.example.ui.theme.BridalCrimsonDeep
import com.example.ui.theme.CngEmerald
import com.example.ui.theme.FairyCyan
import com.example.ui.theme.FairyPink
import com.example.ui.theme.MarigoldGold
import com.example.ui.theme.MaroonCard
import com.example.ui.theme.MaroonNight
import com.example.ui.theme.MaroonSurface
import com.example.ui.theme.WarmIvory
import com.example.ui.theme.WarmMuted

@Composable
fun TitleScreen(
    uiState: BouChorUiState,
    onNameChange: (String) -> Unit,
    onPreferredRoleChange: (Role?) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onPlaySolo: (Role?) -> Unit,
    onClearLeaderboard: () -> Unit
) {
    var joinCode by remember { mutableStateOf("") }
    var showLeaderboardDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF2A0917), MaroonNight, Color(0xFF12060B))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Festive Fairy-Light String Header + Hero Wedding Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(195.dp)
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_wedding_banner_1795636971565),
                        contentDescription = "Bangladeshi Biye Bari Wedding Courtyard",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x4417070D),
                                        Color(0x8817070D),
                                        Color(0xEE17070D)
                                    )
                                )
                            )
                    )
                    // Fairy light bulbs row along top of banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, start = 14.dp, end = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val bulbs = listOf(MarigoldGold, FairyPink, FairyCyan, CngEmerald, MarigoldGold, FairyPink, FairyCyan, CngEmerald)
                        bulbs.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(c)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Surface(
                            color = BridalCrimson,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "5-PLAYER 3D BIYE BARI CHAOS",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarmIvory,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "BOU CHOR",
                            style = MaterialTheme.typography.displayLarge,
                            color = MarigoldGold
                        )
                        Text(
                            text = "Bangladeshi Wedding Thief • Proximity Voice & Smart Bots",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarmIvory.copy(alpha = 0.9f)
                        )
                    }
                }

                // Player Nickname Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaroonSurface),
                    border = BorderStroke(1.dp, MarigoldGold.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.playerName,
                            onValueChange = onNameChange,
                            label = { Text("Your Wedding Nickname") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nickname_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MarigoldGold,
                                unfocusedBorderColor = WarmMuted.copy(alpha = 0.4f),
                                focusedLabelColor = MarigoldGold,
                                cursorColor = MarigoldGold
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )

                        // Instant Play Solo Button (1 Player + 4 Smart AI Bots)
                        Button(
                            onClick = { onPlaySolo(uiState.preferredRole) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("play_solo_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CngEmerald,
                                contentColor = MaroonNight
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.SmartToy, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Play Solo (1P + 4 Smart AI Bots)",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }

                        // Optional Role Preference Chips for Instant Solo Testing
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Solo Role Mode (Default: Random Shuffle)",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarmMuted
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    RolePickChip(
                                        label = "Random",
                                        accent = MarigoldGold,
                                        selected = uiState.preferredRole == null,
                                        onClick = { onPreferredRoleChange(null) }
                                    )
                                }
                                items(Role.entries) { role ->
                                    RolePickChip(
                                        label = role.displayName,
                                        accent = role.accentColor,
                                        selected = uiState.preferredRole == role,
                                        onClick = { onPreferredRoleChange(role) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Device-Saved Local Leaderboard Card (Bou Escapes & Chor Catches across sessions)
                LocalLeaderboardPreviewCard(
                    entries = uiState.leaderboardEntries,
                    activePlayerName = uiState.playerName,
                    onOpenFullBoard = { showLeaderboardDialog = true }
                )

                // Multiplayer P2P Room Card (Create Room / Join Room)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaroonSurface),
                    border = BorderStroke(1.dp, BridalCrimson.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Groups, contentDescription = null, tint = MarigoldGold)
                            Text(
                                text = "P2P Multiplayer Lobby (Max 5 Players)",
                                style = MaterialTheme.typography.titleLarge,
                                color = WarmIvory
                            )
                        }

                        Button(
                            onClick = onCreateRoom,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("create_room_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BridalCrimson,
                                contentColor = WarmIvory
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Create Room (Generate 4-Char Code)",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = joinCode,
                                onValueChange = { joinCode = it.filter { ch -> ch.isLetterOrDigit() || ch == '.' || ch == ':' }.uppercase().take(15) },
                                label = { Text("Code or Host IP") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("join_code_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MarigoldGold,
                                    unfocusedBorderColor = WarmMuted.copy(alpha = 0.4f)
                                ),
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedButton(
                                onClick = { onJoinRoom(joinCode) },
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("join_room_button"),
                                border = BorderStroke(1.5.dp, MarigoldGold),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Login, contentDescription = null, tint = MarigoldGold)
                                Spacer(Modifier.width(6.dp))
                                Text("Join", color = MarigoldGold, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                // 5 Iconic Roles Preview Strip
                Text(
                    text = "Meet the 5 Biye Bari Roles",
                    style = MaterialTheme.typography.titleLarge,
                    color = MarigoldGold
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(Role.entries) { role ->
                        RoleInfoCard(role = role)
                    }
                }
            }
        }
    }

    if (showLeaderboardDialog) {
        LeaderboardDialog(
            entries = uiState.leaderboardEntries,
            activePlayerName = uiState.playerName,
            onDismiss = { showLeaderboardDialog = false },
            onClearLeaderboard = onClearLeaderboard
        )
    }
}

@Composable
private fun LocalLeaderboardPreviewCard(
    entries: List<LeaderboardEntry>,
    activePlayerName: String,
    onOpenFullBoard: () -> Unit
) {
    val normalizedName = remember(activePlayerName) {
        LeaderboardRepository.normalizeName(activePlayerName)
    }
    val myRankIndex = remember(entries, normalizedName) {
        entries.indexOfFirst { it.playerName.equals(normalizedName, ignoreCase = true) }
    }
    val myEntry = if (myRankIndex >= 0) entries[myRankIndex] else null
    val topThree = remember(entries) { entries.take(3) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaroonSurface),
        border = BorderStroke(1.dp, MarigoldGold.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = MarigoldGold
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.leaderboard_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MarigoldGold,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (myEntry != null) {
                                "$normalizedName • #${myRankIndex + 1} • ${myEntry.bouEscapes} Bou • ${myEntry.chorCatches} Chor"
                            } else {
                                "Tracks Bou Escapes & Chor Catches on device"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = WarmMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenFullBoard,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("open_leaderboard_button"),
                    border = BorderStroke(1.2.dp, MarigoldGold),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_open_leaderboard),
                        style = MaterialTheme.typography.labelLarge,
                        color = MarigoldGold
                    )
                }
            }

            if (topThree.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    topThree.forEachIndexed { idx, entry ->
                        val medalColor = when (idx) {
                            0 -> MarigoldGold
                            1 -> Color(0xFFD6DCE5)
                            else -> Color(0xFFCD7F32)
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = MaroonCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (entry.playerName.equals(normalizedName, ignoreCase = true)) {
                                    CngEmerald.copy(alpha = 0.8f)
                                } else {
                                    medalColor.copy(alpha = 0.45f)
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "#${idx + 1} ${entry.playerName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = medalColor,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Bou ${entry.bouEscapes} • Chor ${entry.chorCatches}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WarmIvory,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RolePickChip(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent else MaroonCard,
        border = BorderStroke(1.dp, if (selected) WarmIvory else accent.copy(alpha = 0.5f)),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("role_chip_${label.lowercase()}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (label == "Random") {
                Icon(
                    Icons.Default.Casino,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (selected) MaroonNight else MarigoldGold
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    if (accent == Color(0xFF1A1B24)) WarmIvory else MaroonNight
                } else WarmIvory
            )
        }
    }
}

@Composable
private fun RoleInfoCard(role: Role) {
    Card(
        modifier = Modifier.width(185.dp),
        colors = CardDefaults.cardColors(containerColor = MaroonCard),
        border = BorderStroke(1.5.dp, role.accentColor.copy(alpha = 0.75f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                color = role.accentColor,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = role.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (role == Role.BOR) MaroonNight else WarmIvory,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Text(
                text = role.bengaliTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MarigoldGold
            )
            Text(
                text = role.shortGoal,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = WarmIvory.copy(alpha = 0.88f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val abilities = listOfNotNull(role.ability1Name, role.ability2Name).joinToString(" + ")
            Text(
                text = "Abilities: $abilities",
                style = MaterialTheme.typography.labelSmall,
                color = BridalCrimsonDeep.takeIf { false } ?: FairyCyan
            )
        }
    }
}
