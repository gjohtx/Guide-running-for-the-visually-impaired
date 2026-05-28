package com.example.guiderunningfortheblind.speech

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 语音队列管理器 - 视障跑步导航核心组件
 *
 * 【修复摘要】针对 OPPO ColorOS / 国产 ROM TTS 初始化失败问题：
 * 1. 多引擎 fallback 策略：Google TTS → 科大讯飞 → 系统默认 → 已安装引擎列表
 * 2. 支持通过 ACTION_CHECK_TTS_DATA 检测和引导安装语音数据
 * 3. 增强重试：每次重试切换不同引擎，而非重复同一引擎
 * 4. OPPO ColorOS 特殊适配：延迟初始化、引擎白名单检测
 * 5. 暴露详细状态流，供 UI 层引导用户修复 TTS 问题
 */
class VoiceQueueManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceQueueManager"

        /** 最大重试次数 */
        private const val MAX_RETRY = 5

        /** 重试延迟（毫秒）— OPPO 设备首次延迟更长 */
        private const val RETRY_DELAY_MS = 2000L
        private const val RETRY_DELAY_OPPO_MS = 3500L

        /**
         * TTS 引擎优先级排序 — 关键规则：
         * 1. 离线能力强的引擎优先（视障用户在户外可能无网络）
         * 2. 稳定性高的引擎优先（Google TTS 兼容性最好）
         * 3. 需要联网的国产引擎（讯飞/百度）在无网络时自动降级
         */
        private val PREFERRED_ENGINES = listOf(
            "com.google.android.tts",           // Google TTS — 离线能力强，兼容性最好 ★首选
            "com.samsung.SMT",                  // 三星 TTS — 离线能力好
            "com.huawei.hiai",                  // 华为 TTS — 华为设备适配好
            "com.coloros.speech.tts",           // ColorOS TTS — OPPO 设备原生
            "com.heytap.speech.tts",            // HeyTap TTS — OPPO/Realme
            "com.xiaomi.mibrain.speech",        // 小爱语音 — 小米设备
            "com.vivo.tts",                     // vivo TTS — vivo 设备
            "com.iflytek.speechsuite",          // 科大讯飞 — 需要联网，质量高
            "com.iflytek.tts",                  // 科大讯飞（旧版）
            "com.baidu.duersdk.opensdk"         // 百度语音 — 需要联网
        )

        /** 必须联网才能使用的引擎（无网络时跳过） */
        private val NETWORK_REQUIRED_ENGINES = setOf(
            "com.iflytek.speechsuite",
            "com.iflytek.tts",
            "com.baidu.duersdk.opensdk"
        )

        /** speak 连续失败阈值：超过则尝试切换引擎 */
        private const val MAX_SPEAK_FAIL_COUNT = 3

        /** TTS 检查请求码 */
        const val REQ_CHECK_TTS_DATA = 9001
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 当前重试计数器 */
    private val retryCounter = AtomicInteger(0)

    /** 已尝试的引擎索引 */
    private var engineAttemptIndex = 0

    /** 检测到的可用引擎列表 */
    private var availableEngines: List<String> = emptyList()

    /** 【修复】连续播报失败计数器 */
    private var consecutiveSpeakFailures = 0

    /** 【修复】正在使用的引擎索引 */
    private var currentEngineIndex = 0

    private val speakQueue = ConcurrentLinkedQueue<String>()

    // ═══════════════════════════════════════════════════════════
    //  状态流（供 UI 层观察）
    // ═══════════════════════════════════════════════════════════

    /** 是否正在播报 */
    val isSpeaking = MutableStateFlow(false)

    /** TTS 初始化状态 */
    private val _initState = MutableStateFlow(TtsInitState.IDLE)
    val initState: StateFlow<TtsInitState> = _initState.asStateFlow()

    /** TTS 引擎名称 */
    private val _engineName = MutableStateFlow<String?>(null)
    val engineName: StateFlow<String?> = _engineName.asStateFlow()

    /** 是否需要安装 TTS */
    private val _needInstallTts = MutableStateFlow(false)
    val needInstallTts: StateFlow<Boolean> = _needInstallTts.asStateFlow()

    /** 是否需要下载语音数据 */
    private val _needDownloadData = MutableStateFlow(false)
    val needDownloadData: StateFlow<Boolean> = _needDownloadData.asStateFlow()

    /** 最后一次错误信息 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 语速 */
    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    /** 音量 */
    private val _speechVolume = MutableStateFlow(1.0f)
    val speechVolume: StateFlow<Float> = _speechVolume.asStateFlow()

    // ═══════════════════════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════════════════════

    init {
        // 延迟初始化：给系统足够时间完成开机广播，OPPO 尤其需要
        val delayMs = if (isOppoDevice()) RETRY_DELAY_OPPO_MS else 500L
        Handler(Looper.getMainLooper()).postDelayed({
            discoverEnginesAndInit()
        }, delayMs)
    }

    /**
     * 发现并排序可用引擎，然后开始初始化
     *
     * 【修复】无网络时自动跳过需要联网的引擎（科大讯飞/百度），
     * 优先使用支持离线合成的引擎（Google TTS / 三星 TTS）
     */
    private fun discoverEnginesAndInit() {
        val allEngines = TtsEngineHelper.getInstalledTtsEngines(context)
        val hasNetwork = isNetworkAvailable()
        Log.i(TAG, "发现 ${allEngines.size} 个 TTS 引擎, 网络可用=$hasNetwork: $allEngines")

        // 过滤：无网络时跳过需要联网的引擎
        availableEngines = if (hasNetwork) {
            allEngines
        } else {
            val filtered = allEngines.filter { it !in NETWORK_REQUIRED_ENGINES }
            Log.w(TAG, "无网络连接，已过滤联网引擎，剩余: $filtered")
            filtered
        }

        if (availableEngines.isEmpty()) {
            if (allEngines.isNotEmpty() && !hasNetwork) {
                // 有引擎但都需要联网且当前无网络
                _initState.value = TtsInitState.NO_ENGINE
                _lastError.value = "当前无网络连接，已安装的语音引擎需要联网使用。请连接网络或安装 Google 文字转语音引擎以支持离线播报"
                Log.e(TAG, "【网络问题】有 ${allEngines.size} 个引擎但都需要联网，当前无网络")
            } else {
                _initState.value = TtsInitState.NO_ENGINE
                _needInstallTts.value = true
                _lastError.value = "设备上未安装任何 TTS 引擎"
                Log.e(TAG, "【致命错误】设备上没有安装任何 TTS 引擎")
            }
            return
        }

        // 按偏好顺序排列：先排 preferred 列表中存在的，再排其余的
        val ordered = mutableListOf<String>()
        PREFERRED_ENGINES.forEach { pkg ->
            if (pkg in availableEngines && pkg !in ordered) ordered.add(pkg)
        }
        availableEngines.forEach { pkg ->
            if (pkg !in ordered) ordered.add(pkg)
        }
        availableEngines = ordered

        engineAttemptIndex = 0
        currentEngineIndex = 0
        consecutiveSpeakFailures = 0
        retryCounter.set(0)
        initTtsWithEngine()
    }

    /**
     * 使用指定引擎初始化 TTS
     */
    private fun initTtsWithEngine() {
        // 清理旧实例
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false

        val enginePkg = if (engineAttemptIndex < availableEngines.size) {
            availableEngines[engineAttemptIndex]
        } else null

        if (enginePkg == null) {
            // 所有引擎都尝试过了
            _initState.value = TtsInitState.ALL_ENGINES_FAILED
            _needInstallTts.value = true
            _lastError.value = "所有 TTS 引擎均初始化失败，请安装 Google 文字转语音引擎"
            Log.e(TAG, "【致命错误】所有 ${availableEngines.size} 个 TTS 引擎均初始化失败")
            return
        }

        _initState.value = TtsInitState.INITIALIZING
        Log.i(TAG, "正在初始化 TTS 引擎 [$engineAttemptIndex/${availableEngines.size}]: $enginePkg")

        try {
            tts = TextToSpeech(context, { status ->
                onTtsInitResult(status, enginePkg)
            }, enginePkg)
        } catch (e: Exception) {
            Log.e(TAG, "创建 TTS 实例异常 (引擎=$enginePkg)", e)
            onTtsInitResult(TextToSpeech.ERROR, enginePkg)
        }
    }

    /**
     * TTS 初始化回调处理
     */
    private fun onTtsInitResult(status: Int, enginePkg: String) {
        when (status) {
            TextToSpeech.SUCCESS -> {
                retryCounter.set(0)
                _needInstallTts.value = false

                // 设置语言：优先中文，fallback 英文
                val langResult = tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.ERROR
                if (langResult == TextToSpeech.LANG_MISSING_DATA
                    || langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "中文语音数据缺失或不支持，尝试英文")
                    val enResult = tts?.setLanguage(Locale.US) ?: TextToSpeech.ERROR
                    if (enResult == TextToSpeech.LANG_MISSING_DATA) {
                        _needDownloadData.value = true
                        _lastError.value = "需要下载 TTS 语音数据"
                        Log.w(TAG, "【需要下载语音数据】英文数据也缺失")
                        // 继续尝试播报（部分引擎可在无数据时联网合成）
                    }
                }

                // 设置语速
                tts?.setSpeechRate(_speechRate.value)

                // 设置进度监听器
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking.value = false
                        processQueue()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "单条播报错误: $utteranceId")
                        isSpeaking.value = false
                        processQueue()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "单条播报错误(码=$errorCode): $utteranceId")
                        isSpeaking.value = false
                        processQueue()
                    }
                })

                _engineName.value = enginePkg
                _initState.value = TtsInitState.READY
                ttsReady = true
                currentEngineIndex = engineAttemptIndex
                consecutiveSpeakFailures = 0

                Log.i(TAG, "【OPPO 适配】TTS 初始化成功: engine=$enginePkg, " +
                        "locale=${tts?.voice?.locale}, language=${tts?.language}")

                processQueue()
            }

            else -> {
                ttsReady = false
                val retry = retryCounter.incrementAndGet()
                Log.e(TAG, "TTS 初始化失败, 引擎=$enginePkg, 状态码=$status, 重试=$retry/$MAX_RETRY")

                if (retry < MAX_RETRY) {
                    // 策略：先换下一个引擎，如果所有引擎都试过再回到第一个
                    engineAttemptIndex++
                    if (engineAttemptIndex >= availableEngines.size) {
                        engineAttemptIndex = 0
                    }

                    val delayMs = if (isOppoDevice()) RETRY_DELAY_OPPO_MS else RETRY_DELAY_MS
                    Handler(Looper.getMainLooper()).postDelayed({
                        initTtsWithEngine()
                    }, delayMs)
                } else {
                    _initState.value = TtsInitState.ALL_ENGINES_FAILED
                    _needInstallTts.value = true
                    _lastError.value = "TTS 初始化失败，请检查系统设置中的文字转语音选项"
                    Log.e(TAG, "【致命错误】TTS 经过 $MAX_RETRY 次重试后仍初始化失败")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  公开 API（兼容旧调用方）
    // ═══════════════════════════════════════════════════════════

    /**
     * 重新尝试初始化 TTS（供外部在修复问题后调用）
     */
    fun retryInit() {
        Log.i(TAG, "外部触发重新初始化")
        retryCounter.set(0)
        engineAttemptIndex = 0
        _needInstallTts.value = false
        _needDownloadData.value = false
        _lastError.value = null
        discoverEnginesAndInit()
    }

    /**
     * 获取引导用户安装 TTS 的 Intent
     */
    fun getInstallTtsIntent(): Intent {
        // 优先跳转到 Google TTS 商店页面
        return Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("market://details?id=com.google.android.tts")
            `package` = "com.android.vending"
        }
    }

    /**
     * 获取检查 TTS 数据的 Intent
     */
    fun getCheckTtsDataIntent(): Intent {
        return Intent().apply {
            action = TextToSpeech.Engine.ACTION_CHECK_TTS_DATA
        }
    }

    fun speak(text: String, priority: Int = 0) {
        if (text.isBlank()) return
        speakQueue.offer(text)
        if (ttsReady && !isSpeaking.value) {
            processQueue()
        } else if (!ttsReady && _initState.value != TtsInitState.INITIALIZING) {
            // 如果不在初始化中且未就绪，尝试重新初始化
            Log.w(TAG, "TTS 未就绪，尝试重新初始化...")
            retryInit()
        }
    }

    fun speakImmediate(text: String) {
        speakQueue.clear()
        tts?.stop()
        isSpeaking.value = false
        speak(text)
    }

    /** 停止并清空队列（兼容 NavigationAnnouncer 的调用） */
    fun stopAndClear() {
        speakQueue.clear()
        tts?.stop()
        isSpeaking.value = false
    }

    fun stop() {
        speakQueue.clear()
        tts?.stop()
        isSpeaking.value = false
    }

    /** TTS 是否可用（兼容 NavigationAnnouncer 的调用） */
    fun isAvailable(): Boolean = ttsReady

    fun isReady(): Boolean = ttsReady

    fun setSpeechRate(rate: Float) {
        _speechRate.value = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(_speechRate.value)
    }

    fun setSpeechVolume(volume: Float) {
        _speechVolume.value = volume.coerceIn(0f, 1f)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        _initState.value = TtsInitState.SHUTDOWN
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    private fun processQueue() {
        if (!ttsReady || isSpeaking.value) return
        val text = speakQueue.poll() ?: return
        isSpeaking.value = true

        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, _speechVolume.value)
        }
        val utteranceId = "nav_tts_${System.currentTimeMillis()}"

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            val map = java.util.HashMap<String, String>().apply {
                put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            @Suppress("DEPRECATION")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, map)
        }

        if (result == TextToSpeech.ERROR) {
            consecutiveSpeakFailures++
            Log.w(TAG, "speak() 返回 ERROR (连续 $consecutiveSpeakFailures/$MAX_SPEAK_FAIL_COUNT): $text")
            isSpeaking.value = false
            _needDownloadData.value = true

            // 【修复】连续失败超过阈值，尝试切换到下一个引擎
            if (consecutiveSpeakFailures >= MAX_SPEAK_FAIL_COUNT
                && availableEngines.size > 1
            ) {
                Log.w(TAG, "连续 $MAX_SPEAK_FAIL_COUNT 次播报失败，尝试切换引擎...")
                switchToNextEngine()
                return  // 引擎切换后会自动 processQueue
            }

            processQueue()
        } else {
            consecutiveSpeakFailures = 0
        }
    }

    /**
     * 【修复】切换到下一个可用引擎
     * 当当前引擎连续播报失败时调用
     */
    private fun switchToNextEngine() {
        // 尝试列表中的下一个引擎
        currentEngineIndex++
        if (currentEngineIndex >= availableEngines.size) {
            currentEngineIndex = 0
        }
        // 避免回到同一个引擎（如果只有一个引擎）
        if (availableEngines.size > 1
            && currentEngineIndex == engineAttemptIndex
        ) {
            currentEngineIndex++
            if (currentEngineIndex >= availableEngines.size) {
                currentEngineIndex = 0
            }
        }
        engineAttemptIndex = currentEngineIndex
        consecutiveSpeakFailures = 0
        // 延迟后重新初始化
        Handler(Looper.getMainLooper()).postDelayed({
            initTtsWithEngine()
        }, 500)
    }

    /**
     * 【修复】检测网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * 检测是否为 OPPO / ColorOS 设备
     */
    private fun isOppoDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        return manufacturer.contains("oppo") ||
                brand.contains("oppo") ||
                brand.contains("realme") ||
                brand.contains("oneplus") ||
                fingerprint.contains("coloros") ||
                fingerprint.contains("oppo")
    }
}

/**
 * TTS 初始化状态枚举
 */
enum class TtsInitState {
    /** 初始/空闲 */
    IDLE,
    /** 正在初始化 */
    INITIALIZING,
    /** 已就绪 */
    READY,
    /** 设备上没有 TTS 引擎 */
    NO_ENGINE,
    /** 所有引擎均初始化失败 */
    ALL_ENGINES_FAILED,
    /** 已关闭 */
    SHUTDOWN
}
