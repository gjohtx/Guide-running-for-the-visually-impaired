package com.example.guiderunningfortheblind.speech

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 语音功能 ViewModel - 管理语音播报和按住说话输入
 *
 * 【修复摘要】
 * 1. 新增按住说话（Push-to-Talk）功能完整支持
 * 2. 新增 inputText 状态，绑定到输入框
 * 3. 识别成功后自动将文字填入输入框并语音播报
 * 4. 未识别到文字时语音播报"未识别到文字"反馈
 * 5. 管理整个语音输入生命周期状态
 */
@HiltViewModel
class SpeechViewModel @Inject constructor(
    private val voiceQueueManager: VoiceQueueManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "SpeechViewModel"
        private const val NO_MATCH_FEEDBACK = "未识别到文字"
    }

    // ═══════════════════════════════════════════════════════════
    //  原有 API（TTS 播报 - 保持兼容）
    // ═══════════════════════════════════════════════════════════

    val isSpeaking: StateFlow<Boolean> = voiceQueueManager.isSpeaking
    val speechRate: StateFlow<Float> = voiceQueueManager.speechRate
    val speechVolume: StateFlow<Float> = voiceQueueManager.speechVolume

    fun queueInstruction(text: String) {
        viewModelScope.launch {
            voiceQueueManager.speak(text)
        }
    }

    fun speakImmediate(text: String) {
        voiceQueueManager.speakImmediate(text)
    }

    fun setSpeechRate(rate: Float) {
        voiceQueueManager.setSpeechRate(rate)
    }

    fun setSpeechVolume(volume: Float) {
        voiceQueueManager.setSpeechVolume(volume)
    }

    fun stopSpeaking() {
        voiceQueueManager.stop()
    }

    // ═══════════════════════════════════════════════════════════
    //  TTS 状态暴露（保持兼容）
    // ═══════════════════════════════════════════════════════════

    val ttsInitState: StateFlow<TtsInitState> = voiceQueueManager.initState
    val engineName: StateFlow<String?> = voiceQueueManager.engineName
    val needInstallTts: StateFlow<Boolean> = voiceQueueManager.needInstallTts
    val needDownloadData: StateFlow<Boolean> = voiceQueueManager.needDownloadData
    val lastError: StateFlow<String?> = voiceQueueManager.lastError
    val isTtsReady: Boolean get() = voiceQueueManager.isReady()

    fun getTtsSetupIntent(fromError: Boolean = true): Intent {
        return TtsSetupGuideActivity.createIntent(context, fromError)
    }

    fun retryTtsInit() {
        voiceQueueManager.retryInit()
    }

    fun getDiagnostics(): String {
        return TtsEngineHelper.getDeviceDiagnostics(context)
    }

    fun shouldShowTtsSetup(): Boolean {
        return when (ttsInitState.value) {
            TtsInitState.NO_ENGINE,
            TtsInitState.ALL_ENGINES_FAILED -> true
            else -> false
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  【新增】按住说话（Push-to-Talk）功能
    // ═══════════════════════════════════════════════════════════

    /**
     * 输入框文字状态
     * UI 层通过 TextField(value = inputText.value, ...) 绑定
     */
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /** 是否正在监听（按住说话中，用于 UI 显示动画/提示） */
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** 语音识别结果状态 */
    private val _lastRecognitionResult = MutableStateFlow<RecognitionResult?>(null)
    val lastRecognitionResult: StateFlow<RecognitionResult?> = _lastRecognitionResult.asStateFlow()

    /** 是否启用语音输入功能 */
    val isVoiceInputEnabled: Boolean
        get() = voiceQueueManager.isReady()

    // VoiceCoordinator 引用（由外部注入或获取）
    private var voiceCoordinator: VoiceCoordinator? = null

    /**
     * 设置 VoiceCoordinator 引用
     * 在 UI 层初始化时调用，例如：
     * ```kotlin
     * val voiceCoordinator = (application as MainApplication).voiceCoordinator
     * speechViewModel.setVoiceCoordinator(voiceCoordinator)
     * ```
     */
    fun setVoiceCoordinator(coordinator: VoiceCoordinator) {
        if (voiceCoordinator != null) return // 避免重复设置
        voiceCoordinator = coordinator

        // 收集语音识别结果
        viewModelScope.launch {
            coordinator.voiceInputResult.collect { result ->
                result?.let {
                    handleRecognitionResult(it)
                }
            }
        }

        // 收集监听状态
        viewModelScope.launch {
            coordinator.isVoiceInputActive.collect { active ->
                _isListening.value = active
            }
        }

        Log.i(TAG, "VoiceCoordinator 已设置")
    }

    // ═══════════════════════════════════════════════════════════
    //  【新增】公开 API：按住说话控制
    // ═══════════════════════════════════════════════════════════

    /**
     * 开始语音输入（用户按下语音按钮时调用）
     *
     * @return true 表示成功启动
     */
    fun startVoiceInput(): Boolean {
        val coordinator = voiceCoordinator
        if (coordinator == null) {
            Log.e(TAG, "startVoiceInput: VoiceCoordinator 未设置")
            return false
        }

        // 检查 TTS 是否就绪
        if (!voiceQueueManager.isReady()) {
            Log.w(TAG, "startVoiceInput: TTS 未就绪")
            return false
        }

        // 清除之前的结果
        _lastRecognitionResult.value = null

        val started = coordinator.startVoiceInput()
        Log.i(TAG, "startVoiceInput: 启动${if (started) "成功" else "失败"}")
        return started
    }

    /**
     * 停止语音输入（用户松开语音按钮时调用）
     *
     * 【重要】识别结果不会立即返回，而是通过 [lastRecognitionResult] 异步发送。
     * 原因：SpeechRecognizer 需要一定时间处理音频。
     *
     * UI 层推荐的使用方式：
     * ```kotlin
     * Button(
     *     onClick = {}, // 空，使用 pointerInput 处理按下/松开
     *     modifier = Modifier.pointerInput(Unit) {
     *         detectTapGestures(
     *             onPress = {
     *                 viewModel.startVoiceInput()
     *                 tryAwaitRelease() // 等待用户松开
     *                 viewModel.stopVoiceInput()
     *             }
     *         )
     *     }
     * )
     * ```
     */
    fun stopVoiceInput() {
        Log.d(TAG, "stopVoiceInput: 用户松开按钮")
        voiceCoordinator?.stopVoiceInput()
        // 结果将通过 voiceInputResult Flow 异步到达
    }

    /**
     * 取消语音输入
     */
    fun cancelVoiceInput() {
        Log.d(TAG, "cancelVoiceInput")
        voiceCoordinator?.cancelVoiceInput()
        _isListening.value = false
    }

    // ═══════════════════════════════════════════════════════════
    //  【新增】输入框文字操作
    // ═══════════════════════════════════════════════════════════

    /**
     * 更新输入框文字（由 TextField onValueChange 调用）
     */
    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /**
     * 清空输入框
     */
    fun clearInput() {
        _inputText.value = ""
    }

    /**
     * 发送输入框文字（朗读或其他处理）
     */
    fun sendInput() {
        val text = _inputText.value.trim()
        if (text.isNotEmpty()) {
            voiceQueueManager.speak(text)
        }
    }

    /** 获取打开权限设置的 Intent */
    fun getPermissionSettingsIntent(): Intent? {
        return voiceCoordinator?.getPermissionSettingsIntent()
    }

    // ═══════════════════════════════════════════════════════════
    //  内部：识别结果处理
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理语音识别结果
     *
     * 【重要】此方法只负责：
     * 1. 设置 _lastRecognitionResult 通知 UI 层
     * 2. TTS 播报通用反馈（未识别/错误等）
     *
     * 不负责：
     * - 填入输入框（各页面自行决定如何使用识别文字）
     * - 发送给 AI（各页面自行决定）
     *
     * 原因：主页和跑步页面对识别结果的使用方式不同：
     * - 主页：识别命令 → 跳转页面，不填输入框
     * - 跑步页：识别文字 → 填入输入框 → 发送给 AI
     */
    private fun handleRecognitionResult(result: RecognitionResult) {
        Log.i(TAG, "处理识别结果: $result")
        _lastRecognitionResult.value = result

        when (result) {
            is RecognitionResult.Success -> {
                // 【修复】不再自动填入输入框，由各页面自行处理
                val recognizedText = result.text
                Log.i(TAG, "识别成功，文字=\"$recognizedText\"")
                // 不播报"识别到：xxx"，由各页面自行播报适合的反馈
            }
            is RecognitionResult.NoMatch -> {
                // 未识别到文字：语音播报反馈
                Log.w(TAG, "未识别到文字")
                voiceQueueManager.speak(NO_MATCH_FEEDBACK)
            }
            is RecognitionResult.Error -> {
                // 识别出错：根据错误类型处理
                val errorMsg = when (result.error) {
                    RecognizerError.PERMISSION_DENIED -> "请授予录音权限"
                    RecognizerError.NETWORK_ERROR -> "网络错误，请检查网络连接"
                    RecognizerError.AUDIO_ERROR -> "音频录制失败"
                    RecognizerError.NOT_AVAILABLE -> "语音识别不可用"
                    else -> "语音识别失败"
                }
                Log.e(TAG, "识别错误: ${result.error}, $errorMsg")
                voiceQueueManager.speak(errorMsg)
            }
        }
    }

    /**
     * 清除识别结果
     *
     * 页面导航前调用，避免返回时 LaunchedEffect 重复触发。
     * 同时清除 VoiceCommandManager 中的结果，确保下次识别从零开始。
     */
    fun clearRecognitionResult() {
        Log.d(TAG, "清除识别结果")
        _lastRecognitionResult.value = null
        voiceCoordinator?.let {
            it.voiceCommandManager.clearRecognitionResult()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // VoiceQueueManager 是 Application 级单例，不在此处 shutdown
    }
}
