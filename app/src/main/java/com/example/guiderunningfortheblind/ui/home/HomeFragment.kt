package com.example.guiderunningfortheblind.ui.home

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.example.guiderunningfortheblind.MainApplication
import com.example.guiderunningfortheblind.R
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.speech.RecognitionResult
import com.example.guiderunningfortheblind.speech.SpeechViewModel
import com.example.guiderunningfortheblind.ui.theme.*
import com.example.guiderunningfortheblind.ui.running.RunningSessionViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * 首页 - 无障碍重构版
 *
 * 【语音逻辑修复摘要】
 * 1. 大语音按钮现在真正调用 speechViewModel.startVoiceInput() / stopVoiceInput()
 * 2. 使用 awaitEachGesture 替代 detectTapGestures，避免 onPress/onTap 冲突
 * 3. 添加 RecognizerIntent fallback 支持（ColorOS 兼容）
 * 4. 监听 lastRecognitionResult 处理识别到的命令（开始跑步/历史/设置）
 * 5. 移除旧的连续监听命令收集（"START"），改为按住说话模式
 * 6. 使用 HomeViewModel.handleVoiceCommand() 路由语音命令
 *
 * 核心设计：
 * 1. 顶部：设备自检状态条（GPS/电量/摄像头/网络）
 * 2. 中心：140dp 大语音按钮（黄色高对比度，按住说话）
 * 3. 下方：开始跑步大字按钮
 * 4. 底部：最近跑步卡片 + 历史/设置导航
 * 5. 移除：标题、计划列表等认知负担元素
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    companion object {
        private const val TAG = "HomeFragment"
    }

    /**
     * 【ColorOS 兼容】RecognizerIntent fallback 启动器
     * 当 SpeechRecognizer 返回 error=12 等服务器错误时，自动启动系统语音输入对话框
     */
    private val recognizerFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val app = requireActivity().application as MainApplication
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            app.voiceCommandManager.onRecognizerIntentResult(matches)
        } else {
            app.voiceCommandManager.onRecognizerIntentCancelled()
        }
    }

    private val sharedViewModel: RunningSessionViewModel by activityViewModels {
        val app = requireActivity().application as MainApplication
        RunningSessionViewModel.Factory(
            app.runningSessionRepository,
            app.locationManager,
            app.healthConnectManager,
            app.userProfileRepository
        )
    }

    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory((requireActivity().application as MainApplication).runningRepository)
    }

    private val speechViewModel: SpeechViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            val plans by homeViewModel.plans.collectAsStateWithLifecycle()

            val app = requireActivity().application as MainApplication
            val recentRuns by app.runningSessionRepository.allSessions
                .collectAsStateWithLifecycle(initialValue = emptyList())

            // ── 【修复】初始化语音协调器 ───────
            LaunchedEffect(Unit) {
                speechViewModel.setVoiceCoordinator(app.voiceCoordinator)
            }

            // ── 【ColorOS 兼容】监听 RecognizerIntent fallback 请求 ───────
            LaunchedEffect(Unit) {
                app.voiceCommandManager.requestFallbackRecognition.collect {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出您的命令...")
                    }
                    recognizerFallbackLauncher.launch(intent)
                }
            }

            // ── 【修复】监听语音识别结果，执行对应的页面导航 ───────
            val lastResult by speechViewModel.lastRecognitionResult.collectAsStateWithLifecycle()
            LaunchedEffect(lastResult) {
                when (lastResult) {
                    is RecognitionResult.Success -> {
                        val text = (lastResult as RecognitionResult.Success).text
                        Log.i(TAG, "【识别成功】命令文字=\"$text\"")
                        when (val command = homeViewModel.handleVoiceCommand(text)) {
                            is HomeViewModel.VoiceCommand.StartRunning -> {
                                speechViewModel.stopSpeaking()
                                speechViewModel.queueInstruction("开始跑步")
                                speechViewModel.clearRecognitionResult() // 导航前清除，避免返回时重复触发
                                navigateToRunning(null)
                            }
                            is HomeViewModel.VoiceCommand.GoHistory -> {
                                speechViewModel.stopSpeaking()
                                speechViewModel.queueInstruction("历史记录")
                                speechViewModel.clearRecognitionResult()
                                findNavController().navigate(R.id.action_home_to_history)
                            }
                            is HomeViewModel.VoiceCommand.GoSettings -> {
                                speechViewModel.stopSpeaking()
                                speechViewModel.queueInstruction("设置")
                                speechViewModel.clearRecognitionResult()
                                findNavController().navigate(R.id.action_home_to_settings)
                            }
                            is HomeViewModel.VoiceCommand.Unknown -> {
                                speechViewModel.stopSpeaking()
                                speechViewModel.queueInstruction("请说：开始跑步、历史记录或设置")
                            }
                        }
                        homeViewModel.consumeCommand()
                    }
                    is RecognitionResult.NoMatch -> {
                        Log.w(TAG, "未识别到文字，播报语音提示")
                        speechViewModel.queueInstruction("未识别到文字，请说：开始跑步、历史记录或设置")
                    }
                    is RecognitionResult.Error -> {
                        Log.e(TAG, "识别错误: ${(lastResult as RecognitionResult.Error).error}")
                        // 错误已在 SpeechViewModel 中播报，不重复
                    }
                    null -> { /* 无操作 */ }
                }
            }

            GuideRunningFortheBlindTheme {
                HomeScreenAccessible(
                    plans = plans,
                    recentRuns = recentRuns,
                    onStartRunning = { navigateToRunning(it) },
                    onGoToHistory = { findNavController().navigate(R.id.action_home_to_history) },
                    onGoToSettings = { findNavController().navigate(R.id.action_home_to_settings) },
                    speechViewModel = speechViewModel
                )
            }
        }
    }

    private fun navigateToRunning(plan: RunningPlanEntity?) {
        plan?.aiPlanJson?.let { json ->
            // 这里以后可以解析 AI 计划
        }
        findNavController().navigate(R.id.action_home_to_preRun)
    }
}

// ═══════════════════════════════════════════════════════════
//  首页主屏幕（无障碍版）
// ═══════════════════════════════════════════════════════════

@Composable
fun HomeScreenAccessible(
    plans: List<RunningPlanEntity>,
    recentRuns: List<RunningSessionEntity>,
    onStartRunning: (RunningPlanEntity?) -> Unit,
    onGoToHistory: () -> Unit,
    onGoToSettings: () -> Unit,
    speechViewModel: SpeechViewModel
) {
    val context = LocalContext.current
    val vibrator = remember { ContextCompat.getSystemService(context, Vibrator::class.java) }

    // 【修复】使用 speechViewModel 的 isListening 替代本地状态
    val isListening by speechViewModel.isListening.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── 顶部设备自检状态条 ──
        StatusCheckBar(
            modifier = Modifier.fillMaxWidth(),
            onIssueFound = { issue ->
                vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                speechViewModel.queueInstruction(issue)
            }
        )

        Spacer(modifier = Modifier.weight(0.4f))

        // ── 核心：大语音按钮（140dp，黄色高对比度，按住说话） ──
        BigVoiceButton(
            isListening = isListening,
            onPressStart = {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                speechViewModel.startVoiceInput()
            },
            onPressEnd = {
                speechViewModel.stopVoiceInput()
            },
            modifier = Modifier.size(140.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 【修复】更新提示文字
        Text(
            text = if (isListening) "正在聆听，请说出您的命令..." else "按住说话，说出：开始跑步、历史记录或设置",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── 快捷操作按钮（56dp高，大字） ──
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AccessibleButton(
                text = "开始跑步",
                onClick = {
                    vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                    onStartRunning(null)
                },
                backgroundColor = SurfaceDark,
                contentColor = TextPrimary,
                icon = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            )
        }

        Spacer(modifier = Modifier.weight(0.3f))

        // ── 最近跑步卡片 ──
        if (recentRuns.isNotEmpty()) {
            RecentRunCard(
                session = recentRuns.first(),
                onClick = { onGoToHistory() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── 底部导航（历史 / 设置） ──
        BottomNavigationBar(
            onHistory = onGoToHistory,
            onSettings = onGoToSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  无障碍按钮组件（56dp高，大字）
// ═══════════════════════════════════════════════════════════

@Composable
fun AccessibleButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    contentColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = text },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  设备自检状态条
// ═══════════════════════════════════════════════════════════

@Composable
fun StatusCheckBar(
    modifier: Modifier = Modifier,
    onIssueFound: (String) -> Unit = {}
) {
    val context = LocalContext.current

    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val isGpsEnabled = remember {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    val batteryStatus = rememberBatteryStatus()
    val batteryPct = batteryStatus.first
    val isCharging = batteryStatus.second

    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    val connectivityManager = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    }
    val isNetworkAvailable = remember {
        val activeNetwork = connectivityManager.activeNetworkInfo
        activeNetwork?.isConnected == true
    }

    LaunchedEffect(Unit) {
        val issues = mutableListOf<String>()
        if (!isGpsEnabled) issues.add("GPS未开启，请打开定位服务")
        if (batteryPct < 20) issues.add("电量仅${batteryPct}%，建议充电后使用")
        if (!hasCameraPermission) issues.add("摄像头权限未授权，避障功能不可用")
        if (!isNetworkAvailable) issues.add("网络未连接，AI对话功能不可用")

        if (issues.isNotEmpty()) {
            delay(800)
            onIssueFound("设备状态：${issues.joinToString("。")}")
        }
    }

    Row(
        modifier = modifier
            .height(48.dp)
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusItem(
            label = "GPS",
            isOk = isGpsEnabled,
            detailLabel = if (isGpsEnabled) "GPS正常" else "GPS未开启"
        )
        StatusItem(
            label = "电量",
            isOk = batteryPct > 20,
            detailLabel = "电量${batteryPct}%${if (isCharging) "充电中" else ""}",
            customColor = when {
                batteryPct <= 10 -> BatteryCritical
                batteryPct <= 20 -> BatteryLow
                else -> BatteryOk
            }
        )
        StatusItem(
            label = "摄像头",
            isOk = hasCameraPermission,
            detailLabel = if (hasCameraPermission) "摄像头正常" else "无权限"
        )
        StatusItem(
            label = "网络",
            isOk = isNetworkAvailable,
            detailLabel = if (isNetworkAvailable) "网络已连接" else "网络断开"
        )
    }
}

@Composable
private fun StatusItem(
    label: String,
    isOk: Boolean,
    detailLabel: String,
    customColor: Color? = null
) {
    val color = customColor ?: if (isOk) SafetyGreen else DangerRed
    val icon = if (isOk) "\u2713" else "\u2715"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$detailLabel，$label${if (isOk) "正常" else "异常"}"
            if (!isOk) liveRegion = LiveRegionMode.Polite
        }
    ) {
        Text(
            text = icon,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = label,
            color = if (isOk) TextSecondary else color,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun rememberBatteryStatus(): Pair<Int, Boolean> {
    val context = LocalContext.current
    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    batteryPct = (level * 100 / scale.toFloat()).roundToInt()
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return batteryPct to isCharging
}

// ═══════════════════════════════════════════════════════════
//  大语音按钮（140dp，黄色，按住说话）
// ═══════════════════════════════════════════════════════════

/**
 * 大语音按钮 - 按住说话交互
 *
 * 【修复摘要】
 * 1. 使用 awaitEachGesture 替代 detectTapGestures，避免 onPress/onTap 冲突
 * 2. 移除 onTap 参数，统一为按住说话交互
 * 3. 按下时调用 onPressStart 启动语音识别
 * 4. 松开时调用 onPressEnd 停止语音识别
 * 5. 识别结果通过 speechViewModel.lastRecognitionResult 异步返回
 */
@Composable
fun BigVoiceButton(
    isListening: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isListening) 0.6f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .scale(pulseScale)
            .semantics {
                contentDescription = if (isListening) {
                    "正在聆听，请说出您的命令，例如：开始跑步、历史记录或设置。松开手指结束。"
                } else {
                    "按住说话，说出：开始跑步、历史记录或设置"
                }
                role = Role.Button
                if (isListening) liveRegion = LiveRegionMode.Assertive
            }
            // 【关键修复】使用 awaitEachGesture 替代 detectTapGestures
            // 原因：detectTapGestures 的 onPress 和 onTap 会冲突
            // awaitEachGesture 可以精确控制按下/松手的完整生命周期
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 等待手指按下
                    val down = awaitFirstDown()
                    down.consume()
                    onPressStart()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                    // 等待所有手指抬起
                    while (true) {
                        val event = awaitPointerEvent()
                        val allUp = event.changes.all { !it.pressed }
                        if (allUp) {
                            event.changes.forEach { it.consume() }
                            onPressEnd()
                            break
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        if (isListening) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = BrandYellow.copy(alpha = pulseAlpha),
                        shape = CircleShape
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize(0.85f)
                .background(
                    color = if (isListening) BrandYellowLight else BrandYellow,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color.Black
                )
                if (!isListening) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "按住说话",
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  最近跑步卡片
// ═══════════════════════════════════════════════════════════

@Composable
fun RecentRunCard(
    session: RunningSessionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateStr = remember(session.startTime) {
        val sdf = SimpleDateFormat("M月d日", Locale.CHINA)
        sdf.format(Date(session.startTime))
    }
    val distanceKm = remember(session.totalDistance) {
        "%.1f".format(session.totalDistance / 1000)
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .height(80.dp)
            .semantics {
                contentDescription = "最近一次跑步，${dateStr}，${distanceKm}公里，平均配速${session.avgPace}，双击查看详情"
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = dateStr,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "${distanceKm} 公里",
                    color = TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = session.avgPace,
                    color = BrandYellow,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "平均配速",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  底部导航栏
// ═══════════════════════════════════════════════════════════

@Composable
fun BottomNavigationBar(
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(64.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavButton(
            icon = Icons.Filled.History,
            label = "历史记录",
            onClick = onHistory,
            modifier = Modifier.weight(1f)
        )
        BottomNavButton(
            icon = Icons.Filled.Settings,
            label = "设置",
            onClick = onSettings,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BottomNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(64.dp)
            .clickable(
                onClick = onClick,
                role = Role.Button
            )
            .semantics { contentDescription = label }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
