package com.example.guiderunningfortheblind

import android.app.Application
import android.util.Log
import com.amap.api.maps.MapsInitializer
import com.amap.api.location.AMapLocationClient
import com.example.guiderunningfortheblind.data.local.AppDatabase
import com.example.guiderunningfortheblind.data.remote.api.RunningApiService
import com.example.guiderunningfortheblind.data.repository.*
import com.example.guiderunningfortheblind.health.HealthConnectManager
import com.example.guiderunningfortheblind.location.LocationManager
import com.example.guiderunningfortheblind.navigation.NavigationManager
import com.example.guiderunningfortheblind.speech.*
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * MainApplication - 应用入口
 *
 * 【修复摘要】
 * 1. 语音组件（VoiceCommandManager）现在在 init 中即初始化识别器，不依赖权限状态
 * 2. 权限状态在 MainActivity 的 onRequestPermissionsResult 中通过 VoiceCoordinator 同步
 * 3. 确保 VoiceCoordinator 在创建时 VoiceCommandManager 和 VoiceQueueManager 已就绪
 */
@HiltAndroidApp
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
            MapsInitializer.initialize(this)
            Log.d("MainApplication", "高德地图初始化成功")
        } catch (e: Exception) {
            Log.e("MainApplication", "高德地图初始化失败: ${e.message}", e)
        }

        // 【新增】初始化语音组件
        initVoiceComponents()
    }

    // 核心单例
    private val database by lazy { AppDatabase.getDatabase(this) }
    val locationManager by lazy { LocationManager(this) }

    /**
     * 语音队列管理器（TTS 播报）
     * Application 级单例，全局共享
     */
    val voiceQueueManager by lazy { VoiceQueueManager(this) }

    /**
     * 语音指令管理器（STT 识别）
     * 【修复】现在在 init 中即初始化 SpeechRecognizer，不等待权限授予
     * 权限状态通过 onPermissionResult() 动态同步
     */
    val voiceCommandManager by lazy {
        // 使用 Application Context 创建，避免 Activity 上下文泄漏
        VoiceCommandManager(this).also {
            Log.i("MainApplication", "VoiceCommandManager 已创建，识别器将在 init 中初始化")
        }
    }

    /**
     * 语音协调器（STT/TTS 互斥协调）
     * 【修复】确保 VoiceCommandManager 先创建
     */
    val voiceCoordinator by lazy {
        VoiceCoordinator(voiceCommandManager, voiceQueueManager).also {
            Log.i("MainApplication", "VoiceCoordinator 已创建")
        }
    }

    val healthConnectManager by lazy { HealthConnectManager(this) }

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://jsonplaceholder.typicode.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    private val apiService by lazy { retrofit.create(RunningApiService::class.java) }

    val runningSessionRepository by lazy {
        RunningSessionRepository(database.runningSessionDao(), database.safetyEventDao(), apiService)
    }
    val runningRepository by lazy { RunningRepository(database.runningPlanDao(), apiService) }
    val userProfileRepository by lazy { UserProfileRepository(database.userProfileDao(), apiService) }

    // 跑步管理单例
    private val applicationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val navigationAnnouncer by lazy {
        NavigationAnnouncer(voiceQueueManager, applicationScope)
    }
    val navigationManager by lazy {
        NavigationManager(
            context = this,
            locationManager = locationManager,
            navigationAnnouncer = navigationAnnouncer,
            scope = applicationScope
        )
    }
    val navigationVoiceCommandHandler by lazy {
        NavigationVoiceCommandHandler(
            navigationManager = navigationManager,
            voiceQueueManager = voiceQueueManager,
            scope = applicationScope
        )
    }

    /**
     * 【新增】初始化语音组件
     * 确保懒加载的单例在应用启动时被初始化
     */
    private fun initVoiceComponents() {
        // 触发懒加载初始化
        try {
            val vqm = voiceQueueManager
            val vcm = voiceCommandManager
            val vc = voiceCoordinator
            Log.i("MainApplication", "语音组件初始化完成: " +
                    "TTS=${vqm.isReady()}, " +
                    "STT可用=${vcm.isAvailable.value}")
        } catch (e: Exception) {
            Log.e("MainApplication", "语音组件初始化异常", e)
        }
    }
}
