package com.example.guiderunningfortheblind.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "running_plans")
data class RunningPlanEntity(
    @PrimaryKey val planId: String,
    val title: String,                // 例如：“3公里轻松跑”
    val goalDistance: Double,          // 目标距离（米）
    val targetPace: String,            // 目标配速（如 "6'30\""）
    val isWarmupIncluded: Boolean = true, // 是否包含热身提示
    val aiPlanJson: String? = null,    // AI生成的计划JSON
    val createdAt: Long = System.currentTimeMillis()
)
