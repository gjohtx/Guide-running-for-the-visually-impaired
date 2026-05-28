package com.example.guiderunningfortheblind

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.guiderunningfortheblind.data.local.AppDatabase
import com.example.guiderunningfortheblind.data.local.dao.RunningSessionDao
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RunningSessionEntityDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RunningSessionDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.runningSessionDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadSession() = runBlocking {
        val session = createTestSession(5000.0)
        val id = dao.insertSession(session)
        val sessions = dao.getAllSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(5000.0, sessions[0].totalDistance, 0.0)
    }

    @Test
    fun updateSession() = runBlocking {
        val session = createTestSession(5000.0)
        val id = dao.insertSession(session)
        
        val insertedSession = dao.getSessionById(id)!!
        val updated = insertedSession.copy(totalDistance = 6000.0, avgHeartRate = 155)
        dao.updateSession(updated)
        
        val retrieved = dao.getSessionById(id)
        assertEquals(6000.0, retrieved?.totalDistance ?: 0.0, 0.0)
        assertEquals(155, retrieved?.avgHeartRate)
    }

    @Test
    fun deleteSession() = runBlocking {
        val session = createTestSession(1000.0)
        val id = dao.insertSession(session)
        val insertedSession = dao.getSessionById(id)!!
        
        dao.deleteSession(insertedSession)
        val retrieved = dao.getSessionById(id)
        assertEquals(null, retrieved)
    }

    private fun createTestSession(distance: Double) = RunningSessionEntity(
        startTime = System.currentTimeMillis(),
        endTime = System.currentTimeMillis() + 3600000,
        totalDistance = distance,
        avgPace = "6'00\"",
        avgHeartRate = 150,
        maxHeartRate = 170,
        avgCadence = 180,
        obstacleCount = 2,
        aerobicEnduranceScore = 85,
        recoveryTimeHours = 24,
        routePointJson = "[]"
    )
}
