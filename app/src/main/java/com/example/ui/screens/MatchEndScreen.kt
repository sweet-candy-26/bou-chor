package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.LeaderboardEntry
import com.example.data.LeaderboardRepository
import com.example.game.MatchResult
import com.example.game.Team
import com.example.ui.theme.BridalCrimson
import com.example.ui.theme.CngEmerald
import com.example.ui.theme.FairyCyan
import com.example.ui.theme.FairyPink
import com.example.ui.theme.MarigoldGold
import com.example.ui.theme.MaroonCard
import com.example.ui.theme.MaroonNight
import com.example.ui.theme.MaroonSurface
import com.example.ui.theme.WarmIvory
import com.example.ui.theme.WarmMuted
import kotlin.random.Random

@Composable
fun MatchEndScreen(
    result: MatchResult,
    localWon: Boolean,
    activePlayerName: String,
    leaderboardEntries: List<LeaderboardEntry>,
    recordedBouEscape: Boolean,
    recordedChorCatches: Int,
    onClearLeaderboard: () -> Unit,
    onPlayAgainShuffle: () -> Unit,
    onBackToLobby: () -> Unit
) {
    BackHandler { onBackToLobby() }
    var showLeaderboardDialog by remember { mutableStateOf(false) }

    val normalizedName = remember(activePlayerName) {
        LeaderboardRepository.normalizeName(activePlayerName)
    }
    val myRankIndex = remember(leaderboardEntries, normalizedName) {
        leaderboardEntries.indexOfFirst { it.playerName.equals(normalizedName, ignoreCase = true) }
    }
    val myEntry = if (myRankIndex >= 0) leaderboardEntries[myRankIndex] else null

    val transition = rememberInfiniteTransition(label = "confetti")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
        label = "confettiPhase"
    )
    val confettiSeeds = remember {
        List(36) {
            Triple(Random.nextFloat(), Random.nextFloat(), Random.nextInt(4))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (result.winningTeam == Team.WEDDING) {
                        listOf(Color(0xFF330C1B), MaroonNight, Color(0xFF0E2118))
                    } else {
                        listOf(Color(0xFF1F1228), MaroonNight, Color(0xFF280B14))
                    }
                )
            )
    ) {
        // Festive floating confetti particles
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cols = listOf(MarigoldGold, BridalCrimson, CngEmerald, FairyCyan, FairyPink)
            confettiSeeds.forEach { (rx, ry, ci) ->
                val y = ((ry + phase) % 1f) * size.height
                val x = rx * size.width
                drawCircle(cols[ci % cols.size].copy(alpha = 0.45f), radius = 8f, center = Offset(x, y))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = if (localWon) CngEmerald else BridalCrimson,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = if (localWon) "VICTORY • BIYE BARI LEGEND" else "MATCH OVER • CHAOS COMPLETE",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (localWon) MaroonNight else WarmIvory,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                Text(
                    text = result.headline,
                    style = MaterialTheme.typography.displayLarge,
                    color = MarigoldGold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = result.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = WarmIvory,
                    textAlign = TextAlign.Center
                )

                // Viral-Clip Ready MVP Awards Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaroonSurface),
                    border = BorderStroke(1.5.dp, MarigoldGold.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = MarigoldGold)
                            Text(
                                text = "MATCH MVP HIGHLIGHTS",
                                style = MaterialTheme.typography.titleLarge,
                                color = MarigoldGold
                            )
                        }

                        result.awards.forEach { award ->
                            Surface(
                                color = MaroonCard,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, award.role.accentColor.copy(alpha = 0.65f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = award.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MarigoldGold
                                        )
                                        Text(
                                            text = "${award.winnerName} (${award.role.displayName})",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = WarmIvory
                                        )
                                    }
                                    Surface(
                                        color = award.role.accentColor,
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = award.valueText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = WarmIvory,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Cast Reveal Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaroonCard),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("CAST REVEAL", style = MaterialTheme.typography.labelSmall, color = WarmMuted)
                        result.playerRoles.forEach { (name, role) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, color = WarmIvory)
                                Text(
                                    text = "${role.displayName} • ${role.bengaliTitle}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MarigoldGold
                                )
                            }
                        }
                    }
                }

                // Local Leaderboard Update & Saved Record Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaroonSurface),
                    border = BorderStroke(1.2.dp, MarigoldGold.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(18.dp)
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EmojiEvents,
                                    contentDescription = null,
                                    tint = MarigoldGold
                                )
                                Text(
                                    text = stringResource(R.string.leaderboard_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MarigoldGold,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (myRankIndex >= 0) {
                                Surface(
                                    color = CngEmerald,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "RANK #${myRankIndex + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaroonNight,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        if (recordedBouEscape || recordedChorCatches > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (recordedBouEscape) {
                                    Surface(
                                        color = BridalCrimson,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "+1 BOU ESCAPE SAVED",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = WarmIvory,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                if (recordedChorCatches > 0) {
                                    Surface(
                                        color = FairyCyan,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "+$recordedChorCatches CHOR CATCH${if (recordedChorCatches == 1) "" else "ES"} SAVED",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaroonNight,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = normalizedName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = WarmIvory,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Bou Escapes: ${myEntry?.bouEscapes ?: 0}  •  Chor Catches: ${myEntry?.chorCatches ?: 0}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WarmMuted
                                )
                            }
                            OutlinedButton(
                                onClick = { showLeaderboardDialog = true },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("match_end_leaderboard_button"),
                                border = BorderStroke(1.2.dp, MarigoldGold),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.btn_open_leaderboard),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MarigoldGold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Play Again (Shuffle Roles) Primary CTA
                Button(
                    onClick = onPlayAgainShuffle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .testTag("play_again_shuffle_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CngEmerald,
                        contentColor = MaroonNight
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Play Again (Shuffle Roles)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onBackToLobby,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("back_to_lobby_button"),
                    border = BorderStroke(1.5.dp, MarigoldGold),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, tint = MarigoldGold)
                    Spacer(Modifier.width(8.dp))
                    Text("Back to Lobby / Title", color = MarigoldGold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (showLeaderboardDialog) {
        LeaderboardDialog(
            entries = leaderboardEntries,
            activePlayerName = activePlayerName,
            onDismiss = { showLeaderboardDialog = false },
            onClearLeaderboard = onClearLeaderboard
        )
    }
}
