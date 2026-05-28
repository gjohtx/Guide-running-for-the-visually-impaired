package com.example.guiderunningfortheblind.health

import android.content.Context
import androidx.annotation.RequiresApi
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient by lazy { 
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null 
    }

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    suspend fun hasPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun getLatestHeartRate(startTimeMillis: Long): Int? {
        val client = healthConnectClient ?: return null
        try {
            val startTime = Instant.ofEpochMilli(startTimeMillis)
            val response = client.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.after(startTime)
                )
            )
            // HeartRateRecord contains a list of samples (beatsPerMinute and time)
            return response.records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute?.toInt()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
}
