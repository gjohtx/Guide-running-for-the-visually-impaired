package com.example.guiderunningfortheblind.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音识别管理器 - 支持连续监听和单次识别两种模式
 *
 * 【修复摘要】
 * 1. 新增单次识别模式（SINGLE_SHOT）：按住说话，松手后自动停止并返回结果
 * 2. 修复 init 时权限检查导致识别器无法初始化的问题
 * 3. 识别结果通过 recognitionResult StateFlow 暴露，UI 层可直接观察
 * 4. 未识别到文字时发送 RecognitionResult.NoMatch，UI 层可语音播报反馈
 * 5. 权限与识别器初始化分离，识别器在 init 时即创建
 */
@Singleton
class VoiceCommandManager @Inject constructor(
    @ApplicationContext private val context: Context
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ═══════════════════════════════════════════════════════════
    //  公开 API：Flow（供 UI 层观察）
    // ═══════════════════════════════════════════════════════════

    /** 连续模式指令流（导航指令等） */
    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val commands = _commands.asSharedFlow()

    /** 语音识别器是否可用（系统层面，不依赖权限） */
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable = _isAvailable.asStateFlow()

    /** 录音权限是否已授予 */
    private val _hasPermission = MutableStateFlow(false)
    val hasPermission = _hasPermission.asStateFlow()

    /** 当前错误状态 */
    private val _errorState = MutableStateFlow<RecognizerError?>(null)
    val errorState = _errorState.asStateFlow()

    /** 是否处于冷却期 */
    private val _isCoolingDown = MutableStateFlow(false)
    val isCoolingDown = _isCoolingDown.asStateFlow()

    /** 【新增】是否正在监听 */
    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    /** 【新增】单次识别结果（UI 层观察此 Flow 获取识别文字） */
    private val _recognitionResult = MutableStateFlow<RecognitionResult?>(null)
    val recognitionResult = _recognitionResult.asStateFlow()

    /** 【新增】请求使用 RecognizerIntent fallback（UI 层监听并启动系统语音对话框） */
    private val _requestFallbackRecognition = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestFallbackRecognition = _requestFallbackRecognition.asSharedFlow()

    // ═══════════════════════════════════════════════════════════
    //  内部状态
    // ═══════════════════════════════════════════════════════════

    /** 【新增】当前监听模式 */
    private var currentMode: ListeningMode = ListeningMode.CONTINUOUS

    private var isListeningInternal = false
    private var isAllowedToListen = false

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private var errorConsecutiveCount = 0
    private var permissionDeniedFlag = false
    private var coolDownJob: Job? = null

    /** 【新增】SpeechRecognizer 是否已标记为不可用（ColorOS 兼容） */
    private var speechRecognizerBroken = false

    /** 【新增】记录开始监听的时间戳，用于检测快速失败 */
    private var listeningStartTime = 0L

    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val MAX_CONSECUTIVE_ERRORS = 5
        private const val BASE_RETRY_DELAY_MS = 1_500L
        private const val COOLDOWN_DURATION_MS = 8_000L
    }

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // 【修复】将权限检查与识别器初始化分离
        // 只要系统支持语音识别，就初始化识别器实例
        checkAvailability()
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            initRecognizer()
            setupAudioFocus()
            Log.i(TAG, "语音识别器初始化完成，权限状态=${_hasPermission.value}")
        } else {
            Log.e(TAG, "设备不支持语音识别")
            _isAvailable.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  【新增】单次识别模式 API（按住说话）
    // ═══════════════════════════════════════════════════════════

    /**
     * 开始单次识别（按住说话模式）
     * 
     * 使用方式：
     * 1. 用户按下语音按钮时调用此方法
     * 2. 用户松开按钮时调用 [stopSingleShotListening]
     * 3. 观察 [recognitionResult] Flow 获取识别结果
     * 
     * @return true 表示成功启动，false 表示启动失败（权限不足或识别器不可用）
     */
    fun startSingleShotListening(): Boolean {
        Log.d(TAG, "开始单次识别，权限=${_hasPermission.value}, 可用=${_isAvailable.value}")

        // 检查权限
        if (!_hasPermission.value) {
            _errorState.value = RecognizerError.PERMISSION_DENIED
            _recognitionResult.value = RecognitionResult.Error(RecognizerError.PERMISSION_DENIED)
            Log.w(TAG, "单次识别启动失败：权限未授予")
            return false
        }

        // 检查识别器可用性
        if (!_isAvailable.value) {
            restartIfNeeded()
            if (!_isAvailable.value) {
                _recognitionResult.value = RecognitionResult.Error(RecognizerError.NOT_AVAILABLE)
                Log.w(TAG, "单次识别启动失败：识别器不可用")
                return false
            }
        }

        // 取消之前的冷却
        coolDownJob?.cancel()
        _isCoolingDown.value = false

        // 设置单次模式
        currentMode = ListeningMode.SINGLE_SHOT
        isAllowedToListen = true
        errorConsecutiveCount = 0
        _errorState.value = null
        _recognitionResult.value = null

        startListening()
        return true
    }

    /**
     * 停止单次识别（用户松开按钮时调用）
     * 
     * 【重要】此方法不会立即产生结果，识别结果通过 [recognitionResult] Flow 异步发送。
     * 原因：SpeechRecognizer 需要一定时间处理音频，onResults/onError 是异步回调。
     * 
     * UI 层应该在调用此方法后继续观察 [recognitionResult]，
     * 而不是期待返回值中立即包含识别文字。
     */
    fun stopSingleShotListening() {
        Log.d(TAG, "停止单次识别")
        if (currentMode != ListeningMode.SINGLE_SHOT) return

        isAllowedToListen = false

        // 如果正在监听，调用 stopListening 触发 onResults/onError
        if (isListeningInternal) {
            try {
                speechRecognizer?.stopListening()
                // 注意：stopListening 会触发 onResults 或 onError 回调
                // 识别结果将在回调中通过 _recognitionResult 发送
                Log.d(TAG, "已调用 stopListening，等待识别结果回调...")
            } catch (e: Exception) {
                Log.e(TAG, "停止监听异常", e)
                isListeningInternal = false
                _isListening.value = false
                releaseAudioFocus()
                _recognitionResult.value = RecognitionResult.Error(RecognizerError.AUDIO_ERROR)
            }
        } else {
            // 如果不在监听状态，说明可能没有识别到任何内容
            _recognitionResult.value = RecognitionResult.NoMatch
            releaseAudioFocus()
        }
    }

    /**
     * 【新增】取消当前正在进行的单次识别
     * 用户取消操作或页面销毁时调用
     */
    fun cancelSingleShotListening() {
        Log.d(TAG, "取消单次识别")
        isAllowedToListen = false
        stopInternal()
        _recognitionResult.value = null
    }

    /**
     * 【新增】重置识别结果（在 UI 层消费结果后调用）
     */
    fun clearRecognitionResult() {
        _recognitionResult.value = null
    }

    // ═══════════════════════════════════════════════════════════
    //  连续监听模式 API（原有导航指令模式）
    // ═══════════════════════════════════════════════════════════

    /**
     * 开始连续监听（导航语音指令模式）
     * 识别完成后自动重启，持续监听用户指令
     */
    fun start() {
        Log.d(TAG, "start() 被调用，权限=$_hasPermission.value")

        // 权限检查前置
        if (permissionDeniedFlag || !_hasPermission.value) {
            _errorState.value = RecognizerError.PERMISSION_DENIED
            Log.w(TAG, "start() 被拒绝：录音权限未授予")
            return
        }
        if (!_isAvailable.value) {
            restartIfNeeded()
            if (!_isAvailable.value) return
        }

        currentMode = ListeningMode.CONTINUOUS
        isAllowedToListen = true
        errorConsecutiveCount = 0
        _errorState.value = null
        startListening()
    }

    fun stop() {
        isAllowedToListen = false
        coolDownJob?.cancel()
        _isCoolingDown.value = false
        stopInternal()
    }

    fun pauseForTts() {
        isAllowedToListen = false
        stopInternal()
    }

    fun resumeAfterTts() {
        // 仅在连续模式下自动恢复
        if (currentMode != ListeningMode.CONTINUOUS) return
        if (!_isAvailable.value || permissionDeniedFlag) return
        isAllowedToListen = true
        errorConsecutiveCount = 0
        startListening()
    }

    // ═══════════════════════════════════════════════════════════
    //  权限与生命周期
    // ═══════════════════════════════════════════════════════════

    /**
     * 当权限状态可能改变时刷新
     * 在 onRequestPermissionsResult 中调用
     */
    fun onPermissionResult(granted: Boolean) {
        Log.i(TAG, "权限结果: granted=$granted")
        _hasPermission.value = granted
        if (granted) {
            permissionDeniedFlag = false
            _errorState.value = null
            // 【修复】权限授予后初始化识别器（如果还没初始化）
            if (speechRecognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
                initRecognizer()
                setupAudioFocus()
            }
        } else {
            permissionDeniedFlag = true
            _isAvailable.value = false
            stopInternal()
            Log.w(TAG, "录音权限被拒绝，语音识别不可用")
        }
    }

    /**
     * 【修复】检查语音识别可用性
     * 现在仅检查系统识别器可用性和权限状态，不设置永久标志
     */
    private fun checkAvailability() {
        // 检查录音权限
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        _hasPermission.value = hasPermission

        // 【修复】权限未授予时不设置 permissionDeniedFlag
        // permissionDeniedFlag 只在用户明确拒绝时设置

        // 检查系统识别器是否可用
        val recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        _isAvailable.value = recognizerAvailable

        Log.d(TAG, "可用性检查: 识别器可用=$recognizerAvailable, 权限=$hasPermission")
    }

    fun restartIfNeeded() {
        if (permissionDeniedFlag && !_hasPermission.value) {
            Log.d(TAG, "权限仍被拒绝，跳过重启")
            return
        }
        checkAvailability()
        if (_isAvailable.value && speechRecognizer == null) {
            initRecognizer()
            setupAudioFocus()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════

    private fun initRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@VoiceCommandManager)
            }
            errorConsecutiveCount = 0
            _isAvailable.value = true
            Log.i(TAG, "SpeechRecognizer 创建成功")
        } catch (e: Exception) {
            Log.e(TAG, "创建 SpeechRecognizer 失败", e)
            _isAvailable.value = false
        }
    }

    private fun setupAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setOnAudioFocusChangeListener { }
                .build()
        }
    }

    private fun startListening() {
        // 多层防护
        if (permissionDeniedFlag) {
            Log.w(TAG, "startListening: 权限被拒绝标志位已设置")
            return
        }
        if (!_isAvailable.value || !isAllowedToListen || isListeningInternal) return

        if (_isCoolingDown.value) {
            Log.d(TAG, "处于冷却期，跳过监听")
            return
        }

        // 【新增】如果 SpeechRecognizer 已标记为不可用，直接触发 fallback
        if (speechRecognizerBroken && currentMode == ListeningMode.SINGLE_SHOT) {
            Log.w(TAG, "【ColorOS 兼容】SpeechRecognizer 已标记不可用，直接触发 RecognizerIntent fallback")
            releaseAudioFocus()
            _requestFallbackRecognition.tryEmit(Unit)
            return
        }

        if (errorConsecutiveCount >= MAX_CONSECUTIVE_ERRORS) {
            Log.w(TAG, "连续错误 $errorConsecutiveCount 次，进入冷却期...")
            _isCoolingDown.value = true
            coolDownJob = scope.launch {
                delay(COOLDOWN_DURATION_MS)
                _isCoolingDown.value = false
                errorConsecutiveCount = 0
                if (isAllowedToListen) startListening()
            }
            return
        }

        requestAudioFocus()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 【修复】单次模式下缩短静音超时，更快返回结果
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        isListeningInternal = true
        _isListening.value = true
        listeningStartTime = System.currentTimeMillis()  // 【新增】记录开始时间
        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "startListening 成功 (模式=$currentMode)")
        } catch (e: Exception) {
            Log.e(TAG, "启动监听失败", e)
            isListeningInternal = false
            _isListening.value = false
            errorConsecutiveCount++
            releaseAudioFocus()
        }
    }

    private fun stopInternal() {
        if (!isListeningInternal) return
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "停止监听异常", e)
        } finally {
            isListeningInternal = false
            _isListening.value = false
            releaseAudioFocus()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  RecognitionListener 回调
    // ═══════════════════════════════════════════════════════════

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "onReadyForSpeech: 识别器就绪")
    }

    override fun onBeginningOfSpeech() {
        errorConsecutiveCount = 0
        Log.d(TAG, "onBeginningOfSpeech: 开始检测到语音")
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "onEndOfSpeech: 语音结束 (模式=$currentMode)")
        isListeningInternal = false
        _isListening.value = false
        // 注意：不要在这里释放音频焦点，等待 onResults/onError
    }

    override fun onPartialResults(partialResults: Bundle?) {
        // 可选：处理部分识别结果用于实时显示
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val partialText = matches?.firstOrNull()
        if (partialText != null) {
            Log.d(TAG, "部分识别结果: $partialText")
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onResults(results: Bundle?) {
        isListeningInternal = false
        _isListening.value = false
        releaseAudioFocus()
        errorConsecutiveCount = 0
        _errorState.value = null

        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val command = matches?.firstOrNull()

        if (command.isNullOrBlank()) {
            Log.w(TAG, "onResults: 识别结果为空")
            handleNoMatch("识别结果为空")
            return
        }

        Log.i(TAG, "【识别成功】文字=\"$command\", 模式=$currentMode")

        when (currentMode) {
            ListeningMode.SINGLE_SHOT -> {
                // 【关键】单次模式：发送结果，停止监听
                _recognitionResult.value = RecognitionResult.Success(command)
                isAllowedToListen = false
            }
            ListeningMode.CONTINUOUS -> {
                // 连续模式：原有逻辑
                scope.launch {
                    _commands.emit(command)
                }
                scope.launch {
                    delay(500)
                    if (isAllowedToListen) startListening()
                }
            }
        }
    }

    override fun onError(error: Int) {
        Log.w(TAG, "onError: error=$error, 模式=$currentMode")
        isListeningInternal = false
        _isListening.value = false

        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                Log.d(TAG, "onError: 无匹配/超时 ($error)")
                handleNoMatch("无匹配/超时")
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                // 【关键修复】OPPO ColorOS 等 ROM 可能误报 error=9
                // 收到此错误时不应立即设置永久拒绝标志
                Log.e(TAG, "onError: 系统报告权限不足 ($error)，重新检查权限...")

                val actuallyGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (actuallyGranted) {
                    // 权限实际是 granted，系统误报！不累积错误计数
                    Log.w(TAG, "【ColorOS 兼容】权限已授予，系统误报 error=9")
                    errorConsecutiveCount = 0  // 不累积错误
                    releaseAudioFocus()
                    if (currentMode == ListeningMode.CONTINUOUS && isAllowedToListen) {
                        scope.launch {
                            delay(BASE_RETRY_DELAY_MS)
                            if (isAllowedToListen) startListening()
                        }
                    } else if (currentMode == ListeningMode.SINGLE_SHOT) {
                        if (isAllowedToListen) {
                            // 用户还按着按钮：延迟重试
                            scope.launch {
                                delay(300)
                                if (isAllowedToListen && !isListeningInternal) startListening()
                            }
                        } else {
                            // 用户已松开：发送系统错误结果
                            Log.w(TAG, "【ColorOS 兼容】用户已松开，发送系统错误结果")
                            _recognitionResult.value = RecognitionResult.Error(RecognizerError.CLIENT_ERROR)
                        }
                    }
                } else {
                    // 权限确实未授予
                    Log.e(TAG, "【确认】权限确实未授予")
                    errorConsecutiveCount = MAX_CONSECUTIVE_ERRORS
                    permissionDeniedFlag = true
                    _hasPermission.value = false
                    _isAvailable.value = false
                    _errorState.value = RecognizerError.PERMISSION_DENIED
                    releaseAudioFocus()

                    if (currentMode == ListeningMode.SINGLE_SHOT) {
                        _recognitionResult.value = RecognitionResult.Error(RecognizerError.PERMISSION_DENIED)
                    }
                }
            }

            SpeechRecognizer.ERROR_CLIENT -> {
                Log.w(TAG, "onError: 客户端错误 ($error)")
                handleError(RecognizerError.CLIENT_ERROR)
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                Log.w(TAG, "onError: 识别器繁忙 ($error)")
                handleError(RecognizerError.BUSY)
            }

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                Log.w(TAG, "onError: 网络错误 ($error)，SpeechRecognizer 无法连接到语音服务")
                // 【ColorOS 兼容】网络错误在 ColorOS 上通常表示无法连接 Google 语音服务
                // 直接标记为不可用，后续使用 RecognizerIntent
                speechRecognizerBroken = true
                Log.w(TAG, "【ColorOS 兼容】网络错误，标记 SpeechRecognizer 为不可用，后续使用 RecognizerIntent")
                handleError(RecognizerError.NETWORK_ERROR)
            }

            SpeechRecognizer.ERROR_SERVER -> {
                Log.w(TAG, "onError: 服务器错误 ($error)，SpeechRecognizer 可能不可用")
                // 【ColorOS 兼容】检测是否快速失败（< 200ms），如果是则标记为不可用
                val timeSinceStart = System.currentTimeMillis() - listeningStartTime
                if (timeSinceStart < 200) {
                    speechRecognizerBroken = true
                    Log.w(TAG, "【ColorOS 兼容】SpeechRecognizer 在 ${timeSinceStart}ms 内返回 error=12，标记为不可用，后续使用 RecognizerIntent")
                }
                handleError(RecognizerError.SERVER_ERROR)
            }

            SpeechRecognizer.ERROR_AUDIO -> {
                Log.e(TAG, "onError: 音频录制错误 ($error)")
                handleError(RecognizerError.AUDIO_ERROR, severity = 2)
            }

            else -> {
                Log.w(TAG, "onError: 未知错误 ($error)")
                // 【ColorOS 兼容】error=12 等未知错误也尝试 fallback
                val timeSinceStart = System.currentTimeMillis() - listeningStartTime
                if (timeSinceStart < 200) {
                    speechRecognizerBroken = true
                    Log.w(TAG, "【ColorOS 兼容】SpeechRecognizer 在 ${timeSinceStart}ms 内返回未知错误，标记为不可用")
                }
                handleError(RecognizerError.UNKNOWN)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  【新增】统一错误处理
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理无匹配/超时情况
     */
    private fun handleNoMatch(reason: String) {
        when (currentMode) {
            ListeningMode.SINGLE_SHOT -> {
                // 【关键】单次模式：发送 NoMatch 结果
                Log.d(TAG, "单次模式无匹配: $reason")
                _recognitionResult.value = RecognitionResult.NoMatch
                isAllowedToListen = false
                releaseAudioFocus()
            }
            ListeningMode.CONTINUOUS -> {
                // 连续模式：自动重试
                errorConsecutiveCount = 0
                scope.launch {
                    delay(BASE_RETRY_DELAY_MS)
                    if (isAllowedToListen) startListening()
                }
            }
        }
    }

    /**
     * 统一错误处理
     */
    private fun handleError(error: RecognizerError, severity: Int = 1) {
        errorConsecutiveCount += severity
        _errorState.value = error

        when (currentMode) {
            ListeningMode.SINGLE_SHOT -> {
                // 【ColorOS 兼容】对于网络错误、服务器错误和未知错误，触发 RecognizerIntent fallback
                // 原因：ColorOS 的 SpeechRecognizer 默认连接 Google 语音服务，在国内网络下无法访问
                if (error == RecognizerError.SERVER_ERROR
                    || error == RecognizerError.UNKNOWN
                    || error == RecognizerError.NETWORK_ERROR) {
                    Log.i(TAG, "【ColorOS 兼容】单次模式遇到 $error，触发 RecognizerIntent fallback")
                    releaseAudioFocus()
                    _requestFallbackRecognition.tryEmit(Unit)
                    // 不发送错误结果，等待 RecognizerIntent 的结果
                    return
                }
                // 其他错误（权限、音频、繁忙等）：发送错误结果
                releaseAudioFocus()
                _recognitionResult.value = RecognitionResult.Error(error)
                isAllowedToListen = false
            }
            ListeningMode.CONTINUOUS -> {
                releaseAudioFocus()
                // 连续模式：尝试恢复
                if (error == RecognizerError.CLIENT_ERROR) {
                    initRecognizer()
                }
                scope.launch {
                    delay(BASE_RETRY_DELAY_MS * 2)
                    if (isAllowedToListen) startListening()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //  【新增】RecognizerIntent Fallback 结果接收
    // ═══════════════════════════════════════════════════════════

    /**
     * 接收 RecognizerIntent 的识别结果
     * UI 层在 ActivityResult 回调中调用此方法
     *
     * @param matches 识别到的文字列表（来自 RecognizerIntent.EXTRA_RESULTS）
     */
    fun onRecognizerIntentResult(matches: ArrayList<String>?) {
        val command = matches?.firstOrNull()
        if (command.isNullOrBlank()) {
            Log.w(TAG, "RecognizerIntent 结果为空")
            _recognitionResult.value = RecognitionResult.NoMatch
        } else {
            Log.i(TAG, "【RecognizerIntent 成功】文字=\"$command\"")
            _recognitionResult.value = RecognitionResult.Success(command)
        }
        isAllowedToListen = false
        releaseAudioFocus()
    }

    /**
     * 用户取消 RecognizerIntent（按返回键等）
     * UI 层在 RESULT_CANCELED 时调用此方法
     */
    fun onRecognizerIntentCancelled() {
        Log.w(TAG, "RecognizerIntent 被用户取消")
        _recognitionResult.value = RecognitionResult.NoMatch
        isAllowedToListen = false
        releaseAudioFocus()
    }

    /**
     * 重置 SpeechRecognizer 损坏标记
     * 在权限变更或手动重置时调用，尝试恢复使用 SpeechRecognizer
     */
    fun resetSpeechRecognizer() {
        speechRecognizerBroken = false
        errorConsecutiveCount = 0
        Log.i(TAG, "已重置 SpeechRecognizer 状态")
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取录音权限设置页面的 Intent
     */
    fun getPermissionSettingsIntent(): Intent {
        return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.requestAudioFocus(it) }
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
    }

    fun destroy() {
        isAllowedToListen = false
        coolDownJob?.cancel()
        scope.cancel()
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy 异常", e)
        }
        speechRecognizer = null
        releaseAudioFocus()
    }
}

// ═══════════════════════════════════════════════════════════
//  【新增】数据类/枚举
// ═══════════════════════════════════════════════════════════

/**
 * 语音识别模式
 */
enum class ListeningMode {
    /** 连续监听：识别完成后自动重启，用于导航指令 */
    CONTINUOUS,
    /** 单次识别：识别完成后停止，用于按住说话输入 */
    SINGLE_SHOT
}

/**
 * 单次识别结果
 */
sealed class RecognitionResult {
    /** 识别成功，包含识别到的文字 */
    data class Success(val text: String) : RecognitionResult()
    /** 未识别到文字（用户未说话或语音不清晰） */
    data object NoMatch : RecognitionResult()
    /** 识别出错 */
    data class Error(val error: RecognizerError) : RecognitionResult()
}

/**
 * 语音识别错误类型（供 UI 层观察）
 */
enum class RecognizerError {
    /** 录音权限被拒绝 */
    PERMISSION_DENIED,
    /** 网络错误 */
    NETWORK_ERROR,
    /** 音频录制错误 */
    AUDIO_ERROR,
    /** 客户端错误 */
    CLIENT_ERROR,
    /** 识别器繁忙 */
    BUSY,
    /** 服务器错误 */
    SERVER_ERROR,
    /** 识别器不可用 */
    NOT_AVAILABLE,
    /** 未知错误 */
    UNKNOWN
}
