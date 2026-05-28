package com.example.guiderunningfortheblind.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "safety_events")
data class SafetyEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val obstacleType: String, // 坑洼、行人、宠物、低矮路障
    val detectionDistance: Float, // 检测时的距离
    val detectionLatencyMs: Long, // 检测延迟，需 < 300ms
    val userPaceAtMoment: String, // 动态调整预警距离时的配速
    val isSuccessAvoided: Boolean = true // 是否成功避让
)
