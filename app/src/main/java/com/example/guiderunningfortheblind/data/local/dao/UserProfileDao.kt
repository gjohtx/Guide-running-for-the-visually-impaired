package com.example.guiderunningfortheblind.data.local.dao

import androidx.room.*
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    fun getUserProfile(userId: String = "default_user"): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    suspend fun getProfileSync(userId: String = "default_user"): UserProfileEntity?

    /**
     * 【新增】获取默认用户资料（AI教练使用，无需传参）
     */
    @Query("SELECT * FROM user_profiles WHERE userId = 'default_user' LIMIT 1")
    suspend fun getDefaultProfile(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfileEntity)

    @Update
    suspend fun updateProfile(profile: UserProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: UserProfileEntity)
}
