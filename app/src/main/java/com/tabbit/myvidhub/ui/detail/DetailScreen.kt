package com.tabbit.myvidhub.ui.detail

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.tabbit.myvidhub.data.emby.EmbyClient
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Emby 刮削信息（导演/演员/分级/工作室） */
    var scraped by remember { mutableStateOf<EmbyClient.ItemDetail?>(null) }

    /** TMDB 刮削信息（海报/背景图/Logo/评分/简介/演职员），作为 Emby 的补充 */
    var tmdb by remember { mutableStateOf<com.tabbit.myvidhub.data.tmdb.TmdbClient.ScrapeResult?>(null) }

    /** 海报主色：驱动整页背景渐变，营造沉浸感 */
    var dominantColor by remember { mutableStateOf(Color.Unspecified) }

    LaunchedEffect(mediaId) {
        loading = true
        scraped = null
        tmdb = null
        val m = vm.mediaOf(mediaId)
        media = m

        if (m != null) {
            // Emby 源：加载刮削信息（电影/剧集都支持）
            if (vm.sourceOf(m.sourceId)?.kind == SourceKind.EMBY) {
                scraped = vm.embyDetailOf(m.sourceId, m.remoteId)
            }

            // TMDB 刮削：补充海报/Logo/评分/演职员，所有来源都尝试
            runCatching {
                vm.tmdbScrape(m.title, m.year, m.kind)
            }.getOrNull()?.let { tmdb = it }

            if (m.kind == MediaKind.SERIES) {
                var eps = vm.episodesOf(m.seriesKey)

                // 分集按需拉取（TVBox 详情 / Emby 分集都不随整库同步）
                if (eps.isEmpty()) {
                    val src = vm.sourceOf(m.sourceId)
                    when (src?.kind) {
                        SourceKind.TVBOX -> {
                            vm.loadTvBoxDetail(m.sourceId, m.remoteId)
                            eps = vm.episodesOf(m.seriesKey)
                            media = vm.mediaOf(mediaId) ?: m
                        }
                        SourceKind.EMBY -> {
                            if (vm.loadEmbyEpisodes(m.sourceId, m.remoteId)) {
                                eps = vm.episodesOf(m.seriesKey)
                                media = vm.mediaOf(mediaId) ?: m
                            }
                        }
                        null -> Unit
                    }
                }

                episodes = eps
                selectedSeason = eps.firstOrNull()?.season ?: 1
            }
        }
        loading = false
    }

    // 取色：用 Coil ImageLoader 直接解码海报 bitmap（比 AsyncImage.onSuccess 里取 drawable 更可靠）。
    // 任何环节失败都回退到「标题哈希色」，保证页面背景永远有色、不会是纯黑。
    val context = LocalContext.current
    LaunchedEffect(media?.posterUrl, media?.backdropUrl) {
        val m = media ?: return@LaunchedEffect
        val url = m.posterUrl.ifBlank { m.backdropUrl }
        if (url.isBlank()) {
            dominantColor = fallbackColorFromTitle(m.title)
            return@LaunchedEffect
        }
        val cached = FocusColorCache.get(url)
        if (cached != null) {
            dominantColor = cached
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.Default) { loadPosterBitmap(context, url) }
        val color = bmp?.let { runCatching { focusColorOf(it) }.getOrNull() }
            ?: fallbackColorFromTitle(m.title)
        FocusColorCache.put(url, color)
        dominantColor = color
    }

    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val visibleEpisodes = remember(episodes, selectedSeason) {
        if (seasons.size <= 1) episodes else episodes.filter { it.season == selectedSeason }
    }

    /**
     * 从封面图提取"聚焦色"，并把整页底色压成它的深色版本。
     *
     * 之前的实现只用了 Palette 的 darkMuted 并给很低的 alpha，暗色海报基本等于纯黑；
     * 这里改成：优先取有饱和度的色块 → 保证亮度/饱和度下限 → 整页用不透明深色铺满，
     * 所以无论海报明暗，页面都会带上它自己的色调。
     */
    val pageTones = remember(dominantColor) {
        if (dominantColor == Color.Unspecified) {
            AppColors.Bg to lerp(AppColors.Bg, Color.Black, 0.55f)
        } else {
            pageTonesOf(dominantColor)
        }
    }
    val animatedTop by animateColorAsState(
        targetValue = pageTones.first,
        animationSpec = tween(700),
        label = "page_top"
    )
    val animatedBottom by animateColorAsState(
        targetValue = pageTones.second,
        animationSpec = tween(700),
        label = "page_bottom"
    )

    // 整页填充色 = 封面聚焦色：顶部色相最浓，向下同色相压暗，滚动到底也不会露出纯黑断层
    val bgBrush = remember(animatedTop, animatedBottom) {
        Brush.verticalGradient(
            0f to animatedTop,
            0.30f to animatedTop,
            0.68f to lerp(animatedTop, animatedBottom, 0.72f),
            1f to animatedBottom
        )
    }

    // 封面 → 填充色的渐变过渡：头图下方 45% 区间由封面逐渐溶解成整页填充色，两者之间没有硬边
    val headerScrim = remember(animatedTop) {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.34f),
            0.26f to Color.Transparent,
            0.52f to animatedTop.copy(alpha = 0.30f),
            0.76f to animatedTop.copy(alpha = 0.78f),
            1f to animatedTop
        )
    }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
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
            // 合并 Emby / TMDB 刮削结果：优先用条目本身字段，空了再用 TMDB 补齐
            val effectiveLogo = item.logoUrl.ifBlank { tmdb?.logoUrl.orEmpty() }
            val effectivePoster = item.posterUrl.ifBlank { tmdb?.posterUrl.orEmpty() }
            val effectiveOverview = item.overview.ifBlank { tmdb?.overview.orEmpty() }
            val effectiveRating = if (item.rating > 0) item.rating else tmdb?.rating ?: 0.0
            val effectiveGenres = item.genres.ifBlank { tmdb?.genres.orEmpty() }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // ---------- 顶部海报（海报即头图，下方直接接影片信息） ----------
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (isTv) 360.dp else 320.dp)
                    ) {
                        val art = effectivePoster.ifBlank { item.backdropUrl.ifBlank { tmdb?.backdropUrl.orEmpty() } }
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

                        // 海报底部 → 页面主色的渐变过渡：从海报色自然晕染到整页填充色，无硬边
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(headerScrim)
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
                    }
                }

                // ---------- 标题 + 元信息（海报下方） ----------
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .padding(top = 6.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 标题：优先用刮削到的 Logo 图，文字次之
                        if (effectiveLogo.isNotBlank()) {
                            AsyncImage(
                                model = effectiveLogo,
                                contentDescription = item.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .height(56.dp)
                                    .widthIn(max = 320.dp)
                            )
                        } else {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        val meta = detailMeta(item, effectiveRating)
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelMedium,
                                color = AppColors.TextSecondary
                            )
                        }
                        if (effectiveGenres.isNotBlank()) {
                            Text(
                                text = effectiveGenres,
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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

                        val fav = item.favorite
                        IconButton(
                            onClick = {
                                val next = !fav
                                vm.toggleFavorite(item.id, next)
                                media = media?.copy(favorite = next)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppColors.SurfaceHi)
                        ) {
                            Icon(
                                if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (fav) "取消收藏" else "收藏",
                                tint = if (fav) AppColors.Brand else AppColors.TextSecondary
                            )
                        }
                    }
                }

                // ---------- 简介 ----------
                if (effectiveOverview.isNotBlank()) {
                    item {
                        Column(Modifier.padding(horizontal = 18.dp)) {
                            Text(
                                "简介",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                effectiveOverview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                }

                // ---------- 刮削信息（标语/分级/工作室/导演/演员）：Emby 优先，TMDB 补齐 ----------
                run {
                    val tagline = scraped?.tagline.orEmpty().ifBlank { tmdb?.tagline.orEmpty() }
                    val chips = buildList {
                        scraped?.let {
                            if (it.officialRating.isNotBlank()) add(it.officialRating)
                            addAll(it.studios.take(2))
                        }
                    }
                    val directors = (scraped?.directors?.map { it.toCast() }
                        ?: tmdb?.directors?.map { it.toCast() }.orEmpty())
                    val actors = (scraped?.actors?.map { it.toCast() }
                        ?: tmdb?.actors?.map { it.toCast() }.orEmpty())

                    if (tagline.isNotBlank() || chips.isNotEmpty() || directors.isNotEmpty() || actors.isNotEmpty()) {
                        item {
                            Column(
                                Modifier
                                    .padding(horizontal = 18.dp)
                                    .padding(top = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (tagline.isNotBlank()) {
                                    Text(
                                        "“$tagline”",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AppColors.TextSecondary,
                                        fontStyle = FontStyle.Italic
                                    )
                                }

                                if (chips.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        chips.forEach { label ->
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(AppColors.SurfaceHi)
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = AppColors.TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }

                                if (directors.isNotEmpty()) {
                                    Text(
                                        "导演  " + directors.joinToString("  ") { it.name },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AppColors.TextSecondary
                                    )
                                }

                                if (actors.isNotEmpty()) {
                                    Text(
                                        "主演",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = AppColors.TextPrimary
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(top = 4.dp)
                                    ) {
                                        items(actors, key = { it.name + it.role }) { person ->
                                            CastCard(person)
                                        }
                                    }
                                }
                            }
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

/** 统一演职员展示类型，抹平 EmbyClient.Person / TmdbClient.Person 的差异 */
private data class CastPerson(val name: String, val role: String, val imageUrl: String)

private fun EmbyClient.Person.toCast() = CastPerson(name, role, imageUrl)
private fun com.tabbit.myvidhub.data.tmdb.TmdbClient.Person.toCast() = CastPerson(name, role, imageUrl)

private fun detailMeta(item: MediaEntity, rating: Double = item.rating): String {
    val parts = mutableListOf<String>()
    if (item.year > 0) parts += item.year.toString()
    if (item.kind == MediaKind.SERIES) parts += "剧集"
    if (rating > 0) parts += "★ %.1f".format(rating)
    return parts.joinToString("  ·  ")
}

/** 演员卡：圆形头像 + 姓名 + 角色 */
@Composable
private fun CastCard(person: CastPerson) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(AppColors.SurfaceHi),
            contentAlignment = Alignment.Center
        ) {
            if (person.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = person.imageUrl,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    person.name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextSecondary
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            person.name,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (person.role.isNotBlank()) {
            Text(
                person.role,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
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
