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

    /** 海报主色：驱动整页背景渐变，营造沉浸感 */
    var dominantColor by remember { mutableStateOf(Color.Unspecified) }

    LaunchedEffect(mediaId) {
        loading = true
        val m = vm.mediaOf(mediaId)
        media = m

        if (m != null) {
            // Emby 源：加载刮削信息（电影/剧集都支持）
            if (vm.sourceOf(m.sourceId)?.kind == SourceKind.EMBY) {
                scraped = vm.embyDetailOf(m.sourceId, m.remoteId)
            }

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
    val pageColor = remember(dominantColor) {
        if (dominantColor == Color.Unspecified) AppColors.Bg else toneForPage(dominantColor)
    }
    val animatedPageColor by animateColorAsState(
        targetValue = pageColor,
        animationSpec = tween(650),
        label = "page_color"
    )

    // 顶部亮、往下沉黑：整块是实色，滚动时不会再露出纯黑
    val bgBrush = remember(animatedPageColor) {
        Brush.verticalGradient(
            0f to animatedPageColor,
            0.55f to animatedPageColor,
            1f to lerp(animatedPageColor, Color.Black, 0.55f)
        )
    }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        // 封面加载完成后在后台提取聚焦色（缩略图采样，开销很小）
        val scope = rememberCoroutineScope()
        val extract: (coil.compose.AsyncImagePainter.State.Success) -> Unit = remember {
            { state ->
                scope.launch {
                    val color = withContext(Dispatchers.Default) {
                        runCatching {
                            focusColorOf(state.result.drawable.toBitmap(160, 160))
                        }.getOrNull()
                    }
                    if (color != null && color != Color.Unspecified) dominantColor = color
                }
            }
        }
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
                                modifier = Modifier.fillMaxSize(),
                                onSuccess = { state ->
                                    // 头图先给一个基调，海报加载完会覆盖成更准确的聚焦色
                                    if (dominantColor == Color.Unspecified) extract(state)
                                }
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(AppColors.Surface))
                        }

                        // 头图底部渐变到整页底色：与页面背景同色，接缝完全消失
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.45f),
                                        0.55f to Color.Black.copy(alpha = 0.25f),
                                        1f to animatedPageColor
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
                                        modifier = Modifier.fillMaxSize(),
                                        onSuccess = { state -> extract(state) }
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

                // ---------- 刮削信息（导演/演员/分级/工作室） ----------
                scraped?.let { detail ->
                    item {
                        Column(
                            Modifier
                                .padding(horizontal = 18.dp)
                                .padding(top = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 标语
                            if (detail.tagline.isNotBlank()) {
                                Text(
                                    "“${detail.tagline}”",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextSecondary,
                                    fontStyle = FontStyle.Italic
                                )
                            }

                            // 分级 + 工作室
                            val chips = buildList {
                                if (detail.officialRating.isNotBlank()) add(detail.officialRating)
                                addAll(detail.studios.take(2))
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

                            // 导演
                            if (detail.directors.isNotEmpty()) {
                                Text(
                                    "导演  " + detail.directors.joinToString("  ") { it.name },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AppColors.TextSecondary
                                )
                            }

                            // 演员横滑
                            if (detail.actors.isNotEmpty()) {
                                Text(
                                    "主演",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AppColors.TextPrimary
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(top = 4.dp)
                                ) {
                                    items(detail.actors, key = { it.name + it.role }) { person ->
                                        CastCard(person)
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

/**
 * 从封面提取"聚焦色"。
 *
 * 优先取饱和度较高的色块（vibrant/lightVibrant/muted/...），
 * 并给饱和度、亮度设下限，避免暗色海报提取出来仍然是黑色。
 * 灰度封面提取不到色相时退回品牌色，保证页面总有色调。
 */
private fun focusColorOf(bitmap: Bitmap): Color {
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

/** 整页底色：保留聚焦色的色相，压暗到适合大面积铺底的明度 */
private fun toneForPage(focus: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(focus.toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.95f).coerceIn(0.32f, 0.78f)
    hsv[2] = 0.30f
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
    if (n == 0L) return android.graphics.Color.DKGRAY
    return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

private fun detailMeta(item: MediaEntity): String {
    val parts = mutableListOf<String>()
    if (item.year > 0) parts += item.year.toString()
    if (item.kind == MediaKind.SERIES) parts += "剧集"
    if (item.rating > 0) parts += "★ %.1f".format(item.rating)
    return parts.joinToString("  ·  ")
}

/** 演员卡：圆形头像 + 姓名 + 角色 */
@Composable
private fun CastCard(person: EmbyClient.Person) {
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
