package com.example.guiderunningfortheblind.navigation

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.guiderunningfortheblind.location.LocationManager
import com.example.guiderunningfortheblind.model.RunningMetrics
import com.example.guiderunningfortheblind.speech.NavigationAnnouncer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 简化后的导航/跑步管理器
 * 移除高德导航SDK依赖，专注于AI计划跑的状态维护、GPS监测和跑步数据统计
 */
class NavigationManager(
    private val context: Context,
    private val locationManager: LocationManager,
    private val navigationAnnouncer: NavigationAnnouncer,
    scope: CoroutineScope
) {
    companion object {
        private const val TAG = "NavigationManager"
        private const val GPS_WEAK_ACCURACY = 20f
        private const val GPS_DEGRADE_INTERVAL = 30000L
        private const val LOCATION_INTERVAL_MS = 2000L
    }

    private val navScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    // 跑步状态
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _metrics = MutableStateFlow(RunningMetrics())
    val metrics: StateFlow<RunningMetrics> = _metrics.asStateFlow()

    // 运行状态控制
    private val isRunning = AtomicBoolean(false)
    private var locationJob: Job? = null

    // GPS状态
    private var weakGpsAlertTime = 0L
    private var isGpsWeak = false

    // 跑步数据
    private var totalDistance = 0.0
    private var sessionStartTime = 0L
    private var prevLocation: Location? = null

    /**
     * 启动 AI 计划跑步模式
     */
    fun startAiPlannedRun() {
        Log.i(TAG, "启动 AI 计划跑步")
        isRunning.set(true)
        _isRecording.value = true
        sessionStartTime = System.currentTimeMillis()
        resetStats()
        startLocationTracking()
        
        navScope.launch {
            navigationAnnouncer.announceImmediate("AI 计划跑步已启动，已为您开启避障监测，请跟随指引。")
        }
    }

    fun pauseRun() {
        if (isRunning.get()) {
            isRunning.set(false)
            navScope.launch {
                navigationAnnouncer.announceImmediate("跑步已暂停。")
            }
        }
    }

    fun resumeRun() {
        if (!isRunning.get() && _isRecording.value) {
            isRunning.set(true)
            navScope.launch {
                navigationAnnouncer.announceImmediate("跑步已继续。")
            }
        }
    }

    fun stopRun() {
        Log.i(TAG, "停止跑步")
        isRunning.set(false)
        _isRecording.value = false
        locationJob?.cancel()
        resetStats()
        navigationAnnouncer.reset()
    }

    private fun startLocationTracking() {
        locationJob?.cancel()
        locationJob = navScope.launch {
            locationManager.getLocationUpdates(LOCATION_INTERVAL_MS)
                .catch { e -> Log.e(TAG, "位置更新流异常", e) }
                .collect { location ->
                    if (!isRunning.get()) return@collect
                    handleLocationUpdate(location)
                }
        }
    }

    private fun handleLocationUpdate(location: Location) {
        checkGpsSignal(location)
        updateRunningStats(location)
        announceStatsIfNeeded()
    }

    private fun checkGpsSignal(location: Location) {
        val isWeak = location.accuracy > GPS_WEAK_ACCURACY
        if (isWeak) {
            val now = System.currentTimeMillis()
            if (now - weakGpsAlertTime > GPS_DEGRADE_INTERVAL) {
                weakGpsAlertTime = now
                isGpsWeak = true
                navScope.launch {
                    navigationAnnouncer.announceImmediate("GPS 信号较弱，请前往开阔地带，以免影响数据统计。")
                }
            }
        } else if (isGpsWeak) {
            isGpsWeak = false
            Log.i(TAG, "GPS 信号已恢复")
        }
    }

    private fun updateRunningStats(location: Location) {
        val prev = prevLocation
        if (prev != null) {
            val dist = prev.distanceTo(location).toDouble()
            if (dist in 0.5..100.0) {
                totalDistance += dist
            }
        }
        prevLocation = location

        val durationMs = System.currentTimeMillis() - sessionStartTime
        val pace = calculatePace(totalDistance, durationMs)
        
        _metrics.value = RunningMetrics(
            distanceMeters = totalDistance,
            durationMillis = durationMs,
            currentPace = pace
        )
    }

    private fun calculatePace(distanceMeters: Double, durationMs: Long): String {
        val distanceKm = distanceMeters / 1000.0
        if (distanceKm < 0.01) return "0'00\""
        
        val durationMin = durationMs / 60000.0
        val paceMinPerKm = durationMin / distanceKm
        val min = paceMinPerKm.toInt()
        val sec = ((paceMinPerKm - min) * 60).toInt()
        return "$min'${sec.toString().padStart(2, '0')}\""
    }

    private fun announceStatsIfNeeded() {
        // 每 1 公里播报一次
        val interval = 1000.0
        if (totalDistance > 0 && (totalDistance % interval) < 10) {
            val metrics = _metrics.value
            navigationAnnouncer.announceRunningData(
                metrics.distanceMeters / 1000.0,
                metrics.currentPace,
                metrics.durationMillis
            )
        }
    }

    private fun resetStats() {
        totalDistance = 0.0
        prevLocation = null
        _metrics.value = RunningMetrics()
    }

    /**
     * 安全预警：直接转发给播报器
     */
    fun onSafetyAlert(alertText: String) {
        navScope.launch {
            navigationAnnouncer.announceSafetyAlert(alertText)
        }
    }
}
