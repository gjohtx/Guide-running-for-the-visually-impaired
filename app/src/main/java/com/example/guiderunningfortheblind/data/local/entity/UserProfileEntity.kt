package com.example.guiderunningfortheblind.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val userId: String = "default_user",
    val age: Int = 0, // 目标用户20-50岁
    val targetCadence: Int = 180, // 目标步频，默认180步/分钟
    val vibrationIntensity: Float = 1.0f, // 震动强度可独立调节
    val voiceFeedbackIntervalDistance: Int = 200, // 播报间隔（米），默认200米
    val voiceFeedbackIntervalTime: Int = 60, // 播报间隔（秒）
    val isVirtualPartnerEnabled: Boolean = false, // 虚拟陪跑员开关
    val virtualPartnerPace: String = "6'00\"", // 虚拟伙伴配速
    val useFlashlightAtNight: Boolean = true // 低光环境下自动开启闪光灯
)
