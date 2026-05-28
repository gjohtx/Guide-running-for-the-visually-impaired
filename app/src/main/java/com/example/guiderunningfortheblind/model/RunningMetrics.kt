package com.example.guiderunningfortheblind.model

/**
 * 跑步数据度量
 */
data class RunningMetrics(
    val distanceMeters: Double = 0.0,
    val durationMillis: Long = 0L,
    val currentPace: String = "0'00\""
)
