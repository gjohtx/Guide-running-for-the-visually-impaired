package com.example.guiderunningfortheblind.location

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import android.location.Location
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationManager(private val context: Context) {

    fun getLocationUpdates(intervalMillis: Long): Flow<Location> = callbackFlow {
        val client = AMapLocationClient(context.applicationContext)
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = intervalMillis
            isOnceLocation = false
        }
        client.setLocationOption(option)
        client.setLocationListener { aMapLocation ->
            if (aMapLocation != null && aMapLocation.errorCode == 0) {
                // 把高德Location转换为标准Location
                val loc = Location("amap").apply {
                    latitude = aMapLocation.latitude
                    longitude = aMapLocation.longitude
                    accuracy = aMapLocation.accuracy
                    speed = aMapLocation.speed
                    bearing = aMapLocation.bearing
                    time = aMapLocation.time
                }
                trySend(loc)
            }
        }
        client.startLocation()
        awaitClose { client.stopLocation(); client.onDestroy() }
    }
}

