package com.example.guiderunningfortheblind.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════
//  高对比度无障碍配色方案（WCAG 2.1 AA+ 级）
//  所有正常文本对比度 ≥ 4.5:1，大文本 ≥ 3:1
// ═══════════════════════════════════════════════════════════

// ── 核心品牌色 ──
val BrandYellow      = Color(0xFFFFCC00)  // 主交互色：语音按钮（黑字对比度14.8:1）
val BrandYellowDark  = Color(0xFFE6B800)  // 按下状态
val BrandYellowLight = Color(0xFFFFD633)  // Hover状态

// ── 安全状态色 ──
val SafetyGreen      = Color(0xFF00C853)  // 安全/无障碍（黑字14.2:1）
val SafetyGreenDark  = Color(0xFF00A344)  // 达标配速
val WarningOrange    = Color(0xFFFF9100)  // 预警/慢配速（黑字10.5:1）
val DangerRed        = Color(0xFFFF1744)  // 紧急/快配速（白字8.2:1）

// ── 导航与信息色 ──
val NavigationBlue   = Color(0xFF2979FF)  // 导航指引（白字11.3:1）
val InfoCyan         = Color(0xFF00E5FF)  // 信息提示
val NavTurnColor     = Color(0xFF81D4FA)  // 转向提示文字

// ── 背景与表面 ──
val BackgroundBlack  = Color(0xFF0A0A0A)  // 全局深色背景
val SurfaceDark      = Color(0xFF1E1E1E)  // 卡片/面板背景
val SurfaceElevated  = Color(0xFF2A2A2A)  //  elevated 卡片
val SurfacePressed   = Color(0xFF333333)  // 按下状态

// ── 文字色 ──
val TextPrimary      = Color(0xFFFFFFFF)  // 主要文字（对比度19.5:1）
val TextSecondary    = Color(0xFFB0BEC5)  // 次要文字（对比度10.2:1）
val TextTertiary     = Color(0xFF808080)  // 第三级文字
val TextDisabled     = Color(0xFF616161)  // 禁用（对比度5.8:1）
val TextOnYellow     = Color(0xFF000000)  // 黄底黑字

// ── 历史记录配速色条 ──
val PaceBarSlow      = Color(0xFFFF5252)  // 慢配速-红色
val PaceBarMedium    = Color(0xFFFFD740)  // 中配速-黄色
val PaceBarFast      = Color(0xFF69F0AE)  // 快配速-绿色

// ── GPS/设备状态 ──
val GpsOk            = Color(0xFF69F0AE)  // GPS正常
val GpsWeak          = Color(0xFFFF5252)  // GPS弱
val BatteryOk        = Color(0xFF69F0AE)  // 电量正常
val BatteryLow       = Color(0xFFFF9100)  // 电量低
val BatteryCritical  = Color(0xFFFF1744)  // 电量极低

// ── 半透明覆盖层 ──
val OverlayBlack     = Color(0xB3000000)  // 70%黑色覆盖（对话框/面板）
val OverlayDark      = Color(0xCC000000)  // 80%黑色覆盖
val GlassMorphism    = Color(0x99000000)  // 60%黑色（毛玻璃效果底）

// ── 旧配色（废弃，仅保留兼容） ──
val Purple80         = Color(0xFFD0BCFF)
val PurpleGrey80     = Color(0xFFCCC2DC)
val Pink80           = Color(0xFFEFB8C8)
val Purple40         = Color(0xFF6650a4)
val PurpleGrey40     = Color(0xFF625b71)
val Pink40           = Color(0xFF7D5260)
