package com.tabbit.myvidhub.ui.detail

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 简介页配色工具。
 *
 * 手机端与电视端各自有独立的简介页实现，布局、控件、交互全部不共用；
 * 但「从封面取聚焦色」「把聚焦色压成整页填充色」属于与设备无关的纯计算，
 * 抽在这里共用，避免两端各写一份导致取色效果不一致。
 */

/**
 * 从封面提取"聚焦色"。
 *
 * 优先取饱和度较高的色块（vibrant / lightVibrant / muted / ...），
 * 并给饱和度、亮度设下限，避免暗色海报提取出来仍然是黑色。
 * 灰度封面提取不到色相时退回品牌色，保证页面总有色调。
 */
internal fun focusColorOf(bitmap: Bitmap): Color {
    val palette = runCatching { Palette.from(bitmap).generate() }.getOrNull()
    val swatch = listOfNotNull(
        palette?.vibrantSwatch,
        palette?.lightVibrantSwatch,
        palette?.mutedSwatch,
        palette?.darkVibrantSwatch,
        palette?.dominantSwatch,
        palette?.darkMutedSwatch
    ).firstOrNull { it.population > 0 }

    val argb = swatch?.rgb ?: averageColorOf(bitmap)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    if (hsv[1] < 0.06f) return AppColors.Brand

    hsv[1] = hsv[1].coerceIn(0.40f, 0.90f)
    hsv[2] = hsv[2].coerceIn(0.45f, 0.82f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Palette 失败时的兜底：按步长采样求平均色 */
private fun averageColorOf(bitmap: Bitmap): Int {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    val stepX = (bitmap.width / 24).coerceAtLeast(1)
    val stepY = (bitmap.height / 24).coerceAtLeast(1)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val p = bitmap.getPixel(x, y)
            r += android.graphics.Color.red(p)
            g += android.graphics.Color.green(p)
            b += android.graphics.Color.blue(p)
            n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0L) return AppColors.Brand.toArgb()
    return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/**
 * 整页填充的两个端点色，都保留聚焦色的色相：
 *   first  = 顶部，色相最浓、略亮，承接头图
 *   second = 底部，同色相压得更暗，收住长列表
 * 两端同色系，中间用插值平滑过渡，天然没有色带断层。
 */
internal fun pageTonesOf(focus: Color): Pair<Color, Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(focus.toArgb(), hsv)

    val topHsv = hsv.copyOf()
    topHsv[1] = (topHsv[1] * 0.96f).coerceIn(0.34f, 0.80f)
    topHsv[2] = 0.34f

    val bottomHsv = hsv.copyOf()
    bottomHsv[1] = (bottomHsv[1] * 0.90f).coerceIn(0.30f, 0.72f)
    bottomHsv[2] = 0.14f

    return Color(android.graphics.Color.HSVToColor(topHsv)) to
        Color(android.graphics.Color.HSVToColor(bottomHsv))
}

/**
 * 封面聚焦色缓存。
 *
 * 同一张封面会在首页、继续观看、简介页反复出现，每次进入都重新解码取色是浪费。
 * 以图片地址为 key 做小容量 LRU，命中即返回。
 */
internal object FocusColorCache {
    private const val MAX = 64

    private val map = object : LinkedHashMap<String, Color>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
            size > MAX
    }

    @Synchronized
    fun get(url: String): Color? = if (url.isBlank()) null else map[url]

    @Synchronized
    fun put(url: String, color: Color) {
        if (url.isNotBlank()) map[url] = color
    }
}
