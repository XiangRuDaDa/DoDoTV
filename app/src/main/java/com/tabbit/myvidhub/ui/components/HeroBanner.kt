@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tabbit.myvidhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * 首页顶部大图轮播（VidHub 的「精选」区）。
 *
 * 视觉要点：
 * · 16:9 背景剧照铺满，渐变压暗后沉入页面底色，消除硬边界
 * · 标题优先用刮削到的艺术字（ClearLogo，透明 PNG）；没有才回落到文字
 * · 只保留「播放 / 详情」两个按钮，不再堆影片简介，画面更干净
 * · 遮罩透明度整体调低，让剧照本身的色彩透出来，营造艺术氛围
 */
@Composable
fun HeroBanner(
    items: List<MediaEntity>,
    isTv: Boolean,
    onOpen: (MediaEntity) -> Unit,
    onPlay: (MediaEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6_500)
            val next = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(next)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(if (isTv) 430.dp else 392.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize()) {
                val art = item.backdropUrl.ifBlank { item.posterUrl }
                if (art.isNotBlank()) {
                    AsyncImage(
                        model = art,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(AppColors.Surface))
                }

                // 纵向遮罩：顶部只轻微压暗保住白字，底部平滑融入页面底色。
                // 相比原来的 0.35/0.55，这里降到 0.08/0.22，让剧照自身的色彩保留下来。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.08f),
                                0.42f to Color.Black.copy(alpha = 0.20f),
                                0.78f to Color.Black.copy(alpha = 0.46f),
                                1f to AppColors.Bg
                            )
                        )
                )
                // 横向遮罩：只在左侧给标题留一层薄纱，右侧完全放开，避免压死画面
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to AppColors.Bg.copy(alpha = 0.62f),
                                0.45f to AppColors.Bg.copy(alpha = 0.14f),
                                1f to Color.Transparent
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 22.dp, end = 22.dp, bottom = 24.dp)
                        .fillMaxWidth(if (isTv) 0.52f else 0.82f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 标题：有刮削艺术字就用图，没有才用文字兜底
                    if (item.logoUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.logoUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(if (isTv) 96.dp else 68.dp)
                                .widthIn(max = if (isTv) 380.dp else 260.dp)
                        )
                    } else {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val meta = metaLine(item)
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            letterSpacing = 1.2.sp
                        )
                    }

                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onPlay(item) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Brand,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("播放")
                        }
                        OutlinedButton(onClick = { onOpen(item) }) {
                            Text("详情")
                        }
                    }
                }
            }
        }

        // 页码指示点
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(items.size) { i ->
                val active = pagerState.currentPage == i
                Box(
                    Modifier
                        .size(width = if (active) 16.dp else 6.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (active) AppColors.Brand else Color.White.copy(alpha = 0.35f))
                )
            }
        }
    }
}

private fun metaLine(item: MediaEntity): String {
    val parts = mutableListOf<String>()
    if (item.year > 0) parts += item.year.toString()
    if (item.rating > 0) parts += "★ %.1f".format(item.rating)
    if (item.genres.isNotBlank()) {
        parts += item.genres.split(",", "/", " ", "，").first().trim()
    }
    return parts.joinToString("  ·  ")
}
