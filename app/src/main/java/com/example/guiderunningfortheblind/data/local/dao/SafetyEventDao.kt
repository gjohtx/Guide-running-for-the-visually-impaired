package com.example.guiderunningfortheblind.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.guiderunningfortheblind.data.local.entity.SafetyEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SafetyEventDao {
    @Query("SELECT * FROM safety_events WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun getEventsForSession(sessionId: Long): Flow<List<SafetyEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SafetyEventEntity): Long

    @Query("SELECT COUNT(*) FROM safety_events WHERE sessionId = :sessionId AND isSuccessAvoided = 0")
    suspend fun getAvoidanceFailureCount(sessionId: Long): Int

    @Query("DELETE FROM safety_events WHERE sessionId = :sessionId")
    suspend fun deleteEventsForSession(sessionId: Long)
}
