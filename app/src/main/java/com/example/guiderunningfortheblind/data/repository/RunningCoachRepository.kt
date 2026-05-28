package com.example.guiderunningfortheblind.data.repository

import com.example.guiderunningfortheblind.data.local.dao.RunningPlanDao
import com.example.guiderunningfortheblind.data.local.dao.RunningSessionDao
import com.example.guiderunningfortheblind.data.local.dao.UserProfileDao
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI跑步教练数据仓库
 *
 * 封装三表数据的获取，为AiRunningCoach提供统一数据接口。
 */
@Singleton
class RunningCoachRepository @Inject constructor(
    private val runningPlanDao: RunningPlanDao,
    private val userProfileDao: UserProfileDao,
    private val runningSessionDao: RunningSessionDao
) {

    /**
     * 获取当前跑步计划
     * @param planId 计划ID字符串
     */
    suspend fun getCurrentPlan(planId: String?): RunningPlanEntity? {
        if (planId.isNullOrBlank()) return null
        return try {
            runningPlanDao.getPlanById(planId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取默认用户资料
     */
    suspend fun getUserProfile(): UserProfileEntity? {
        return try {
            userProfileDao.getDefaultProfile()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取最新的跑步会话
     */
    suspend fun getLatestRunningSession(): RunningSessionEntity? {
        return try {
            runningSessionDao.getLatestRunningSession()
        } catch (e: Exception) {
            null
        }
    }
}
