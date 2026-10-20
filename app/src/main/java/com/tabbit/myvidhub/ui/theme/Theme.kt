package com.tabbit.myvidhub.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** VidHub 式深色影音配色：近黑底 + 蓝紫强调色 */
object AppColors {
    val Brand = Color(0xFF4F8CFF)
    val BrandSoft = Color(0xFF6BA1FF)
    val Bg = Color(0xFF0B0D12)
    val Surface = Color(0xFF141822)
    val SurfaceHi = Color(0xFF1E2430)
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFF9BA4B4)
    val TextTertiary = Color(0xFF6B7482)
    val Rating = Color(0xFFFFC107)
    val ScrimTop = Color(0xFF0B0D12)
}

private val Scheme = darkColorScheme(
    primary = AppColors.Brand,
    onPrimary = Color.White,
    primaryContainer = AppColors.BrandSoft,
    secondary = AppColors.BrandSoft,
    background = AppColors.Bg,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceHi,
    onSurfaceVariant = AppColors.TextSecondary
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

fun Context.isTelevision(): Boolean {
    val manager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (manager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

    // 兜底：电视盒子常以普通模式上报，用 Leanback 特性/无触摸屏来识别
    val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return hasLeanback || !hasTouchscreen
}

@Composable
fun MyVidHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
