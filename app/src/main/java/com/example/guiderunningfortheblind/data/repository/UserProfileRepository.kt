package com.example.guiderunningfortheblind.data.repository

import com.example.guiderunningfortheblind.data.local.dao.UserProfileDao
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity
import com.example.guiderunningfortheblind.data.remote.api.RunningApiService
import kotlinx.coroutines.flow.Flow

class UserProfileRepository(
    private val userProfileDao: UserProfileDao,
    private val api: RunningApiService,
) {
    val userProfileEntity: Flow<UserProfileEntity?> = userProfileDao.getUserProfile()

    suspend fun refreshProfile() {
        if (AppConfig.IS_OFFLINE_MODE) return // 离线模式：跳过网络拉取
        try {
            val remoteProfile = api.fetchProfile()
            userProfileDao.insertProfile(remoteProfile)
        } catch (e: Exception) { }
    }

    suspend fun saveProfile(profile: UserProfileEntity) {
        userProfileDao.insertProfile(profile)
        if (AppConfig.IS_OFFLINE_MODE) return
        try {
            api.updateProfile(profile)
        } catch (e: Exception) { }
    }

    suspend fun updateProfile(profile: UserProfileEntity) {
        val fixedProfile = profile.copy(userId = "default_user")
        userProfileDao.insertProfile(fixedProfile)
        if (AppConfig.IS_OFFLINE_MODE) {
            android.util.Log.d("UserProfileRepository", "离线模式：已跳过远程配置同步")
            return
        }

        try {
            api.updateProfile(fixedProfile)
        } catch (e: Exception) {
            android.util.Log.e("UserProfileRepository", "Remote sync failed: ${e.message}")
        }
    }
}