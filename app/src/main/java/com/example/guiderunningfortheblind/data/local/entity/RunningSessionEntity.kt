package com.example.guiderunningfortheblind.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "running_sessions")
data class RunningSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val planId: Long? = null, // 关联的计划ID
    val startTime: Long,
    val endTime: Long,
    val totalDistance: Double, // 累计距离
    val avgPace: String, // 平均配速
    val avgHeartRate: Int, // 平均心率
    val maxHeartRate: Int, // 最高心率
    val avgCadence: Int, // 平均步频
    val obstacleCount: Int, // 遇到的障碍物次数
    val aerobicEnduranceScore: Int, // 有氧耐力评分
    val recoveryTimeHours: Int, // 建议恢复时间
    val routePointJson: String // 轨迹点集合的JSON字符串，支持返程导航
)
