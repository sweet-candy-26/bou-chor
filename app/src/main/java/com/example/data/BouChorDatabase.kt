package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LeaderboardEntry::class],
    version = 1,
    exportSchema = false
)
abstract class BouChorDatabase : RoomDatabase() {
    abstract fun leaderboardDao(): LeaderboardDao

    companion object {
        @Volatile
        private var INSTANCE: BouChorDatabase? = null

        fun getInstance(context: Context): BouChorDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BouChorDatabase::class.java,
                    "bouchor_leaderboard.db"
                )
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
