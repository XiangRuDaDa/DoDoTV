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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.scrape.ScrapeEngine
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy
import com.tabbit.myvidhub.ui.components.FormatBadgesRow
import com.tabbit.myvidhub.ui.components.TmdbCreditLine
import androidx.compose.runtime.collectAsState

/**
 * 电视端简介页（独立实现，不复用手机端 DetailScreen）。
 *
 * 结构差异来自观看距离：
 *   · 左侧大海报 + 右侧文案的两栏布局，替代手机端「上下堆叠」；
 *   · 操作按钮 2~3 个大按钮横排，遥控器左右即可选中，不做长列表；
 *   · 分集改为网格卡片（6 列 × 4 行 = 每页 24 集），可按季切换、可翻页：
 *     几百集的剧横排滚动要按几百次右键，网格既能方向键跳着走，
 *     又能一屏看到 24 集的已看进度，找「看到哪了」比手机端还快。
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

    /**
     * TMDB 刮削结果。
     *
     * 这个页面之前完全没有接 TMDB，所以「刮削前 / 刮削后」看起来一模一样 ——
     * 手机端有海报、艺术字 Logo、评分、简介、演职员，电视端却只有 Emby 的基础字段。
     * 这里和手机端 DetailScreen 走同一条链路（同样的策略判定 + 新鲜期判断），
     * 保证两端刮削结果一致。
     */
    var tmdb by remember { mutableStateOf<com.tabbit.myvidhub.data.tmdb.TmdbClient.ScrapeResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    /** 分季 + 分页：几百集的剧直接一屏铺满很难找，先按季过滤再按页翻 */
    var selectedSeason by remember { mutableStateOf(0) }
    var epPage by remember { mutableStateOf(0) }

    // 首帧就用「上次算好的主色 / 内存缓存 / 标题哈希色」，避免进入瞬间一片黑
    // 同手机端：mediaOf() 是 suspend，组合期不能调，改从已订阅的 library 同步取，
    // 并把 remember 的 key 挂到标题上，库数据晚到时自动重新给初值
    val libraryState by vm.library.collectAsState()
    val seedMedia = remember(libraryState, mediaId) {
        libraryState.firstOrNull { it.id == mediaId }
    }
    var dominant by remember(mediaId, seedMedia?.title) {
        mutableStateOf(
            seedMedia?.let { seed ->
                val persisted = Prefs.detailTone(seed.title).takeIf { it != 0 }
                if (persisted != null) Color(persisted)
                else FocusColorCache.get(seed.posterUrl.ifBlank { seed.backdropUrl })
                    ?: fallbackColorFromTitle(seed.title)
            } ?: AppColors.Bg
        )
    }

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
            episodes.firstOrNull()?.season?.let { if (selectedSeason == 0) selectedSeason = it }

            // TMDB 刮削：与手机端同一套判定（凭据存在 + 未被近期刮过）
            if (ScrapeStrategy.refresh() == ScrapeEngine.TMDB) {
                val fresh = latest.scrapedAt > 0L &&
                    System.currentTimeMillis() - latest.scrapedAt < TV_SCRAPE_FRESH_MS
                val localReady = latest.posterUrl.isNotBlank() || latest.logoUrl.isNotBlank()
                if (!(fresh && localReady)) {
                    tmdb = runCatching {
                        vm.tmdbScrape(latest.title, latest.year, latest.kind, mediaId)
                    }.getOrNull()
                    // 刮削会把海报/Logo 回填进库，取回最新的再渲染
                    vm.mediaOf(mediaId)?.let { media = it }
                }
            }
        }
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            loading = false
        }
    }

    // 取色：与手机端一致，用 Coil ImageLoader 直接解码 bitmap，失败回退标题哈希色
    val tvContext = LocalContext.current
    // 依赖里带上 tmdb?.posterUrl：刮削完成后海报换了，主色要跟着重算
    LaunchedEffect(media?.posterUrl, media?.backdropUrl, tmdb?.posterUrl) {
        val m = media ?: return@LaunchedEffect
        val url = tmdb?.posterUrl.orEmpty().ifBlank {
            m.posterUrl.ifBlank { m.backdropUrl }
        }.ifBlank {
            m.backdropUrl
        }
        if (url.isBlank()) {
            dominant = fallbackColorFromTitle(m.title)
            return@LaunchedEffect
        }
        val cached = FocusColorCache.get(url)
        if (cached != null) {
            dominant = cached
            Prefs.putDetailTone(m.title, cached.toArgb())
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.Default) { loadPosterBitmap(tvContext, url) }
        val color = bmp?.let { runCatching { focusColorOf(it) }.getOrNull() }
            ?: fallbackColorFromTitle(m.title)
        FocusColorCache.put(url, color)
        Prefs.putDetailTone(m.title, color.toArgb())
        dominant = color
    }

    val item = media

    // ---------- 分集：按季过滤 + 按页切块 ----------
    val tvSeasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val seasonEpisodes = remember(episodes, selectedSeason, tvSeasons) {
        if (tvSeasons.size <= 1) episodes else episodes.filter { it.season == selectedSeason }
    }
    val totalPages = remember(seasonEpisodes) {
        ((seasonEpisodes.size + TV_EPS_PAGE - 1) / TV_EPS_PAGE).coerceAtLeast(1)
    }
    // 换季时页码可能越界，这里钳一下，避免出现空白页
    val pageIndex = epPage.coerceIn(0, totalPages - 1)
    val pagedEpisodes = remember(seasonEpisodes, pageIndex) {
        seasonEpisodes.drop(pageIndex * TV_EPS_PAGE).take(TV_EPS_PAGE)
    }

    val tones = remember(dominant) {
        if (dominant == Color.Unspecified) {
            AppColors.Bg to lerp(AppColors.Bg, Color.Black, 0.55f)
        } else {
            pageTonesOf(dominant)
        }
    }
    val top by animateColorAsState(tones.first, tween(320), label = "tv_top")
    val bottom by animateColorAsState(tones.second, tween(320), label = "tv_bottom")

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

    val backdrop = tmdb?.backdropUrl.orEmpty().ifBlank {
        item?.let { it.backdropUrl.ifBlank { it.posterUrl } }.orEmpty()
    }

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
                            // 刮削结果优先：TMDB 的海报质量和尺寸都远好于本地抓的
                            model = tmdb?.posterUrl.orEmpty().ifBlank {
                                item.posterUrl.ifBlank { item.backdropUrl }
                            },
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

                            // 第三行：格式徽章（TMDB 图标 + Emby 解析到的 分辨率/HDR/Codec）
                            FormatBadgesRow(
                                tmdbEnabled = tmdb != null,
                                resolution = scraped?.videoResolution.orEmpty(),
                                hdr = scraped?.hdrType.orEmpty(),
                                codec = scraped?.videoCodec.orEmpty()
                            )
                            Spacer(Modifier.height(16.dp))

                            // 优先用刮削文案，其次回落本地记录
                            // TMDB 简介是完整剧情，比本地 tagline 信息量大得多
                            val overview = tmdb?.overview?.takeIf { it.isNotBlank() }
                                ?: scraped?.tagline?.takeIf { it.isNotBlank() }
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

                            val directors = (tmdb?.directors?.takeIf { it.isNotEmpty() }?.map { it.name }
                                ?: scraped?.directors?.map { it.name })
                                ?.joinToString(" / ").orEmpty()
                            if (directors.isNotBlank()) {
                                TvInfoLine("导演", directors)
                            }
                            val actors = (tmdb?.actors?.takeIf { it.isNotEmpty() }?.map { it.name }
                                ?: scraped?.actors?.map { it.name })
                                ?.take(6)?.joinToString(" / ").orEmpty()
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

                            // 用了 TMDB 数据就必须署名（其使用条款要求）
                            if (tmdb != null) {
                                Spacer(Modifier.height(18.dp))
                                TmdbCreditLine()
                            }
                        }
                    }
                }
            }

            // ---------- 分集（网格 + 分季 + 翻页）----------
            if (episodes.isNotEmpty()) {
                item {
                    TvSectionTitle(
                        text = if (tvSeasons.size > 1) {
                            "选集（第 $selectedSeason 季 · 共 ${seasonEpisodes.size} 集）"
                        } else {
                            "选集（共 ${episodes.size} 集）"
                        }
                    ) {
                        // 右上角：季切换 + 翻页，几百集不用一路横划到底
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (tvSeasons.size > 1) {
                                tvSeasons.forEach { sn ->
                                    TvPillButton(
                                        text = if (sn > 0) "第 $sn 季" else "正片",
                                        primary = sn == selectedSeason,
                                        onClick = {
                                            selectedSeason = sn
                                            epPage = 0
                                        }
                                    )
                                }
                            }
                            if (totalPages > 1) {
                                TvPillButton(
                                    text = "‹ 上一页",
                                    primary = false,
                                    onClick = { if (pageIndex > 0) epPage = pageIndex - 1 }
                                )
                                Text(
                                    "${pageIndex + 1} / $totalPages",
                                    color = Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TvPillButton(
                                    text = "下一页 ›",
                                    primary = false,
                                    onClick = { if (pageIndex < totalPages - 1) epPage = pageIndex + 1 }
                                )
                            }
                        }
                    }
                }
                // 网格用 chunked 手动分行：LazyVerticalGrid 嵌在 LazyColumn 里会因为
                // 双向无限高度约束直接崩，分行 Row 是最稳的做法
                pagedEpisodes.chunked(TV_GRID_COLS).forEachIndexed { rowIdx, rowEps ->
                    item(key = "ep_row_${pageIndex}_$rowIdx") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 44.dp, end = 44.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowEps.forEach { ep ->
                                TvEpisodeCard(
                                    episode = ep,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onPlay(ep.id) }
                                )
                            }
                            // 最后一行不满时补空位，避免卡片被拉伸成超宽
                            repeat(TV_GRID_COLS - rowEps.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        // 遥控器 Back 键 = TvApp.BackHandler 统一处理，不再显示页面内返回按钮
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

/** 每页显示的分集数：电视端一行 6 个、共 4 行，一屏看完，不用横向滑 */
private const val TV_EPS_PAGE = 24

/** 分集网格列数 */
private const val TV_GRID_COLS = 6

/** 本地刮削结果的新鲜期：一周内不重复请求 TMDB */
private const val TV_SCRAPE_FRESH_MS = 7L * 24 * 60 * 60 * 1000

/**
 * TV 端分集卡片。
 *
 * 为什么不用原来的胶囊按钮：几百集的剧横排滚动，遥控器要按几百次右键才能翻到中间，
 * 而且看不到已看进度。改成卡片后可以按网格方向键移动，一屏能看到 24 集，
 * 已看的集数带进度条，一眼就能找到接到哪里。
 */
@Composable
private fun TvEpisodeCard(
    episode: MediaEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(140), label = "ep_card")

    val label = if (episode.episode > 0) {
        if (episode.season > 0) "S${episode.season}E${episode.episode}" else "第 ${episode.episode} 集"
    } else {
        episode.title
    }
    val watched = episode.positionMs > 5_000L
    val progress = if (episode.durationMs > 0L) {
        (episode.positionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (watched) AppColors.Brand.copy(alpha = 0.18f) else AppColors.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AppColors.Brand else AppColors.SurfaceHi,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            color = if (watched) AppColors.BrandSoft else AppColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        // 已看进度：一眼能看出这部剧看到哪了
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppColors.SurfaceHi)
        ) {
            if (progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(AppColors.Brand)
                )
            }
        }
    }
}
