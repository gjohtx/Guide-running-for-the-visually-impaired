package com.example.guiderunningfortheblind.ui.running

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.data.repository.RunningSessionRepository
import com.example.guiderunningfortheblind.location.LocationManager
import com.example.guiderunningfortheblind.health.HealthConnectManager
import android.location.Location
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

data class RunningUiState(
    val isRunning: Boolean = false,
    val currentDistance: Double = 0.0,
    val currentPace: String = "0'00\"",
    val currentHeartRate: Int? = null,
    val startTime: Long = 0L,
    val obstacleCount: Int = 0,
    val isFinished: Boolean = false
)

class RunningSessionViewModel(
    private val repository: RunningSessionRepository,
    private val locationManager: LocationManager,
    private val healthConnectManager: HealthConnectManager,
    private val userProfileRepository: com.example.guiderunningfortheblind.data.repository.UserProfileRepository
) : ViewModel() {

    private val tag = "RunningSessionViewModel"

    private val _uiState = MutableStateFlow(RunningUiState())
    val uiState: StateFlow<RunningUiState> = _uiState.asStateFlow()

    private val _voiceEvents = MutableSharedFlow<String>()
    val voiceEvents: SharedFlow<String> = _voiceEvents.asSharedFlow()

    private var userAge = 30
    private var lastHrAlertTime = 0L
    private val hrAlertInterval = 30000L

    private var lastDistanceKmMark = 0
    private val lastLocation = AtomicReference<Location?>(null)
    private val startLocation = AtomicReference<Location?>(null)
    private var locationJob: Job? = null
    private var healthJob: Job? = null

    /** 运动实际开始时间（第一个有效GPS点到达时的系统时间） */
    private var activeRunStartTime = 0L

    /** 累积运动时间（毫秒），支持暂停/恢复 */
    private var accumulatedRunTimeMs = 0L

    /** 是否已接收到第一个有效GPS点 */
    private var hasFirstValidLocation = false

    /** 已过滤的轨迹点（线程安全列表） */
    private val routePoints = Collections.synchronizedList(ArrayList<Map<String, Double>>())

    private var activeSessionId: Long? = null

    // ========== GPS过滤参数 ==========
    companion object {
        /** 最大可接受GPS精度（米），超过此值丢弃 */
        private const val MAX_ACCEPTABLE_ACCURACY = 30f

        /** 跳点速度阈值（米/秒），超过此值视为GPS跳点。约43km/h，远超人类跑步极限 */
        private const val MAX_REASONABLE_SPEED = 12.0

        /** 最小累积距离（米），小于此值视为GPS噪声/漂移，不累积 */
        private const val MIN_DISTANCE_METERS = 1.0

        /** GPS采样间隔（毫秒），1秒一次提高精度 */
        private const val GPS_UPDATE_INTERVAL_MS = 1000L
    }

    fun startSession() {
        _uiState.update { it.copy(isRunning = true, startTime = System.currentTimeMillis()) }

        viewModelScope.launch {
            try {
                val loc = withTimeout(10_000L) {
                    locationManager.getLocationUpdates(1000L).first()
                }
                startLocation.set(loc)
                android.util.Log.d(tag, "Start location captured: ${loc.latitude}, ${loc.longitude}")
            } catch (e: TimeoutCancellationException) {
                android.util.Log.w(tag, "GPS initial fix timeout")
                _voiceEvents.emit("GPS 定位超时，请检查定位服务是否开启")
            } catch (e: Exception) {
                android.util.Log.e(tag, "Failed to capture start location: ${e.message}")
            }
        }

        viewModelScope.launch {
            userProfileRepository.userProfileEntity.collect { profile ->
                profile?.let { userAge = it.age }
            }
        }

        startLocationTracking()
        startHealthTracking()
        viewModelScope.launch {
            _voiceEvents.emit("跑步已开始，请注意安全。")
        }
    }

    private fun startLocationTracking() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationManager.getLocationUpdates(GPS_UPDATE_INTERVAL_MS).collect { location ->
                processLocation(location)
            }
        }
    }

    private fun startHealthTracking() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return

        healthJob?.cancel()
        healthJob = viewModelScope.launch {
            if (!healthConnectManager.hasPermissions()) {
                android.util.Log.w(tag, "Health Connect permissions not granted.")
                return@launch
            }

            val sessionStartTime = System.currentTimeMillis()
            while (isActive) {
                try {
                    val hr = healthConnectManager.getLatestHeartRate(sessionStartTime)
                    if (hr != null) {
                        _uiState.update { it.copy(currentHeartRate = hr) }

                        val maxHr = 220 - userAge
                        val intensity = hr.toFloat() / maxHr
                        val currentTime = System.currentTimeMillis()

                        if (intensity > 0.90) {
                            if (currentTime - lastHrAlertTime > hrAlertInterval / 2) {
                                lastHrAlertTime = currentTime
                                _voiceEvents.emit("危险，您的心率已达到 $hr 次每分钟，请立即停止跑步并休息。")
                            }
                        } else if (intensity > 0.80) {
                            if (currentTime - lastHrAlertTime > hrAlertInterval) {
                                lastHrAlertTime = currentTime
                                _voiceEvents.emit("警告，心率过高：$hr 次每分钟。请放慢速度。")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(tag, "Error reading heart rate: ${e.message}")
                }
                delay(5000L)
            }
        }
    }

    /**
     * 处理GPS位置更新 —— 含精度过滤、跳点过滤、噪声过滤
     */
    private fun processLocation(location: Location) {
        if (!_uiState.value.isRunning) return

        // === 1. GPS精度过滤：丢弃精度不足的点 ===
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCEPTABLE_ACCURACY) {
            android.util.Log.d(tag, "GPS精度不足: accuracy=${location.accuracy}m, 丢弃")
            return
        }

        // === 2. 初始化：首个有效GPS点开始运动计时 ===
        if (!hasFirstValidLocation) {
            hasFirstValidLocation = true
            startLocation.set(location)
            activeRunStartTime = System.currentTimeMillis()
            android.util.Log.i(
                tag,
                "首个有效GPS点: accuracy=${location.accuracy}m, " +
                "lat=${location.latitude}, lng=${location.longitude}, 运动计时开始"
            )
        }

        val currentLastLocation = lastLocation.get()
        if (currentLastLocation != null) {
            val distanceToLast = currentLastLocation.distanceTo(location).toDouble()

            // === 3. 跳点过滤：基于速度阈值检测异常GPS跳变 ===
            val timeDeltaMs = location.time - currentLastLocation.time
            if (timeDeltaMs > 0) {
                val impliedSpeed = distanceToLast / (timeDeltaMs / 1000.0)
                if (impliedSpeed > MAX_REASONABLE_SPEED) {
                    android.util.Log.w(
                        tag,
                        "GPS跳点过滤: speed=${String.format("%.1f", impliedSpeed)}m/s, " +
                        "dist=${String.format("%.1f", distanceToLast)}m, 丢弃"
                    )
                    // 更新位置但不累加距离（避免后续点相对此跳点也异常）
                    lastLocation.set(location)
                    return
                }
            }

            // === 4. 噪声过滤：距离小于1米视为GPS漂移，不累积 ===
            if (distanceToLast < MIN_DISTANCE_METERS) {
                lastLocation.set(location)
                return
            }

            // === 5. 距离累加 ===
            val totalDistance = _uiState.value.currentDistance + distanceToLast

            // === 6. 配速计算：使用运动时间（从首个有效GPS点开始），避免包含等待GPS的空白时间 ===
            val durationMs = System.currentTimeMillis() - activeRunStartTime + accumulatedRunTimeMs
            val durationMin = durationMs / 60000.0
            val distanceKm = totalDistance / 1000.0

            val paceStr = if (distanceKm > 0.005) {
                // 统一换算为总秒数再拆分分钟和秒，避免浮点精度丢失
                val paceSecPerKm = (durationMin * 60.0 / distanceKm).toInt()
                val min = paceSecPerKm / 60
                val sec = paceSecPerKm % 60
                "$min'${sec.toString().padStart(2, '0')}\""
            } else {
                "0'00\""
            }

            updateMetrics(totalDistance, paceStr)
        }

        lastLocation.set(location)
        // 只有通过全部过滤的点才记录到轨迹
        routePoints.add(mapOf("lat" to location.latitude, "lng" to location.longitude))
    }

    fun updateMetrics(distance: Double, pace: String) {
        _uiState.update { it.copy(currentDistance = distance, currentPace = pace) }

        val currentKmMark = (distance / 1000).toInt()
        if (currentKmMark > lastDistanceKmMark) {
            lastDistanceKmMark = currentKmMark
            viewModelScope.launch {
                _voiceEvents.emit("已完成 $currentKmMark 公里。当前配速为 $pace。")
            }
        }
    }

    fun onObstacleDetected() {
        _uiState.update { it.copy(obstacleCount = it.obstacleCount + 1) }
        viewModelScope.launch {
            _voiceEvents.emit("注意，检测到前方有障碍物。")
        }
    }

    fun endSession() {
        viewModelScope.launch {
            val state = _uiState.value
            val session = RunningSessionEntity(
                startTime = state.startTime,
                endTime = System.currentTimeMillis(),
                totalDistance = state.currentDistance,
                avgPace = state.currentPace,
                avgHeartRate = 0,
                maxHeartRate = 0,
                avgCadence = 0,
                obstacleCount = state.obstacleCount,
                aerobicEnduranceScore = 0,
                recoveryTimeHours = 0,
                routePointJson = Gson().toJson(routePoints.toList())
            )
            activeSessionId = repository.saveSession(session)

            locationJob?.cancel()
            healthJob?.cancel()

            _uiState.update { it.copy(isRunning = false, isFinished = true) }
        }
    }

    fun clearSessionData() {
        _uiState.value = RunningUiState()
        activeSessionId = null
        locationJob?.cancel()
        healthJob?.cancel()
        lastLocation.set(null)
        startLocation.set(null)
        // 修复：完整重置所有运动计时状态
        activeRunStartTime = 0L
        accumulatedRunTimeMs = 0L
        hasFirstValidLocation = false
        lastDistanceKmMark = 0
        routePoints.clear()
    }

    class Factory(
        private val repository: RunningSessionRepository,
        private val locationManager: LocationManager,
        private val healthConnectManager: HealthConnectManager,
        private val userProfileRepository: com.example.guiderunningfortheblind.data.repository.UserProfileRepository
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(RunningSessionViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return RunningSessionViewModel(repository, locationManager, healthConnectManager, userProfileRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
