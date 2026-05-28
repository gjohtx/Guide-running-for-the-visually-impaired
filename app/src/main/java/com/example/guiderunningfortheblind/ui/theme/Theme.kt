package com.example.guiderunningfortheblind.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 从 Context 递归查找 Activity（兼容 Hilt Fragment）
 * Hilt 注入的 Fragment 中，ComposeView 的 context 是
 * ViewComponentManager$FragmentContextWrapper，不是直接的 Activity。
 * 此方法通过递归遍历 ContextWrapper 链来找到真正的 Activity。
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private val AccessibleDarkColorScheme = darkColorScheme(
    primary = BrandYellow,
    onPrimary = TextOnYellow,
    primaryContainer = BrandYellowDark,
    onPrimaryContainer = TextOnYellow,
    secondary = NavigationBlue,
    onSecondary = TextPrimary,
    secondaryContainer = NavigationBlue.copy(alpha = 0.2f),
    onSecondaryContainer = TextPrimary,
    tertiary = InfoCyan,
    onTertiary = TextPrimary,
    tertiaryContainer = InfoCyan.copy(alpha = 0.2f),
    onTertiaryContainer = TextPrimary,
    background = BackgroundBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    outlineVariant = TextDisabled,
    error = DangerRed,
    onError = TextPrimary,
    errorContainer = DangerRed.copy(alpha = 0.2f),
    onErrorContainer = DangerRed,
    inversePrimary = TextPrimary,
    inverseSurface = TextPrimary,
    inverseOnSurface = BackgroundBlack,
    scrim = OverlayBlack,
    surfaceTint = BrandYellow.copy(alpha = 0.1f)
)

private val AccessibleLightColorScheme = lightColorScheme(
    primary = BrandYellowDark,
    onPrimary = TextOnYellow,
    primaryContainer = BrandYellow,
    onPrimaryContainer = TextOnYellow,
    secondary = NavigationBlue,
    onSecondary = TextPrimary,
    secondaryContainer = NavigationBlue.copy(alpha = 0.15f),
    onSecondaryContainer = NavigationBlue,
    tertiary = InfoCyan,
    onTertiary = TextPrimary,
    tertiaryContainer = InfoCyan.copy(alpha = 0.15f),
    onTertiaryContainer = InfoCyan,
    background = BackgroundBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    outlineVariant = TextDisabled,
    error = DangerRed,
    onError = TextPrimary,
    errorContainer = DangerRed.copy(alpha = 0.1f),
    onErrorContainer = DangerRed,
    inversePrimary = TextPrimary,
    inverseSurface = TextPrimary,
    inverseOnSurface = BackgroundBlack,
    scrim = OverlayBlack,
    surfaceTint = BrandYellow.copy(alpha = 0.05f)
)

@Composable
fun GuideRunningFortheBlindTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            AccessibleDarkColorScheme
        }
        darkTheme -> AccessibleDarkColorScheme
        else -> AccessibleLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 使用 findActivity() 兼容 Hilt Fragment 的 ContextWrapper
            val window = view.context.findActivity()?.window
            window?.let { w ->
                w.statusBarColor = BackgroundBlack.toArgb()
                w.navigationBarColor = BackgroundBlack.toArgb()
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AccessibleTypography,
        content = content
    )
}