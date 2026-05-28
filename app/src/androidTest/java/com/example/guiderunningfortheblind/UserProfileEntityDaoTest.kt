package com.example.guiderunningfortheblind

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.guiderunningfortheblind.data.local.AppDatabase
import com.example.guiderunningfortheblind.data.local.dao.UserProfileDao
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class UserProfileEntityDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: UserProfileDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.userProfileDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadProfile() = runBlocking {
        val profile = UserProfileEntity(age = 25, targetCadence = 185)
        dao.insertProfile(profile)
        val readProfile = dao.getUserProfile().first()
        assertEquals(25, readProfile?.age)
        assertEquals(185, readProfile?.targetCadence)
    }

    @Test
    fun updateProfile() = runBlocking {
        val profile = UserProfileEntity(age = 25)
        dao.insertProfile(profile)
        
        val updated = profile.copy(age = 26, vibrationIntensity = 0.8f)
        dao.updateProfile(updated)
        
        val read = dao.getProfileSync()
        assertEquals(26, read?.age)
        assertEquals(0.8f, read?.vibrationIntensity ?: 0f, 0.01f)
    }

    @Test
    fun deleteProfile() = runBlocking {
        val profile = UserProfileEntity()
        dao.insertProfile(profile)
        
        dao.deleteProfile(profile)
        val read = dao.getProfileSync()
        assertEquals(null, read)
    }
}
