package com.tabbit.myvidhub.ui.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.components.ContinueCard
import com.tabbit.myvidhub.ui.components.HeroBanner
import com.tabbit.myvidhub.ui.components.PosterCard
import com.tabbit.myvidhub.ui.components.SectionTitle
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 首页（VidHub 形态）：
 *   精选大图轮播 → 继续观看横滑 → 电影 → 剧集
 *
 * 海报墙用「按列切块 + Row」而不是嵌套 LazyVerticalGrid：
 * LazyColumn 内嵌 LazyVerticalGrid 会因高度无界而崩，切块是标准解法。
 */
@Composable
fun HomeScreen(
    vm: LibraryViewModel,
    isTv: Boolean,
    onOpenDetail: (Long) -> Unit,
    onPlay: (MediaEntity) -> Unit
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val continueList by vm.continueWatching.collectAsStateWithLifecycle()
    val featured by vm.featured.collectAsStateWithLifecycle()

    val columns = if (isTv) 6 else 3
    val hSpacing = if (isTv) 14.dp else 9.dp

    val movies = library.filter { it.kind == MediaKind.MOVIE }
    val series = library.filter { it.kind == MediaKind.SERIES }

    // 优先用有背景图的条目做精选，不足时用有海报的补
    val heroItems = remember(featured, library) {
        val primary = featured.filter { it.backdropUrl.isNotBlank() }
        if (primary.size >= 3) primary.take(6)
        else (primary + library.filter { it.posterUrl.isNotBlank() }).distinctBy { it.id }.take(6)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        // ---------- 顶栏 ----------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MyVidHub",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (vm.scanning) vm.scanMessage.ifBlank { "同步中…" }
                        else "${library.size} 个条目 · ${vm.sources.value.size} 个来源",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
                if (vm.scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp).width(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = { vm.scanAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "同步", tint = AppColors.TextSecondary)
                    }
                }
            }
        }

        // ---------- 精选大图 ----------
        if (heroItems.isNotEmpty()) {
            item {
                HeroBanner(
                    items = heroItems,
                    isTv = isTv,
                    onOpen = { onOpenDetail(it.id) },
                    onPlay = { onPlay(it) }
                )
            }
        } else if (library.isEmpty()) {
            item { EmptyState(vm) }
        }

        // ---------- 继续观看 ----------
        if (continueList.isNotEmpty()) {
            item { SectionTitle("继续观看") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(continueList, key = { "cw_${it.id}" }) { item ->
                        ContinueCard(item = item, onClick = { onPlay(item) })
                    }
                }
            }
        }

        // ---------- 电影 ----------
        if (movies.isNotEmpty()) {
            item { SectionTitle("电影", trailing = { CountBadge(movies.size) }) }
            items(movies.chunked(columns)) { row ->
                PosterRow(
                    row = row,
                    columns = columns,
                    hSpacing = hSpacing,
                    onOpenDetail = onOpenDetail
                )
            }
        }

        // ---------- 剧集 ----------
        if (series.isNotEmpty()) {
            item { SectionTitle("剧集", trailing = { CountBadge(series.size) }) }
            items(series.chunked(columns)) { row ->
                PosterRow(
                    row = row,
                    columns = columns,
                    hSpacing = hSpacing,
                    onOpenDetail = onOpenDetail
                )
            }
        }
    }
}

@Composable
private fun PosterRow(
    row: List<MediaEntity>,
    columns: Int,
    hSpacing: androidx.compose.ui.unit.Dp,
    onOpenDetail: (Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = if (hSpacing > 10.dp) 8.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(hSpacing)
    ) {
        row.forEach { item ->
            Box(Modifier.weight(1f)) {
                PosterCard(
                    item = item,
                    onClick = { onOpenDetail(item.id) }
                )
            }
        }
        // 补齐空位，避免最后一行卡片被拉伸
        repeat(columns - row.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Text(
        text = "$count",
        style = MaterialTheme.typography.labelSmall,
        color = AppColors.TextTertiary
    )
}

@Composable
private fun EmptyState(vm: LibraryViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "还没有内容",
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary
        )
        Text(
            "到「来源」页添加一个 Emby 服务器或 TVBox 接口，\n回来后点右上角同步按钮",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextTertiary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "当前 ${vm.sources.value.size} 个来源",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary
        )
    }
}
