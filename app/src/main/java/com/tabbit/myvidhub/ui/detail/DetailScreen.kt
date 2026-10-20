package com.tabbit.myvidhub.ui.detail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 剧集/电影详情页（VidHub 形态）。
 *
 * 结构自上而下：
 *   backdrop 沉浸头图（渐变沉底，返回键浮于其上）
 *   → 海报 + 标题 + 评分/年份/类型
 *   → 播放按钮 + 剧情简介
 *   → 季切换（仅剧集）
 *   → 分集网格
 *
 * TVBox 来源有个特殊点：列表接口不含播放地址，必须进详情时才请求 ac=detail。
 * 因此首次进入会先拉一次详情，这段时间显示加载态。
 */
@Composable
fun DetailScreen(
    vm: LibraryViewModel,
    mediaId: Long,
    isTv: Boolean,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit
) {
    var media by remember { mutableStateOf<MediaEntity?>(null) }
    var episodes by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedSeason by remember { mutableStateOf(0) }

    LaunchedEffect(mediaId) {
        loading = true
        val m = vm.mediaOf(mediaId)
        media = m

        if (m != null && m.kind == MediaKind.SERIES) {
            var eps = vm.episodesOf(m.seriesKey)

            // TVBox：分集需要按需拉取
            if (eps.isEmpty()) {
                val src = vm.sourceOf(m.sourceId)
                if (src != null && src.kind == SourceKind.TVBOX) {
                    vm.loadTvBoxDetail(m.sourceId, m.remoteId)
                    eps = vm.episodesOf(m.seriesKey)
                    media = vm.mediaOf(mediaId) ?: m
                }
            }

            episodes = eps
            selectedSeason = eps.firstOrNull()?.season ?: 1
        }
        loading = false
    }

    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val visibleEpisodes = remember(episodes, selectedSeason) {
        if (seasons.size <= 1) episodes else episodes.filter { it.season == selectedSeason }
    }

    Box(Modifier.fillMaxSize().background(AppColors.Bg)) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Brand)
            }
        } else if (media == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("条目不存在", color = AppColors.TextSecondary)
            }
        } else {
            val item = media!!
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // ---------- 头图 ----------
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (isTv) 340.dp else 300.dp)
                    ) {
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

                        // 渐变沉入底色
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.4f),
                                        0.6f to Color.Black.copy(alpha = 0.6f),
                                        1f to AppColors.Bg
                                    )
                                )
                        )

                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }

                        // 海报 + 文字信息
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 18.dp)
                                .padding(bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val posterShape = RoundedCornerShape(10.dp)
                            Box(
                                Modifier
                                    .width(if (isTv) 116.dp else 96.dp)
                                    .height(if (isTv) 174.dp else 144.dp)
                                    .clip(posterShape)
                                    .background(AppColors.Surface)
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), posterShape)
                            ) {
                                if (item.posterUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = item.posterUrl,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val meta = detailMeta(item)
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AppColors.TextSecondary
                                    )
                                }
                                if (item.genres.isNotBlank()) {
                                    Text(
                                        text = item.genres,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.TextTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------- 播放按钮 ----------
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .padding(top = 4.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val target = playTarget(item, visibleEpisodes)
                        Button(
                            onClick = { if (target != null) onPlay(target.id) },
                            enabled = target != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Brand,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (item.kind == MediaKind.SERIES) {
                                    val ep = visibleEpisodes.firstOrNull { it.positionMs > 0 }
                                        ?: visibleEpisodes.firstOrNull()
                                    ep?.let { "继续观看  ${it.title}" } ?: "开始播放"
                                } else "播放"
                            )
                        }
                    }
                }

                // ---------- 简介 ----------
                if (item.overview.isNotBlank()) {
                    item {
                        Column(Modifier.padding(horizontal = 18.dp)) {
                            Text(
                                "简介",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                item.overview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                }

                // ---------- 季切换 ----------
                if (seasons.size > 1) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons) { s ->
                                FilterChip(
                                    selected = selectedSeason == s,
                                    onClick = { selectedSeason = s },
                                    label = { Text("第 $s 季") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AppColors.Brand,
                                        selectedLabelColor = Color.White,
                                        containerColor = AppColors.SurfaceHi,
                                        labelColor = AppColors.TextSecondary
                                    )
                                )
                            }
                        }
                    }
                }

                // ---------- 分集 ----------
                if (item.kind == MediaKind.SERIES) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .padding(top = 14.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "剧集",
                                style = MaterialTheme.typography.titleLarge,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${visibleEpisodes.size} 集",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary
                            )
                        }
                    }

                    if (visibleEpisodes.isEmpty()) {
                        item {
                            Text(
                                "暂无分集，可能是来源接口未返回播放地址",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextTertiary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        items(visibleEpisodes.chunked(if (isTv) 4 else 2)) { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                row.forEach { ep ->
                                    Box(Modifier.weight(1f)) {
                                        EpisodeCard(
                                            episode = ep,
                                            onClick = { onPlay(ep.id) }
                                        )
                                    }
                                }
                                repeat((if (isTv) 4 else 2) - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 决定「播放」按钮跳哪一集：优先未看完的，其次第一集 */
private fun playTarget(item: MediaEntity, episodes: List<MediaEntity>): MediaEntity? =
    if (item.kind == MediaKind.SERIES) {
        episodes.firstOrNull { it.positionMs > 0 } ?: episodes.firstOrNull()
    } else item

private fun detailMeta(item: MediaEntity): String {
    val parts = mutableListOf<String>()
    if (item.year > 0) parts += item.year.toString()
    if (item.kind == MediaKind.SERIES) parts += "剧集"
    if (item.rating > 0) parts += "★ %.1f".format(item.rating)
    return parts.joinToString("  ·  ")
}

@Composable
private fun EpisodeCard(episode: MediaEntity, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val shape = RoundedCornerShape(10.dp)
    val playing = episode.positionMs > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.Surface)
            .then(if (focused) Modifier.border(2.dp, AppColors.Brand, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable()
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (episode.episode > 0) "E%02d".format(episode.episode) else "•",
            style = MaterialTheme.typography.labelMedium,
            color = if (playing) AppColors.Brand else AppColors.TextTertiary
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (playing && episode.durationMs > 0) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                (episode.positionMs.toFloat() / episode.durationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            )
                            .height(2.5.dp)
                            .background(AppColors.Brand)
                    )
                }
            }
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}
