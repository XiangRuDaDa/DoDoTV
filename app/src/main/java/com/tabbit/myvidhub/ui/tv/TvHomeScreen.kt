package com.tabbit.myvidhub.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.components.subtitle
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * 电视端首页（Netflix 式横版结构，针对遥控器与十英尺观看距离设计）。
 *
 * 和手机端的区别不只是「字大一点」：
 *   · 顶部横向入口条代替底部标签栏，遥控器只需左右移动；
 *   · 首屏是一整屏的横版主视觉，标题与按钮压在左下，进入即有内容感；
 *   · 每个分区是无限横向海报行，聚焦卡片放大 10% 并描白边，保证三米外也能看清选中项；
 *   · 分区切换用纵向滚动，符合电视「上下换类目、左右换条目」的直觉。
 */
@Composable
fun TvHomeScreen(
    vm: LibraryViewModel,
    onSearch: () -> Unit,
    onDetail: (Long) -> Unit,
    onGenre: (kind: String?, genre: String?) -> Unit,
    onPlay: (MediaEntity) -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit
) {
    val continueList by vm.continueWatching.collectAsStateWithLifecycle()
    val featured by vm.featured.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recentMovies by vm.recentMovies.collectAsStateWithLifecycle()
    val recentSeries by vm.recentSeries.collectAsStateWithLifecycle()
    val recentAnime by vm.recentAnime.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()

    // 主视觉：优先有横版背景图的条目，不足时用海报补位
    val heroItems = remember(featured, library) {
        val primary = featured.filter { it.backdropUrl.isNotBlank() }
        if (primary.size >= 3) primary.take(5)
        else (primary + library.filter { it.posterUrl.isNotBlank() })
            .distinctBy { it.id }
            .take(5)
    }

    var heroIndex by remember { mutableStateOf(0) }
    LaunchedEffect(heroItems.size) {
        if (heroItems.size > 1) {
            while (true) {
                delay(9_000L)
                heroIndex = (heroIndex + 1) % heroItems.size
            }
        }
    }
    val hero = heroItems.getOrNull(heroIndex.coerceIn(0, (heroItems.size - 1).coerceAtLeast(0)))

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 44.dp)
        ) {
            // ---------- 主视觉（置顶，顶部入口条悬浮其上） ----------
            if (hero != null) {
                item {
                    TvHero(
                        item = hero,
                        focusRequester = firstFocus,
                        onPlay = { onPlay(hero) },
                        onDetail = { onDetail(hero.id) }
                    )
                }
            } else if (library.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        TvEmptyState(
                            text = if (vm.scanning) "正在同步媒体库…" else "还没有内容",
                            hint = if (vm.scanning) null else "先在「来源」里添加 Emby 或 TVBox，再同步媒体库"
                        )
                    }
                }
            }

            // ---------- 继续观看 ----------
            if (continueList.isNotEmpty()) {
                item {
                    TvSectionTitle("继续观看", trailing = { TvMoreButton { onGenre(null, null) } })
                }
                item {
                    TvPosterRow(
                        items = continueList,
                        onDetail = onDetail,
                        onPlay = onPlay
                    )
                }
            }

            // ---------- 我的收藏 ----------
            if (favorites.isNotEmpty()) {
                item {
                    TvSectionTitle("我的收藏") { TvMoreButton { onGenre(null, null) } }
                }
                item { TvPosterRow(items = favorites, onDetail = onDetail) }
            }

            // ---------- 电影 ----------
            if (recentMovies.isNotEmpty()) {
                item {
                    TvSectionTitle("电影") { TvMoreButton { onGenre(MediaKind.MOVIE, null) } }
                }
                item { TvPosterRow(items = recentMovies, onDetail = onDetail) }
            }

            // ---------- 剧集 ----------
            if (recentSeries.isNotEmpty()) {
                item {
                    TvSectionTitle("剧集") { TvMoreButton { onGenre(MediaKind.SERIES, null) } }
                }
                item { TvPosterRow(items = recentSeries, onDetail = onDetail) }
            }

            // ---------- 动漫 ----------
            if (recentAnime.isNotEmpty()) {
                item {
                    TvSectionTitle("动漫") { TvMoreButton { onGenre(null, null) } }
                }
                item { TvPosterRow(items = recentAnime, onDetail = onDetail) }
            }
        }

        // ---------- 透明悬浮顶部入口条（压在主视觉之上） ----------
        TvTopBar(
            count = library.size,
            sourceCount = sources.size,
            scanning = vm.scanning,
            onSearch = onSearch,
            onSources = onSources,
            onSettings = onSettings,
            onRefresh = { vm.scanAll() }
        )
    }
}

/** 顶部入口条：左边品牌与统计，右边可聚焦的操作按钮 */
@Composable
private fun TvTopBar(
    count: Int,
    sourceCount: Int,
    scanning: Boolean,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 44.dp, end = 44.dp, top = 26.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "DouDouTV",
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (scanning) "同步中…" else "$count 个条目 · $sourceCount 个来源",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (scanning) {
            CircularProgressIndicator(color = AppColors.Brand, strokeWidth = 2.dp)
            Spacer(Modifier.width(18.dp))
        } else {
            TvPillButton("同步", primary = false, onClick = onRefresh)
            Spacer(Modifier.width(12.dp))
        }
        TvPillButton("搜索", primary = false, onClick = onSearch)
        Spacer(Modifier.width(12.dp))
        TvPillButton("来源", primary = false, onClick = onSources)
        Spacer(Modifier.width(12.dp))
        TvPillButton("设置", primary = false, onClick = onSettings)
    }
}

/** 主视觉：满宽横版图，左下压标题与操作，底部渐隐接入页面底色 */
@Composable
private fun TvHero(
    item: MediaEntity,
    focusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDetail: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(430.dp)
    ) {
        AsyncImage(
            model = item.backdropUrl.ifBlank { item.posterUrl },
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 左侧压暗保证文字可读，底部渐隐让主视觉和下面的行自然衔接
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.86f),
                        0.45f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Transparent
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to AppColors.Bg
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 44.dp, end = 44.dp, bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题：优先用刮削到的 ClearLogo（透明 PNG 艺术字），没有才回落文字
            if (item.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(96.dp)
                        .widthIn(max = 460.dp)
                )
            } else {
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(0.62f)) {
                TvMetaRow(item, modifier = Modifier.align(Alignment.Center))
            }
            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    item.overview,
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvPillButton(
                    text = if (item.positionMs > 5_000L) "继续播放" else "立即播放",
                    modifier = Modifier.focusRequester(focusRequester),
                    primary = true,
                    onClick = onPlay
                )
                TvPillButton(text = "详情", primary = false, onClick = onDetail)
            }
        }
    }
}

/** 横向海报行：左右滚动，聚焦放大由卡片自身处理 */
@Composable
private fun TvPosterRow(
    items: List<MediaEntity>,
    onDetail: (Long) -> Unit,
    onPlay: ((MediaEntity) -> Unit)? = null
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = TvRowPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(TvRowSpacing)
    ) {
        items(items, key = { "tv_${it.id}" }) { item ->
            TvPosterCard(
                item = item,
                onClick = { onDetail(item.id) }
            )
        }
    }
}
