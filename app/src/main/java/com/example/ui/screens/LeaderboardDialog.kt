package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsKabaddi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.LeaderboardEntry
import com.example.data.LeaderboardRepository
import com.example.ui.theme.BridalCrimson
import com.example.ui.theme.CngEmerald
import com.example.ui.theme.FairyCyan
import com.example.ui.theme.MarigoldGold
import com.example.ui.theme.MaroonCard
import com.example.ui.theme.MaroonNight
import com.example.ui.theme.MaroonSurface
import com.example.ui.theme.WarmIvory
import com.example.ui.theme.WarmMuted

private enum class LeaderboardSortTab(val label: String, val tag: String) {
    OVERALL("Overall", "leaderboard_filter_all"),
    BOU_ESCAPES("Top Bou", "leaderboard_filter_bou"),
    CHOR_CATCHES("Top Chor", "leaderboard_filter_chor")
}

@Composable
fun LeaderboardDialog(
    entries: List<LeaderboardEntry>,
    activePlayerName: String,
    onDismiss: () -> Unit,
    onClearLeaderboard: () -> Unit
) {
    var activeTab by remember { mutableStateOf(LeaderboardSortTab.OVERALL) }
    var showConfirmReset by remember { mutableStateOf(false) }

    val normalizedActiveName = remember(activePlayerName) {
        LeaderboardRepository.normalizeName(activePlayerName)
    }

    val sortedEntries = remember(entries, activeTab) {
        when (activeTab) {
            LeaderboardSortTab.OVERALL -> entries.sortedWith(
                compareByDescending<LeaderboardEntry> { it.totalScore }
                    .thenByDescending { it.bouEscapes }
                    .thenByDescending { it.chorCatches }
                    .thenByDescending { it.lastPlayedMs }
            )
            LeaderboardSortTab.BOU_ESCAPES -> entries.sortedWith(
                compareByDescending<LeaderboardEntry> { it.bouEscapes }
                    .thenByDescending { it.totalScore }
                    .thenByDescending { it.lastPlayedMs }
            )
            LeaderboardSortTab.CHOR_CATCHES -> entries.sortedWith(
                compareByDescending<LeaderboardEntry> { it.chorCatches }
                    .thenByDescending { it.totalScore }
                    .thenByDescending { it.lastPlayedMs }
            )
        }
    }

    val totalBouEscapes = remember(entries) { entries.sumOf { it.bouEscapes } }
    val totalChorCatches = remember(entries) { entries.sumOf { it.chorCatches } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 540.dp)
                .testTag("leaderboard_dialog"),
            colors = CardDefaults.cardColors(containerColor = MaroonSurface),
            border = BorderStroke(1.5.dp, MarigoldGold.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            color = MarigoldGold.copy(alpha = 0.2f),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MarigoldGold)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEvents,
                                contentDescription = stringResource(R.string.leaderboard_title),
                                tint = MarigoldGold,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.leaderboard_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = MarigoldGold,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.leaderboard_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = WarmMuted
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("leaderboard_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.btn_close),
                            tint = WarmIvory
                        )
                    }
                }

                // Device totals summary bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatTile(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.label_bou_escapes),
                        value = totalBouEscapes.toString(),
                        accent = BridalCrimson,
                        icon = Icons.Default.Security
                    )
                    SummaryStatTile(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.label_chor_catches),
                        value = totalChorCatches.toString(),
                        accent = FairyCyan,
                        icon = Icons.Default.SportsKabaddi
                    )
                }

                // Sort / Filter Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LeaderboardSortTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MarigoldGold else MaroonCard,
                            border = BorderStroke(
                                1.dp,
                                if (selected) WarmIvory else MarigoldGold.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable { activeTab = tab }
                                .testTag(tab.tag)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaroonNight else WarmIvory,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Ranked list or Empty state
                if (sortedEntries.isEmpty()) {
                    Surface(
                        color = MaroonCard,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, WarmMuted.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MilitaryTech,
                                contentDescription = null,
                                tint = MarigoldGold.copy(alpha = 0.7f),
                                modifier = Modifier.size(38.dp)
                            )
                            Text(
                                text = stringResource(R.string.leaderboard_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = WarmIvory,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.leaderboard_empty_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarmMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 310.dp)
                            .testTag("leaderboard_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        itemsIndexed(
                            items = sortedEntries,
                            key = { _, item -> item.playerName }
                        ) { index, entry ->
                            LeaderboardRowItem(
                                rank = index + 1,
                                entry = entry,
                                isCurrentPlayer = entry.playerName.equals(normalizedActiveName, ignoreCase = true)
                            )
                        }
                    }
                }

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (entries.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showConfirmReset = true },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("leaderboard_reset_button"),
                            border = BorderStroke(1.dp, BridalCrimson.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = BridalCrimson,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.btn_reset_leaderboard),
                                style = MaterialTheme.typography.labelLarge,
                                color = BridalCrimson
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("leaderboard_done_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CngEmerald,
                            contentColor = MaroonNight
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.btn_close),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showConfirmReset) {
        AlertDialog(
            onDismissRequest = { showConfirmReset = false },
            containerColor = MaroonSurface,
            titleContentColor = MarigoldGold,
            textContentColor = WarmIvory,
            title = { Text(stringResource(R.string.leaderboard_reset_confirm_title)) },
            text = { Text(stringResource(R.string.leaderboard_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmReset = false
                        onClearLeaderboard()
                    },
                    modifier = Modifier.testTag("leaderboard_reset_confirm_button")
                ) {
                    Text(stringResource(R.string.btn_reset_leaderboard), color = BridalCrimson)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmReset = false }) {
                    Text(stringResource(R.string.btn_cancel), color = WarmIvory)
                }
            }
        )
    }
}

@Composable
private fun SummaryStatTile(
    modifier: Modifier,
    label: String,
    value: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = modifier,
        color = MaroonCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = WarmIvory,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = WarmMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LeaderboardRowItem(
    rank: Int,
    entry: LeaderboardEntry,
    isCurrentPlayer: Boolean
) {
    val rankColor = when (rank) {
        1 -> MarigoldGold
        2 -> Color(0xFFD6DCE5)
        3 -> Color(0xFFCD7F32)
        else -> WarmMuted.copy(alpha = 0.65f)
    }
    val borderColor = if (isCurrentPlayer) {
        CngEmerald.copy(alpha = 0.85f)
    } else if (rank == 1) {
        MarigoldGold.copy(alpha = 0.6f)
    } else {
        WarmMuted.copy(alpha = 0.2f)
    }

    Surface(
        color = if (isCurrentPlayer) Color(0xFF2B1322) else MaroonCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("leaderboard_row_${entry.playerName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Rank Medal Circle
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(rankColor.copy(alpha = if (rank <= 3) 0.24f else 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.labelLarge,
                    color = rankColor,
                    fontWeight = FontWeight.Bold
                )
            }

            // Player Name & Matches Played
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.playerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = WarmIvory,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isCurrentPlayer) {
                        Surface(
                            color = CngEmerald,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "YOU",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaroonNight,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "${entry.matchesPlayed} match${if (entry.matchesPlayed == 1) "" else "es"} • Total ${entry.totalScore}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarmMuted
                )
            }

            // Bou Escapes Pill
            StatCountBadge(
                label = "BOU",
                count = entry.bouEscapes,
                accent = BridalCrimson
            )

            // Chor Catches Pill
            StatCountBadge(
                label = "CHOR",
                count = entry.chorCatches,
                accent = FairyCyan
            )
        }
    }
}

@Composable
private fun StatCountBadge(
    label: String,
    count: Int,
    accent: Color
) {
    Surface(
        color = accent.copy(alpha = 0.18f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = WarmIvory,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent
            )
        }
    }
}
