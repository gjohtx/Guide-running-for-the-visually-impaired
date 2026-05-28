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
class RunningDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RunningPlanDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
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
}
