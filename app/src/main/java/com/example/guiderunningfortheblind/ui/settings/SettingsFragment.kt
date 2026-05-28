package com.example.guiderunningfortheblind.ui.settings

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.guiderunningfortheblind.MainApplication
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity
import com.example.guiderunningfortheblind.speech.SpeechViewModel
import com.example.guiderunningfortheblind.ui.theme.*
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint

/**
 * 设置界面 - 无障碍重构版
 *
 * 新增：
 * 1. 导航设置分组（偏离敏感度、播报频率、导航语音开关）
 * 2. 语音与播报分组（语速、音量、播报内容开关）
 * 3. 路线偏好分组（盲道优先、避开施工）
 * 4. 安全与反馈分组（震动强度、虚拟陪跑员、弱光手电）
 * 5. 无障碍分组（TalkBack优化、高对比度、字体大小）
 * 6. 所有设置项带语音解释按钮
 * 7. Slider触控区域增大至48dp+
 * 8. 全部中文
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory((requireActivity().application as MainApplication).userProfileRepository)
    }

    private val speechViewModel: SpeechViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            val profile by viewModel.userProfile.collectAsStateWithLifecycle()
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                viewModel.saveStatus.collect { success ->
                    val msg = if (success) "设置已保存" else "保存失败，请重试"
                    speechViewModel.queueInstruction(msg)
                }
            }

            GuideRunningFortheBlindTheme {
                SettingsScreenAccessible(
                    profile = profile ?: UserProfileEntity(),
                    onSave = { viewModel.updateProfile(it) },
                    onExplainSetting = { settingName, description ->
                        speechViewModel.queueInstruction("$settingName：$description")
                    },
                    speechViewModel = speechViewModel
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  设置主屏幕（无障碍版）
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenAccessible(
    profile: UserProfileEntity,
    onSave: (UserProfileEntity) -> Unit,
    onExplainSetting: (String, String) -> Unit,
    speechViewModel: SpeechViewModel
) {
    val vibrator = LocalContext.current.let {
        ContextCompat.getSystemService(it, Vibrator::class.java)
    }

    // 本地状态
    var age by remember(profile) { mutableFloatStateOf(profile.age.toFloat()) }
    var targetCadence by remember(profile) { mutableFloatStateOf(profile.targetCadence.toFloat()) }
    var vibrationIntensity by remember(profile) { mutableFloatStateOf(profile.vibrationIntensity) }
    var voiceIntervalDistance by remember(profile) { mutableFloatStateOf(profile.voiceFeedbackIntervalDistance.toFloat()) }
    var isVirtualPartnerEnabled by remember(profile) { mutableStateOf(profile.isVirtualPartnerEnabled) }
    var useFlashlightAtNight by remember(profile) { mutableStateOf(profile.useFlashlightAtNight) }



    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "设置",
            color = TextPrimary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))


        // ── 分组4: 安全与反馈 ──
        AccessibleSettingHeader("安全与反馈")

        AccessibleSliderItem(
            label = "震动强度",
            value = vibrationIntensity,
            onValueChange = { vibrationIntensity = it },
            valueRange = 0f..1f,
            displayValue = "${(vibrationIntensity * 100).toInt()}%",
            description = "震动反馈的强度。0%为关闭震动。",
            onExplain = { onExplainSetting("震动强度", "震动反馈的强度。0%为关闭震动，100%为最大强度。建议设为80%以上，确保能感受到。") },
            steps = 9
        )

        AccessibleSliderItem(
            label = "语音播报间隔",
            value = voiceIntervalDistance,
            onValueChange = { voiceIntervalDistance = it },
            valueRange = 100f..1000f,
            displayValue = "${voiceIntervalDistance.toInt()}米",
            description = "每隔多少米播报一次跑步数据。",
            onExplain = { onExplainSetting("语音播报间隔", "每隔多少米播报一次跑步数据，包括距离、配速、时间。建议设为200米。") },
            steps = 8
        )

        AccessibleSwitchItem(
            label = "虚拟陪跑员",
            checked = isVirtualPartnerEnabled,
            onCheckedChange = { isVirtualPartnerEnabled = it },
            description = "模拟一个陪跑伙伴进行语音互动。",
            onExplain = { onExplainSetting("虚拟陪跑员", "开启后会有虚拟跑步伙伴进行语音鼓励和互动。适合独自跑步时使用。") }
        )

        AccessibleSwitchItem(
            label = "弱光自动手电",
            checked = useFlashlightAtNight,
            onCheckedChange = { useFlashlightAtNight = it },
            description = "检测到光线不足时自动开启摄像头补光灯。",
            onExplain = { onExplainSetting("弱光自动手电", "在光线不足的环境中，自动开启摄像头补光灯辅助避障。建议开启。") }
        )



        Spacer(modifier = Modifier.height(16.dp))

        // ── 用户资料 ──
        AccessibleSettingHeader("用户资料")

        AccessibleSliderItem(
            label = "年龄",
            value = age,
            onValueChange = { age = it },
            valueRange = 10f..80f,
            displayValue = "${age.toInt()}岁",
            description = "您的年龄，用于计算心率和卡路里。",
            onExplain = { onExplainSetting("年龄", "您的年龄，用于计算推荐心率和消耗的卡路里。请如实填写。") },
            steps = 13
        )

        AccessibleSliderItem(
            label = "目标步频",
            value = targetCadence,
            onValueChange = { targetCadence = it },
            valueRange = 120f..220f,
            displayValue = "${targetCadence.toInt()}步/分钟",
            description = "每分钟的目标步数。",
            onExplain = { onExplainSetting("目标步频", "每分钟的目标步数。一般跑步建议160到180步每分钟。") },
            steps = 9
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── 保存按钮 ──
        Button(
            onClick = {
                vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                val updatedProfile = profile.copy(
                    age = age.toInt(),
                    targetCadence = targetCadence.toInt(),
                    vibrationIntensity = vibrationIntensity,
                    voiceFeedbackIntervalDistance = voiceIntervalDistance.toInt(),
                    isVirtualPartnerEnabled = isVirtualPartnerEnabled,
                    useFlashlightAtNight = useFlashlightAtNight
                )
                onSave(updatedProfile)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandYellow)
        ) {
            Text(
                "保存设置",
                color = Color.Black,
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ═══════════════════════════════════════════════════════════
//  无障碍设置组件
// ═══════════════════════════════════════════════════════════

@Composable
fun AccessibleSettingHeader(text: String) {
    Text(
        text = text,
        color = BrandYellow,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
fun AccessibleSettingSubHeader(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun AccessibleSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    description: String = "",
    onExplain: () -> Unit = {},
    steps: Int = 0
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label，当前值$displayValue。$description"
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayValue,
                    color = NavigationBlue,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (onExplain != {}) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onExplain,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "听取${label}说明",
                            tint = TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = BrandYellow,
                    activeTrackColor = BrandYellow,
                    inactiveTrackColor = SurfaceElevated
                )
            )
        }
    }
}

@Composable
fun AccessibleSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String = "",
    onExplain: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) {
                contentDescription = "$label，${if (checked) "已开启" else "已关闭"}。$description"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium
            )
            if (onExplain != {}) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onExplain,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "听取${label}说明",
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BrandYellow,
                checkedTrackColor = BrandYellow.copy(alpha = 0.5f),
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = SurfaceElevated
            )
        )
    }
}
