package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LeaderboardDao {
    @Query(
        """
        SELECT * FROM leaderboard_entries
        ORDER BY (bouEscapes + chorCatches) DESC, bouEscapes DESC, chorCatches DESC, lastPlayedMs DESC
        """
    )
    fun observeLeaderboard(): Flow<List<LeaderboardEntry>>

    @Query("SELECT * FROM leaderboard_entries WHERE playerName = :playerName LIMIT 1")
    suspend fun getByName(playerName: String): LeaderboardEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LeaderboardEntry)

    @Query("DELETE FROM leaderboard_entries")
    suspend fun clearAll()
}
