package com.tabbit.myvidhub.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.tabbit.myvidhub.data.emby.EmbyClient
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.detail.FocusColorCache
import com.tabbit.myvidhub.ui.detail.fallbackColorFromTitle
import com.tabbit.myvidhub.ui.detail.focusColorOf
import com.tabbit.myvidhub.ui.detail.loadPosterBitmap
import com.tabbit.myvidhub.ui.detail.pageTonesOf
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 电视端简介页（独立实现，不复用手机端 DetailScreen）。
 *
 * 结构差异来自观看距离：
 *   · 左侧大海报 + 右侧文案的两栏布局，替代手机端「上下堆叠」；
 *   · 操作按钮 2~3 个大按钮横排，遥控器左右即可选中，不做长列表；
 *   · 分集横排滚动，避免手机上那种需要精确点击的小格子。
 *
 * 背景沿用「封面聚焦色填充 + 封面到填充色的长距离溶解过渡」：
 * 头图下半段逐渐变成整页底色，两者之间没有硬边。
 */
@Composable
fun TvDetailScreen(
    vm: LibraryViewModel,
    mediaId: Long,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit
) {
    var media by remember { mutableStateOf<MediaEntity?>(null) }
    var episodes by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var scraped by remember { mutableStateOf<EmbyClient.ItemDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var dominant by remember { mutableStateOf(Color.Unspecified) }

    LaunchedEffect(mediaId) {
        loading = true
        // 整个加载过程兜底：任何 IO/解析异常都不允许冒泡到 Composition，
        // 否则电视端会直接崩溃退出（用户看到的就是「点详情闪退」）。
        try {
        val m = vm.mediaOf(mediaId)
        media = m
        if (m != null) {
            val src = vm.sourceOf(m.sourceId)
            if (src?.kind == SourceKind.EMBY) {
                scraped = runCatching { vm.embyDetailOf(m.sourceId, m.remoteId) }.getOrNull()
                if (m.kind == MediaKind.SERIES) {
                    runCatching { vm.loadEmbyEpisodes(m.sourceId, m.remoteId) }
                }
            } else if (src?.kind == SourceKind.TVBOX && m.kind == MediaKind.SERIES) {
                runCatching { vm.loadTvBoxDetail(m.sourceId, m.remoteId) }
            }
            val latest = vm.mediaOf(mediaId) ?: m
            media = latest
            episodes = runCatching { vm.episodesOf(latest.seriesKey) }.getOrDefault(emptyList())
        }
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            loading = false
        }
    }

    // 取色：与手机端一致，用 Coil ImageLoader 直接解码 bitmap，失败回退标题哈希色
    val tvContext = LocalContext.current
    LaunchedEffect(media?.posterUrl, media?.backdropUrl) {
        val m = media ?: return@LaunchedEffect
        val url = m.posterUrl.ifBlank { m.backdropUrl }
        if (url.isBlank()) {
            dominant = fallbackColorFromTitle(m.title)
            return@LaunchedEffect
        }
        val cached = FocusColorCache.get(url)
        if (cached != null) {
            dominant = cached
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.Default) { loadPosterBitmap(tvContext, url) }
        val color = bmp?.let { runCatching { focusColorOf(it) }.getOrNull() }
            ?: fallbackColorFromTitle(m.title)
        FocusColorCache.put(url, color)
        dominant = color
    }

    val item = media
    val tones = remember(dominant) {
        if (dominant == Color.Unspecified) {
            AppColors.Bg to lerp(AppColors.Bg, Color.Black, 0.55f)
        } else {
            pageTonesOf(dominant)
        }
    }
    val top by animateColorAsState(tones.first, tween(700), label = "tv_top")
    val bottom by animateColorAsState(tones.second, tween(700), label = "tv_bottom")

    val bgBrush = remember(top, bottom) {
        Brush.verticalGradient(
            0f to top,
            0.34f to top,
            0.72f to lerp(top, bottom, 0.72f),
            1f to bottom
        )
    }
    // 头图 → 填充色的溶解过渡
    val headerScrim = remember(top) {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.30f),
            0.28f to Color.Transparent,
            0.55f to top.copy(alpha = 0.34f),
            0.80f to top.copy(alpha = 0.82f),
            1f to top
        )
    }

    val backdrop = item?.let { it.backdropUrl.ifBlank { it.posterUrl } }.orEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        // 顶部横版图作为页面头图，向下溶解到填充色
        if (backdrop.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = item?.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(headerScrim)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.55f),
                                0.6f to Color.Transparent
                            )
                        )
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 44.dp)
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(if (backdrop.isBlank()) 44.dp else 250.dp))
            }

            // ---------- 左侧海报 + 右侧信息 ----------
            item {
                if (item == null) {
                    // 注意：这里在 LazyColumn 的 item 内，高度约束是无限的。
                    // 若让 TvEmptyState 用 fillMaxSize，会抛
                    // "Can't represent a size of 2147483647 in Constraints" 导致进页即闪退。
                    val boxMod = Modifier.fillMaxWidth().height(420.dp)
                    if (loading) {
                        TvEmptyState("加载中…", modifier = boxMod)
                    } else {
                        TvEmptyState("找不到该内容", modifier = boxMod)
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 44.dp, end = 44.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        AsyncImage(
                            model = item.posterUrl.ifBlank { item.backdropUrl },
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(220.dp)
                                .height(330.dp)
                                .background(AppColors.Surface)
                        )

                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                color = Color.White,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(10.dp))
                            TvMetaRow(item)
                            Spacer(Modifier.height(16.dp))

                            // 优先用刮削文案，其次回落本地记录
                            val overview = scraped?.tagline?.takeIf { it.isNotBlank() }
                                ?: item.overview
                            if (overview.isNotBlank()) {
                                Text(
                                    overview,
                                    color = Color.White.copy(alpha = 0.78f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(14.dp))
                            }

                            val directors = scraped?.directors?.joinToString(" / ") { it.name }.orEmpty()
                            if (directors.isNotBlank()) {
                                TvInfoLine("导演", directors)
                            }
                            val actors = scraped?.actors?.take(6)?.joinToString(" / ") { it.name }.orEmpty()
                            if (actors.isNotBlank()) {
                                TvInfoLine("主演", actors)
                            }
                            val studios = scraped?.studios?.joinToString(" / ").orEmpty()
                            if (studios.isNotBlank()) {
                                TvInfoLine("制片", studios)
                            }
                            if (item.genres.isNotBlank()) {
                                TvInfoLine("类型", item.genres)
                            }

                            Spacer(Modifier.height(22.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                TvPillButton(
                                    text = if (item.positionMs > 5_000L) "继续播放" else "立即播放",
                                    primary = true,
                                    onClick = { onPlay(item.id) }
                                )
                                TvPillButton(
                                    text = if (item.favorite) "已收藏" else "收藏",
                                    primary = false,
                                    onClick = { vm.toggleFavorite(item.id, !item.favorite) }
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 分集 ----------
            if (episodes.isNotEmpty()) {
                item { TvSectionTitle("选集（共 ${episodes.size} 集）") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 44.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(episodes, key = { "tv_ep_${it.id}" }) { ep ->
                            val label = if (ep.episode > 0) {
                                if (ep.season > 0) "S${ep.season} E${ep.episode}" else "第 ${ep.episode} 集"
                            } else ep.title
                            TvPillButton(
                                text = label,
                                primary = ep.positionMs > 5_000L,
                                onClick = { onPlay(ep.id) }
                            )
                        }
                    }
                }
            }
        }

        TvBackButton(onBack = onBack)
    }
}

@Composable
private fun TvInfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = AppColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(58.dp)
        )
        Text(
            value,
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
