package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "leaderboard_entries")
data class LeaderboardEntry(
    @PrimaryKey val playerName: String,
    val bouEscapes: Int = 0,
    val chorCatches: Int = 0,
    val matchesPlayed: Int = 0,
    val lastPlayedMs: Long = System.currentTimeMillis()
) {
    val totalScore: Int
        get() = bouEscapes + chorCatches
}
