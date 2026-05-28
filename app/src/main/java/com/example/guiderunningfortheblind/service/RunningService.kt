package com.example.guiderunningfortheblind.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.example.guiderunningfortheblind.MainActivity
import com.example.guiderunningfortheblind.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RunningService : Service() {

    private val CHANNEL_ID = "running_service_channel"
    private val NOTIFICATION_ID = 8888

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("助盲跑应用正在后台守护中")
            .setContentText("实时避障与导航服务已激活，请放心跑步。")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(getPendingIntent())
            .setOngoing(true)
            .build()

        // 适配 Android 14/15 的前台服务启动规范
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // TODO: 在此处拉起系统的 LocationManager 和 CameraX 分析流
        return START_STICKY
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Guide Running Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
