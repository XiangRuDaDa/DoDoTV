@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tabbit.myvidhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 标题：有刮削艺术字（ClearLogo）就用图，没有才用文字兜底
                    if (item.logoUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.logoUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(if (isTv) 100.dp else 72.dp)
                                .widthIn(max = if (isTv) 420.dp else 300.dp)
                        )
                    } else {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    val meta = metaLine(item)
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f),
                            letterSpacing = 1.2.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 主按钮：品牌色实底 + 阴影，视觉最突出
                        Button(
                            onClick = { onPlay(item) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Brand,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("播放", fontWeight = FontWeight.SemiBold)
                        }
                        // 次按钮：半透明白底玻璃风，与主按钮形成层级
                        OutlinedButton(
                            onClick = { onOpen(item) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White.copy(alpha = 0.14f),
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, Color.White.copy(alpha = 0.35f)
                            ),
                            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 10.dp)
                        ) {
                            Text("详情", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // 页码指示点：居中放在按钮下方
                    Row(
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
