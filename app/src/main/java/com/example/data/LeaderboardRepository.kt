package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LeaderboardRepository(
    private val dao: LeaderboardDao
) {
    private val writeMutex = Mutex()

    val leaderboard: Flow<List<LeaderboardEntry>> = dao.observeLeaderboard()

    suspend fun recordChorCatch(rawPlayerName: String) {
        val name = normalizeName(rawPlayerName)
        writeMutex.withLock {
            val existing = dao.getByName(name)
            val updated = if (existing != null) {
                existing.copy(
                    chorCatches = existing.chorCatches + 1,
                    lastPlayedMs = System.currentTimeMillis()
                )
            } else {
                LeaderboardEntry(
                    playerName = name,
                    bouEscapes = 0,
                    chorCatches = 1,
                    matchesPlayed = 0,
                    lastPlayedMs = System.currentTimeMillis()
                )
            }
            dao.upsert(updated)
        }
    }

    suspend fun recordMatchCompleted(rawPlayerName: String, bouEscaped: Boolean) {
        val name = normalizeName(rawPlayerName)
        writeMutex.withLock {
            val existing = dao.getByName(name)
            val updated = if (existing != null) {
                existing.copy(
                    bouEscapes = existing.bouEscapes + if (bouEscaped) 1 else 0,
                    matchesPlayed = existing.matchesPlayed + 1,
                    lastPlayedMs = System.currentTimeMillis()
                )
            } else {
                LeaderboardEntry(
                    playerName = name,
                    bouEscapes = if (bouEscaped) 1 else 0,
                    chorCatches = 0,
                    matchesPlayed = 1,
                    lastPlayedMs = System.currentTimeMillis()
                )
            }
            dao.upsert(updated)
        }
    }

    suspend fun clearLeaderboard() {
        writeMutex.withLock {
            dao.clearAll()
        }
    }

    companion object {
        fun normalizeName(raw: String): String {
            return raw.trim().take(16).ifBlank { "Player" }
        }
    }
}
