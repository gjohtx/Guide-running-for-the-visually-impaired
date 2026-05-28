package com.example.guiderunningfortheblind.model

/**
 * 播报优先级
 */
enum class AnnouncePriority(val level: Int) {
    EMERGENCY(0),    // 紧急（避障）
    CORRECTION(1),   // 纠正
    NAVIGATION(2),   // 导航
    RUNNING_DATA(3), // 跑步数据
    ELEVATION(4),    // 海拔
    INFO(5)          // 信息
}

/**
 * 播报项数据类
 */
data class Announcement(
    val id: String,
    val text: String,
    val priority: AnnouncePriority,
    val timestamp: Long,
    val isMergeable: Boolean = false,
    val expireTimeMs: Long = 0,
    val relatedStepIndex: Int = -1
)
