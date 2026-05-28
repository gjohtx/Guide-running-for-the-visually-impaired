package com.example.guiderunningfortheblind.data.local.dao

import androidx.room.*
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RunningPlanDao {
    @Query("SELECT * FROM running_plans")
    fun getAllPlans(): Flow<List<RunningPlanEntity>>

    @Query("SELECT * FROM running_plans WHERE planId = :planId")
    suspend fun getPlanById(planId: String): RunningPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlans(plans: List<RunningPlanEntity>)

    @Update
    suspend fun updatePlan(plan: RunningPlanEntity)

    @Delete
    suspend fun deletePlan(plan: RunningPlanEntity)

    @Query("DELETE FROM running_plans")
    suspend fun deleteAll()
}