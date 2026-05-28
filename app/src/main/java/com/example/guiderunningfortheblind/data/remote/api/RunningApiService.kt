package com.example.guiderunningfortheblind.data.remote.api

import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.data.local.entity.SafetyEventEntity
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

interface RunningApiService {
    // Running Plans
    @GET("plans")
    suspend fun fetchRemotePlans(): List<RunningPlanEntity>

    // Running Sessions
    @GET("sessions")
    suspend fun fetchSessions(): List<RunningSessionEntity>
    
    @POST("sessions")
    suspend fun uploadSession(@Body session: RunningSessionEntity): RunningSessionEntity

    // Safety Events
    @GET("safety-events")
    suspend fun fetchSafetyEvents(): List<SafetyEventEntity>
    
    @POST("safety-events")
    suspend fun uploadSafetyEvent(@Body event: SafetyEventEntity): SafetyEventEntity

    // User Profile
    @GET("profile")
    suspend fun fetchProfile(): UserProfileEntity
    
    @PUT("profile")
    suspend fun updateProfile(@Body profile: UserProfileEntity): UserProfileEntity
}