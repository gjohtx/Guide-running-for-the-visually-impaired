package com.example.guiderunningfortheblind.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * TTS 引擎辅助工具类
 *
 * 负责：
 * 1. 扫描设备上已安装的 TTS 引擎
 * 2. 检测最佳可用引擎
 * 3. 检查 TTS 语音数据完整性
 * 4. 提供引导用户修复 TTS 问题的辅助方法
 * 5. OPPO/ColorOS 设备特殊适配
 */
object TtsEngineHelper {

    private const val TAG = "TtsEngineHelper"

    /** 已知 TTS 引擎包名列表（按推荐优先级排序） */
    val KNOWN_TTS_ENGINES = listOf(
        "com.google.android.tts" to "Google 文字转语音",
        "com.iflytek.speechsuite" to "科大讯飞语音引擎",
        "com.iflytek.tts" to "科大讯飞 TTS（旧版）",
        "com.baidu.duersdk.opensdk" to "百度语音",
        "com.samsung.SMT" to "三星 TTS",
        "com.huawei.hiai" to "华为语音助手",
        "com.xiaomi.mibrain.speech" to "小爱语音",
        "com.vivo.tts" to "vivo TTS",
        "com.coloros.speech.tts" to "ColorOS TTS",
        "com.heytap.speech.tts" to "HeyTap TTS",
        "com.android.speech.tts" to "Android 系统 TTS"
    )

    /**
     * 获取设备上已安装的所有 TTS 引擎包名
     *
     * 在 Android 11+ 上，需要在 AndroidManifest.xml 中声明 <queries> 才能读取
     */
    fun getInstalledTtsEngines(context: Context): List<String> {
        val pm = context.packageManager
        val installed = mutableListOf<String>()

        // 方法1：通过 PackageManager 查询所有已安装应用（Android 11+ 可能受限）
        for ((pkg, name) in KNOWN_TTS_ENGINES) {
            if (isPackageInstalled(pm, pkg)) {
                Log.d(TAG, "检测到已安装 TTS 引擎: $name ($pkg)")
                installed.add(pkg)
            }
        }

        // 方法2：通过 TextToSpeech.EngineInfo 获取系统报告的引擎
        // 这是更可靠的方法，不受 queries 限制
        try {
            val tts = TextToSpeech(context, null)
            val engines = tts.engines
            for (engine in engines) {
                if (engine.name !in installed) {
                    Log.d(TAG, "系统报告 TTS 引擎: ${engine.label} (${engine.name})")
                    installed.add(engine.name)
                }
            }
            tts.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "通过 TextToSpeech 获取引擎列表失败", e)
        }

        // 方法3：尝试创建默认 TTS 并获取其默认引擎
        if (installed.isEmpty()) {
            try {
                val tts = TextToSpeech(context, null)
                val defaultEngine = tts.defaultEngine
                if (!defaultEngine.isNullOrBlank() && defaultEngine !in installed) {
                    Log.d(TAG, "默认 TTS 引擎: $defaultEngine")
                    installed.add(defaultEngine)
                }
                tts.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "获取默认引擎失败", e)
            }
        }

        // 去重并保持优先级顺序
        return installed.distinct()
    }

    /**
     * 获取推荐的 TTS 引擎（供用户安装）
     */
    fun getRecommendedEngineForInstall(): Pair<String, String> {
        // 优先推荐 Google TTS
        return "com.google.android.tts" to "Google 文字转语音引擎"
    }

    /**
     * 检查指定包名是否已安装
     */
    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 获取已启用 TTS 引擎的用户友好名称
     */
    fun getEngineDisplayName(packageName: String?): String {
        if (packageName == null) return "未知引擎"
        return KNOWN_TTS_ENGINES.find { it.first == packageName }?.second
            ?: packageName.substringAfterLast(".")
    }

    /**
     * 检测系统默认 TTS 引擎是否设置
     */
    fun isDefaultTtsEngineSet(context: Context): Boolean {
        return try {
            val defaultEngine = Settings.Secure.getString(
                context.contentResolver,
                "tts_default_synth"
            )
            !defaultEngine.isNullOrBlank()
        } catch (e: Exception) {
            Log.w(TAG, "无法读取默认 TTS 设置", e)
            true // 保守返回 true，避免过度提示
        }
    }

    /**
     * 获取引导用户打开系统 TTS 设置的 Intent
     */
    fun getOpenTtsSettingsIntent(): Intent {
        return Intent().apply {
            action = "com.android.settings.TTS_SETTINGS"
            // 兜底：如果上述 action 不存在，用通用设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        }
    }

    /**
     * 获取从应用商店安装 Google TTS 的 Intent
     */
    fun getInstallGoogleTtsIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("market://details?id=com.google.android.tts")
        }
    }

    /**
     * 获取打开无障碍设置的 Intent
     * OPPO ColorOS 有时需要在无障碍中授权语音相关权限
     */
    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    /**
     * 判断当前设备是否为 OPPO / ColorOS / Realme / OnePlus
     */
    fun isOppoFamilyDevice(): Boolean {
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

    /**
     * 判断是否为小米/Redmi设备
     */
    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("xiaomi") ||
                brand.contains("xiaomi") ||
                brand.contains("redmi")
    }

    /**
     * 获取设备诊断信息（用于日志上报和问题排查）
     */
    fun getDeviceDiagnostics(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== TTS 设备诊断 ===")
        sb.appendLine("制造商: ${Build.MANUFACTURER}")
        sb.appendLine("品牌: ${Build.BRAND}")
        sb.appendLine("型号: ${Build.MODEL}")
        sb.appendLine("系统版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("指纹: ${Build.FINGERPRINT}")
        sb.appendLine("是否 OPPO 系: ${isOppoFamilyDevice()}")
        sb.appendLine("---")

        val engines = getInstalledTtsEngines(context)
        sb.appendLine("已安装 TTS 引擎数: ${engines.size}")
        engines.forEach { pkg ->
            val name = getEngineDisplayName(pkg)
            sb.appendLine("  - $name ($pkg)")
        }
        sb.appendLine("---")
        sb.appendLine("默认引擎已设置: ${isDefaultTtsEngineSet(context)}")
        sb.appendLine("===================")

        return sb.toString()
    }

    /**
     * 检查语音数据是否可用（需要在 Activity 中启动）
     *
     * 使用方式：
     * ```kotlin
     * val intent = TtsEngineHelper.getCheckTtsDataIntent()
     * activity.startActivityForResult(intent, REQ_CHECK_TTS_DATA)
     * ```
     *
     * 在 onActivityResult 中处理：
     * ```kotlin
     * when (resultCode) {
     *     TextToSpeech.Engine.CHECK_VOICE_DATA_PASS -> // 语音数据可用
     *     TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL -> // 需要下载语音数据
     * }
     * ```
     */
    fun getCheckTtsDataIntent(): Intent {
        return Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
    }

    /**
     * 获取安装语音数据的 Intent（当 CHECK_VOICE_DATA_FAIL 时调用）
     */
    fun getInstallVoiceDataIntent(): Intent {
        return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
    }

    /**
     * 【修复】检测 Google TTS 是否安装了中文离线语音包
     *
     * 返回：true = 已安装离线数据，可在无网络时使用
     *       false = 需要下载语音数据
     */
    fun checkGoogleTtsChineseData(context: Context): Boolean {
        return try {
            val tts = TextToSpeech(context, null, "com.google.android.tts")
            val result = tts.setLanguage(Locale.CHINESE)
            val hasData = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
            tts.shutdown()
            Log.i(TAG, "Google TTS 中文数据检查: ${if (hasData) "已安装" else "缺失"}")
            hasData
        } catch (e: Exception) {
            Log.w(TAG, "无法检查 Google TTS 数据", e)
            false
        }
    }

    /**
     * 【修复】检测网络是否可用
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * 【修复】获取推荐的引擎安装方案（根据设备和网络情况）
     *
     * 返回：推荐安装步骤的文本描述
     */
    fun getRecommendedFixSteps(context: Context): List<String> {
        val steps = mutableListOf<String>()
        val hasNetwork = isNetworkAvailable(context)
        val isOppo = isOppoFamilyDevice()

        steps.add("安装 Google 文字转语音引擎（应用商店搜索下载）")
        if (isOppo) {
            steps.add("【OPPO必做】设置 → 其他设置 → 无障碍 → 开启语音引擎权限")
        }
        steps.add("设置 → 系统 → 文字转语音(TTS)输出 → 首选引擎选择 Google 文字转语音")
        steps.add("在 TTS 设置中点击下载中文（中国）语音数据")
        if (!hasNetwork) {
            steps.add("【当前无网络】请连接 WiFi 或移动数据后重试")
        }

        return steps
    }
}
