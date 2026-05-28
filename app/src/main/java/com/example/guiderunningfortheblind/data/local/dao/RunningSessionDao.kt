package com.example.guiderunningfortheblind.data.local.dao

import androidx.room.*
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RunningSessionDao {
    @Query("SELECT * FROM running_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<RunningSessionEntity>>

    @Query("SELECT * FROM running_sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: Long): RunningSessionEntity?

    /**
     * 【新增】获取最新的跑步会话（用于AI教练获取当前会话数据）
     */
    @Query("SELECT * FROM running_sessions ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatestRunningSession(): RunningSessionEntity?

    @Query("DELETE FROM running_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: RunningSessionEntity): Long

    @Update
    suspend fun updateSession(session: RunningSessionEntity)

    @Delete
    suspend fun deleteSession(session: RunningSessionEntity)

    @Query("DELETE FROM running_sessions")
    suspend fun deleteAll()
}
