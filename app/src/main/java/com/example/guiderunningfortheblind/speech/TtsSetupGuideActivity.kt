package com.example.guiderunningfortheblind.speech

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guiderunningfortheblind.ui.theme.GuideRunningFortheBlindTheme
import kotlinx.coroutines.delay

/**
 * TTS 设置引导 Activity
 *
 * 【目的】当 TTS 初始化失败时，以无障碍友好的方式引导用户完成修复。
 * 针对视障用户优化：
 * - 所有文本带语义标签，可被 TalkBack 朗读
 * - 操作按钮超大且带清晰语音描述
 * - 步骤顺序清晰，每步完成后语音播报确认
 * - OPPO ColorOS 提供专项修复指引
 */
class TtsSetupGuideActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TtsSetupGuide"
        const val EXTRA_FROM_ERROR = "from_error"
        const val EXTRA_DEVICE_BRAND = "device_brand"
        const val RESULT_TTS_FIXED = 9002

        fun createIntent(context: android.content.Context, fromError: Boolean = true): Intent {
            return Intent(context, TtsSetupGuideActivity::class.java).apply {
                putExtra(EXTRA_FROM_ERROR, fromError)
                putExtra(EXTRA_DEVICE_BRAND, Build.BRAND)
            }
        }
    }

    private var voiceQueueManager: VoiceQueueManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fromError = intent.getBooleanExtra(EXTRA_FROM_ERROR, true)
        Log.i(TAG, "打开 TTS 设置引导页, fromError=$fromError")

        setContent {
            GuideRunningFortheBlindTheme {
                TtsSetupGuideScreen(
                    fromError = fromError,
                    onInstallGoogleTts = { openInstallGoogleTts() },
                    onOpenTtsSettings = { openTtsSettings() },
                    onCheckVoiceData = { checkVoiceData() },
                    onOpenAccessibility = { openAccessibilitySettings() },
                    onRetryInit = { retryInit() },
                    onFinish = { finishWithResult() }
                )
            }
        }
    }

    private fun openInstallGoogleTts() {
        try {
            val intent = TtsEngineHelper.getInstallGoogleTtsIntent()
            startActivity(intent)
        } catch (e: Exception) {
            // 兜底：打开浏览器下载页
            val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(
                    "https://play.google.com/store/apps/details?id=com.google.android.tts"
                )
            }
            startActivity(browserIntent)
        }
    }

    private fun openTtsSettings() {
        try {
            val intent = TtsEngineHelper.getOpenTtsSettingsIntent()
            startActivity(intent)
        } catch (e: Exception) {
            // 兜底：打开系统设置
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun checkVoiceData() {
        val intent = TtsEngineHelper.getCheckTtsDataIntent()
        try {
            startActivityForResult(intent, VoiceQueueManager.REQ_CHECK_TTS_DATA)
        } catch (e: Exception) {
            Log.w(TAG, "无法启动语音数据检查", e)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = TtsEngineHelper.getAccessibilitySettingsIntent()
        startActivity(intent)
    }

    private fun retryInit() {
        voiceQueueManager?.retryInit()
    }

    private fun finishWithResult() {
        setResult(RESULT_TTS_FIXED)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VoiceQueueManager.REQ_CHECK_TTS_DATA) {
            when (resultCode) {
                TextToSpeech.Engine.CHECK_VOICE_DATA_PASS -> {
                    Log.i(TAG, "语音数据检查通过")
                }
                TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL -> {
                    Log.w(TAG, "语音数据缺失，需要下载")
                    // 引导下载语音数据
                    try {
                        val installIntent = TtsEngineHelper.getInstallVoiceDataIntent()
                        startActivity(installIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "无法启动语音数据安装", e)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Compose UI
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsSetupGuideScreen(
    fromError: Boolean,
    onInstallGoogleTts: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onCheckVoiceData: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRetryInit: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(0) }
    var isFixed by remember { mutableStateOf(false) }
    val isOppo = remember { TtsEngineHelper.isOppoFamilyDevice() }
    val diagnostics = remember { TtsEngineHelper.getDeviceDiagnostics(context) }

    LaunchedEffect(Unit) {
        // 延迟一秒让用户感知页面已切换（TalkBack 会朗读标题）
        delay(1000)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "语音引擎设置",
                        modifier = Modifier.semantics {
                            contentDescription = "语音引擎设置页面标题"
                        }
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态提示横幅
            if (fromError && !isFixed) {
                ErrorBanner(isOppo = isOppo)
            } else if (isFixed) {
                SuccessBanner()
            }

            // 设备诊断信息（可折叠）
            DiagnosticsCard(diagnostics)

            // 修复步骤
            if (!isFixed) {
                Text(
                    "请按顺序完成以下步骤：",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics {
                        contentDescription = "请按顺序完成以下修复步骤"
                    }
                )

                // 步骤1：安装 Google TTS
                StepCard(
                    stepNumber = 1,
                    title = "安装 Google 文字转语音引擎",
                    description = "这是最稳定、兼容性最好的 TTS 引擎，" +
                            "支持中文离线语音播报。",
                    actionLabel = "去应用商店安装",
                    onAction = onInstallGoogleTts,
                    isCompleted = currentStep > 0
                )

                // OPPO 专项步骤
                if (isOppo) {
                    OppoSpecialStep(onOpenAccessibility)
                }

                // 步骤2：设置默认引擎
                StepCard(
                    stepNumber = if (isOppo) 3 else 2,
                    title = "设置系统默认语音引擎",
                    description = "在系统设置中，将" +
                            "\"首选引擎\"设置为 Google 文字转语音引擎。",
                    actionLabel = "打开 TTS 设置",
                    onAction = onOpenTtsSettings,
                    isCompleted = currentStep > 1
                )

                // 步骤3：下载语音数据
                StepCard(
                    stepNumber = if (isOppo) 4 else 3,
                    title = "下载中文语音数据",
                    description = "确保中文（中国）语音数据已下载。" +
                            "如果未下载，请点击设置页中的下载按钮。",
                    actionLabel = "检查语音数据",
                    onAction = onCheckVoiceData,
                    isCompleted = currentStep > 2
                )

                // 步骤4：测试
                StepCard(
                    stepNumber = if (isOppo) 5 else 4,
                    title = "测试语音播报",
                    description = "完成以上步骤后，点击测试按钮验证修复。",
                    actionLabel = "测试语音引擎",
                    onAction = {
                        onRetryInit()
                        currentStep++
                        isFixed = true
                    },
                    isCompleted = false
                )
            } else {
                // 修复成功状态
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "修复成功",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            "语音引擎修复完成！",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "您可以返回应用继续使用导航功能。",
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onFinish,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .semantics {
                                    contentDescription = "返回应用按钮"
                                }
                        ) {
                            Text("返回应用", fontSize = 18.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ErrorBanner(isOppo: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (isOppo) {
                    "错误提示：OPPO 手机检测到语音引擎初始化失败，请按以下步骤修复"
                } else {
                    "错误提示：语音引擎初始化失败，请按以下步骤修复"
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "警告图标",
                tint = MaterialTheme.colorScheme.error
            )
            Column {
                Text(
                    "语音引擎初始化失败",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    if (isOppo) {
                        "您的 OPPO 手机需要额外设置才能使用语音播报功能。"
                    } else {
                        "语音播报功能无法正常工作，需要手动修复。"
                    },
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun SuccessBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "语音引擎已修复，可以正常使用"
            }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "成功图标",
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "语音引擎已就绪",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: String) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics {
                    contentDescription = "设备诊断信息，点击展开"
                }
            ) {
                Icon(Icons.Default.Info, contentDescription = "信息图标")
                Text("设备诊断信息", fontWeight = FontWeight.Medium)
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = diagnostics,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    isCompleted: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "步骤 $stepNumber: $title${if (isCompleted) "，已完成" else ""}"
            }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 步骤编号圆圈
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isCompleted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$stepNumber",
                            fontWeight = FontWeight.Bold,
                            color = if (isCompleted)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (isCompleted) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "已完成",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                description,
                style = MaterialTheme.typography.bodyMedium
            )

            if (!isCompleted) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics {
                            contentDescription = "$actionLabel，$title"
                        }
                ) {
                    Text(actionLabel, fontSize = 16.sp)
                }
            }
        }
    }
}

/**
 * OPPO ColorOS 专项修复步骤卡片
 */
@Composable
private fun OppoSpecialStep(onOpenAccessibility: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "OPPO 手机专项设置：需要授予语音引擎无障碍权限"
            }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "2",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                    }
                }
                Text(
                    "【OPPO 必做】授予无障碍权限",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                "OPPO ColorOS 系统需要为语音引擎开启无障碍权限才能正常播报。\n" +
                        "设置路径：设置 → 其他设置 → 无障碍 → 文字转语音(TTS)输出 → 开启权限",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = onOpenAccessibility,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics {
                        contentDescription = "打开无障碍设置，授予语音引擎权限"
                    }
            ) {
                Text("打开无障碍设置", fontSize = 16.sp)
            }
        }
    }
}
