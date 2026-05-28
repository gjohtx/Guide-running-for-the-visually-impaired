package com.example.guiderunningfortheblind.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.guiderunningfortheblind.data.local.dao.RunningPlanDao
import com.example.guiderunningfortheblind.data.local.dao.RunningSessionDao
import com.example.guiderunningfortheblind.data.local.dao.SafetyEventDao
import com.example.guiderunningfortheblind.data.local.dao.UserProfileDao
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.data.local.entity.SafetyEventEntity
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity

@Database(
    entities = [
        RunningPlanEntity::class,
        UserProfileEntity::class,
        RunningSessionEntity::class,
        SafetyEventEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runningPlanDao(): RunningPlanDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun runningSessionDao(): RunningSessionDao
    abstract fun safetyEventDao(): SafetyEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "guide_running_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
