package com.example.guiderunningfortheblind.data.repository

import com.example.guiderunningfortheblind.data.local.dao.RunningPlanDao
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import com.example.guiderunningfortheblind.data.remote.api.RunningApiService
import kotlinx.coroutines.flow.Flow

class RunningRepository(
    private val dao: RunningPlanDao,
    private val api: RunningApiService,
) {
    // 提供给 UI 的单一数据流
    val allPlans: Flow<List<RunningPlanEntity>> = dao.getAllPlans()

    // 刷新逻辑：请求网络并更新本地数据库
    suspend fun refreshPlans() {
        if (AppConfig.IS_OFFLINE_MODE) {
            android.util.Log.d("RunningRepository", "离线模式：已跳过远程计划同步")
            return
        }
        try {
            val remoteData = api.fetchRemotePlans()
            if (remoteData.isNotEmpty()) {
                dao.deleteAll()
                dao.insertPlans(remoteData)
            }
        } catch (e: Exception) {
            /* 记录日志 */
            android.util.Log.d("RunningRepository", "  ")
        }
    }

    suspend fun insert(plan: RunningPlanEntity) = dao.insertPlans(listOf(plan))
    suspend fun deletePlan(plan: RunningPlanEntity) = dao.deletePlan(plan)

}