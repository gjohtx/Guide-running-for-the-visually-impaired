package com.example.guiderunningfortheblind

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.guiderunningfortheblind.data.local.AppDatabase
import com.example.guiderunningfortheblind.data.local.dao.RunningPlanDao
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RunningPlanDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RunningPlanDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.runningPlanDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadPlan() = runBlocking {
        val plan = RunningPlanEntity("1", "5公里挑战", 5000.0, "6'00\"", true)
        dao.insertPlans(listOf(plan))

        val allPlans = dao.getAllPlans().first()
        assertEquals(allPlans[0].title, "5公里挑战")
    }

    @Test
    fun updateAndGetPlanById() = runBlocking {
        val plan = RunningPlanEntity("1", "5公里挑战", 5000.0, "6'00\"", true)
        dao.insertPlans(listOf(plan))

        val updatedPlan = plan.copy(title = "10公里挑战", goalDistance = 10000.0)
        dao.updatePlan(updatedPlan)

        val retrieved = dao.getPlanById("1")
        assertEquals("10公里挑战", retrieved?.title)
        assertEquals(10000.0, retrieved?.goalDistance ?: 0.0, 0.0)
    }

    @Test
    fun deletePlan() = runBlocking {
        val plan = RunningPlanEntity("1", "临时计划", 1000.0, "7'00\"", false)
        dao.insertPlans(listOf(plan))
        
        dao.deletePlan(plan)
        val retrieved = dao.getPlanById("1")
        assertEquals(null, retrieved)
    }
}