package com.example.guiderunningfortheblind

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.guiderunningfortheblind.data.local.AppDatabase
import com.example.guiderunningfortheblind.data.local.dao.SafetyEventDao
import com.example.guiderunningfortheblind.data.local.entity.SafetyEventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SafetyEventEntityDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SafetyEventDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.safetyEventDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndGetEvents() = runBlocking {
        val event = SafetyEventEntity(
            sessionId = 1L,
            timestamp = System.currentTimeMillis(),
            obstacleType = "坑洼",
            detectionDistance = 2.5f,
            detectionLatencyMs = 150L,
            userPaceAtMoment = "6'00\"",
            isSuccessAvoided = true
        )
        dao.insertEvent(event)
        
        val events = dao.getEventsForSession(1L).first()
        assertEquals(1, events.size)
        assertEquals("坑洼", events[0].obstacleType)
    }

    @Test
    fun failureCountTest() = runBlocking {
        dao.insertEvent(createEvent(1L, false))
        dao.insertEvent(createEvent(1L, true))
        dao.insertEvent(createEvent(1L, false))
        
        val failures = dao.getAvoidanceFailureCount(1L)
        assertEquals(2, failures)
    }

    @Test
    fun deleteForSession() = runBlocking {
        dao.insertEvent(createEvent(1L, true))
        dao.insertEvent(createEvent(2L, true))
        
        dao.deleteEventsForSession(1L)
        
        val session1Events = dao.getEventsForSession(1L).first()
        val session2Events = dao.getEventsForSession(2L).first()
        
        assertEquals(0, session1Events.size)
        assertEquals(1, session2Events.size)
    }

    private fun createEvent(sessionId: Long, success: Boolean) = SafetyEventEntity(
        sessionId = sessionId,
        timestamp = System.currentTimeMillis(),
        obstacleType = "行人",
        detectionDistance = 3.0f,
        detectionLatencyMs = 100L,
        userPaceAtMoment = "5'30\"",
        isSuccessAvoided = success
    )
}
