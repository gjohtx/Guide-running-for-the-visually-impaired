package com.example.guiderunningfortheblind.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.guiderunningfortheblind.ui.theme.*

/**
 * TTS引擎安装引导横幅
 *
 * 当OPPO手机检测到没有可用的中文TTS引擎时显示，
 * 引导用户到应用商店安装讯飞语音+。
 *
 * 使用方式：在 HomeFragment 的 HomeScreenAccessible 中添加：
 *
 *     val needInstallTts by voiceQueueManager.needInstallTts.collectAsStateWithLifecycle()
 *     if (needInstallTts) {
 *         TtsInstallBanner(
 *             onDismiss = { /* 用户关闭提示 */ },
 *             modifier = Modifier.fillMaxWidth()
 *         )
 *     }
 */
@Composable
fun TtsInstallBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        onClick = {
            // 跳转到应用商店
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("market://details?id=com.iflytek.speechcloud")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // 应用商店不可用，用浏览器
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("https://www.xfyun.cn/")
                }
                context.startActivity(intent)
            }
        },
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E3A5F) // 深蓝色提示背景
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 语音图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(BrandYellow.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = BrandYellow,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 文字说明
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "安装语音引擎",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "点击安装讯飞语音+，获得更好的语音导航体验",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
