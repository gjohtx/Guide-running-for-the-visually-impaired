package com.example.guiderunningfortheblind.speech

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音协调器 - 协调语音指令识别与语音播报之间的互斥关系
 *
 * 【修复摘要】
 * 1. 新增按住说话（单次识别）API：[startVoiceInput] / [stopVoiceInput]
 * 2. 按住说话时自动暂停 TTS 播报，避免自说自听
 * 3. 单次识别结果通过 [voiceInputResult] Flow 暴露给 UI 层
 * 4. 连续监听模式（导航指令）改为 opt-in，不在 onResume 自动启动
 * 5. 修复生命周期管理，避免页面切换时错误停止单次识别
 *
 * 【互斥规则】
 * - TTS 开始播报 → 暂停连续语音识别（pauseForTts）
 * - TTS 播报结束 → 延迟 600ms 恢复连续识别（resumeAfterTts）
 * - 按住说话开始 → 暂停 TTS 播报
 * - 按住说话结束 → 恢复 TTS 播报
 * - 页面 onPause → 停止一切语音活动
 * - 页面 onResume → 仅刷新权限状态，不自动启动识别
 */
@Singleton
class VoiceCoordinator @Inject constructor(
    val voiceCommandManager: VoiceCommandManager,
    private val voiceQueueManager: VoiceQueueManager
) : DefaultLifecycleObserver {

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /** 录音权限是否已授予 */
    val hasAudioPermission: StateFlow<Boolean> = voiceCommandManager.hasPermission

    /** 语音识别错误状态 */
    val recognizerError: StateFlow<RecognizerError?> = voiceCommandManager.errorState

    /** 【新增】单次识别结果（透传自 VoiceCommandManager） */
    val voiceInputResult: StateFlow<RecognitionResult?> = voiceCommandManager.recognitionResult

    /** 【新增】是否正在进行语音输入（按住说话中） */
    private val _isVoiceInputActive = MutableStateFlow(false)
    val isVoiceInputActive: StateFlow<Boolean> = _isVoiceInputActive.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var resumeJob: Job? = null
    private var continuousListeningEnabled = false

    companion object {
        private const val TAG = "VoiceCoordinator"
    }

    init {
        // 监听 TTS 播报状态
        scope.launch {
            voiceQueueManager.isSpeaking.collect { speaking ->
                if (speaking) {
                    onTtsStart()
                } else {
                    onTtsEnd()
                }
            }
        }

        // 【新增】监听识别结果，用于在单次识别后恢复 TTS
        scope.launch {
            voiceCommandManager.recognitionResult.collect { result ->
                if (result != null && _isVoiceInputActive.value) {
                    // 单次识别完成，恢复 TTS
                    _isVoiceInputActive.value = false
                    Log.d(TAG, "单次识别完成，恢复 TTS 播报")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  生命周期管理
    // ═══════════════════════════════════════════════════════════

    fun observe(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        val activity = owner as? android.app.Activity ?: return
        refreshPermission(activity)

        // 【修复】不再自动启动连续监听
        // 连续监听由调用方（如导航页面）通过 startContinuousListening() 显式启动
        if (continuousListeningEnabled && hasAudioPermission.value) {
            voiceCommandManager.start()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        // 【修复】onPause 时根据当前模式决定如何停止
        if (_isVoiceInputActive.value) {
            // 如果正在进行单次识别（按住说话），取消它
            voiceCommandManager.cancelSingleShotListening()
            _isVoiceInputActive.value = false
        }
        resumeJob?.cancel()
        voiceCommandManager.stop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        resumeJob?.cancel()
        voiceCommandManager.stop()
        super.onDestroy(owner)
    }

    // ═══════════════════════════════════════════════════════════
    //  【新增】按住说话 API（单次识别）
    // ═══════════════════════════════════════════════════════════

    /**
     * 开始语音输入（按住说话）
     *
     * 调用时机：用户按下语音按钮时
     * 行为：
     * 1. 停止当前 TTS 播报（避免自说自听）
     * 2. 启动单次语音识别
     * 3. 标记语音输入状态
     *
     * @return true 表示成功启动
     */
    fun startVoiceInput(): Boolean {
        Log.d(TAG, "startVoiceInput: 开始语音输入")

        // 检查权限
        if (!hasAudioPermission.value) {
            Log.w(TAG, "startVoiceInput: 权限未授予")
            return false
        }

        // 标记状态
        _isVoiceInputActive.value = true

        // 停止 TTS 播报（避免麦克风录到 TTS 声音）
        voiceQueueManager.stop()
        voiceCommandManager.pauseForTts()

        // 启动单次识别
        val started = voiceCommandManager.startSingleShotListening()
        if (!started) {
            _isVoiceInputActive.value = false
            Log.w(TAG, "startVoiceInput: 启动识别失败")
            return false
        }

        Log.i(TAG, "startVoiceInput: 语音输入已启动")
        return true
    }

    /**
     * 停止语音输入（用户松开按钮时调用）
     *
     * 调用时机：用户松开语音按钮时
     * 行为：
     * 1. 停止语音识别
     * 2. 恢复 TTS 播报能力
     *
     * 【重要】识别结果不会立即返回，而是通过 [voiceInputResult] Flow 异步发送。
     * 原因：SpeechRecognizer 需要一定时间处理音频。
     * UI 层应在松开按钮后继续观察 [voiceInputResult] 获取结果。
     */
    fun stopVoiceInput() {
        Log.d(TAG, "stopVoiceInput: 停止语音输入")

        if (!_isVoiceInputActive.value) {
            Log.d(TAG, "stopVoiceInput: 语音输入未激活，忽略")
            return
        }

        // 停止识别（这会触发 onResults/onError 回调）
        voiceCommandManager.stopSingleShotListening()

        // 注意：_isVoiceInputActive 会在识别结果回调中自动设为 false
        // 这里不立即设为 false，以等待结果
    }

    /**
     * 取消语音输入（用户取消操作）
     */
    fun cancelVoiceInput() {
        Log.d(TAG, "cancelVoiceInput: 取消语音输入")
        _isVoiceInputActive.value = false
        voiceCommandManager.cancelSingleShotListening()
        // 恢复连续监听（如果已启用）
        if (continuousListeningEnabled) {
            voiceCommandManager.resumeAfterTts()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  连续监听模式 API（导航指令）
    // ═══════════════════════════════════════════════════════════

    /**
     * 启动连续监听模式（用于导航语音指令）
     */
    fun startContinuousListening() {
        continuousListeningEnabled = true
        voiceCommandManager.start()
    }

    /**
     * 停止连续监听模式
     */
    fun stopContinuousListening() {
        continuousListeningEnabled = false
        voiceCommandManager.stop()
    }

    /**
     * 是否启用连续监听
     */
    fun isContinuousListeningEnabled(): Boolean = continuousListeningEnabled

    // ═══════════════════════════════════════════════════════════
    //  权限处理
    // ═══════════════════════════════════════════════════════════

    fun onAudioPermissionResult(granted: Boolean) {
        voiceCommandManager.onPermissionResult(granted)
        if (granted && continuousListeningEnabled) {
            voiceCommandManager.start()
        }
    }

    fun getPermissionSettingsIntent() = voiceCommandManager.getPermissionSettingsIntent()

    private fun refreshPermission(activity: android.app.Activity) {
        val hasPermission = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        voiceCommandManager.onPermissionResult(hasPermission)
    }

    // ═══════════════════════════════════════════════════════════
    //  TTS 互斥处理
    // ═══════════════════════════════════════════════════════════

    private fun onTtsStart() {
        // 如果正在进行单次识别，不暂停（单次识别已经自己停止了 TTS）
        if (_isVoiceInputActive.value) {
            Log.d(TAG, "TTS 开始但单次识别激活中，不暂停识别")
            return
        }

        resumeJob?.cancel()
        _isSpeaking.value = true
        voiceCommandManager.pauseForTts()
    }

    private fun onTtsEnd() {
        _isSpeaking.value = false

        // 如果正在进行单次识别，不恢复连续监听
        if (_isVoiceInputActive.value) {
            Log.d(TAG, "TTS 结束但单次识别激活中，不恢复连续监听")
            return
        }

        resumeJob = scope.launch {
            delay(600)
            if (!_isSpeaking.value && !_isVoiceInputActive.value) {
                if (continuousListeningEnabled) {
                    voiceCommandManager.resumeAfterTts()
                }
            }
        }
    }
}
