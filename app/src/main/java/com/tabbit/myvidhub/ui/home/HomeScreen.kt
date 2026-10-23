package com.tabbit.myvidhub.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.components.ContinueCard
import com.tabbit.myvidhub.ui.components.HeroBanner
import com.tabbit.myvidhub.ui.components.PosterCard
import com.tabbit.myvidhub.ui.components.SectionTitle
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页（VidHub 形态）：
 *   精选大图轮播 → 继续观看 → 收藏 → [动态分区]
 *
 * 懒加载策略：每个分类只展示最新 10 条（横向滑动画廊，条数由 ViewModel 控制），
 * 点「更多」进入分类浏览页再按类型/题材分页加载整库，
 * 避免一次性把几百上千个条目全部刷进首页。
 *
 * 顶栏（软件名/刷新/搜索）悬浮在轮播图之上，下滑时平滑淡出，
 * 顶到轮播图顶部时再淡入。
 */
@Composable
fun HomeScreen(
    vm: LibraryViewModel,
    isTv: Boolean,
    onOpenSearch: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenGenre: (kind: String?, genre: String?) -> Unit,
    onPlay: (MediaEntity) -> Unit
) {
    val continueList by vm.continueWatching.collectAsStateWithLifecycle()
    val featured by vm.featured.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    val cardWidth = if (isTv) Prefs.posterSize.tvDp.dp else Prefs.posterSize.mobileDp.dp

    // 优先用有背景图的条目做精选，不足时用有海报的补
    val heroItems = remember(featured, library) {
        val primary = featured.filter { it.backdropUrl.isNotBlank() }
        if (primary.size >= 3) primary.take(6)
        else (primary + library.filter { it.posterUrl.isNotBlank() }).distinctBy { it.id }.take(6)
    }

    val listState = rememberLazyListState()
    // 软件名淡出距离：轮播图高度的 ~55%
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val heroCollapseDist = (configuration.screenWidthDp * 0.55f).toInt()
    // 注意：alpha 计算放在 graphicsLayer 的 draw 阶段（下面 Column 里），
    // 不在这里在 composition 层读 firstVisibleItemScrollOffset —— 那会导致每像素滚动都触发整页重组

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to AppColors.Brand.copy(alpha = 0.09f),
                        0.22f to AppColors.Bg,
                        1f to AppColors.Bg
                    )
                ),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            // ---------- 精选大图（置顶，顶栏悬浮其上） ----------
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

            // ---------- 收藏 ----------
            if (favorites.isNotEmpty()) {
                item { SectionTitle("我的收藏", trailing = { CountBadge(favorites.size) }) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(favorites, key = { "fav_${it.id}" }) { item ->
                            Box(Modifier.width(cardWidth)) {
                                PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                            }
                        }
                    }
                }
            }

            // ---------- 动态分区：根据库内实际存在的 kind / genre 渲染 ----------
            DynamicGallerySections(
                library = library,
                cardWidth = cardWidth,
                onMore = onOpenGenre,
                onOpenDetail = onOpenDetail
            )
        }

        // ---------- 透明悬浮顶栏（拆成两段：左=软件名单独淡出；右=工具栏常驻无动画） ----------
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：软件名 + 统计，滚动时淡出（alpha 在 draw 阶段计算，不触发 composition 重组）
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer {
                        // 当第一个可见 item index 越过 hero（index >= 1）时，alpha 永久归零；
                        // 否则按 heroCollapseDist 内的 offset 线性淡出 —— 这样 index 切换瞬间不会闪回
                        val alpha = if (listState.firstVisibleItemIndex > 0) 0f
                        else if (heroCollapseDist == 0) 1f
                        else {
                            val raw = listState.firstVisibleItemScrollOffset.toFloat() / heroCollapseDist
                            (1f - raw).coerceIn(0f, 1f)
                        }
                        this.alpha = alpha
                    }
            ) {
                Text(
                    "DouDouTV",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    if (vm.scanning) vm.scanMessage.ifBlank { "同步中…" }
                    else "${library.size} 个条目 · ${vm.sources.value.size} 个来源",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
            // 右侧：搜索/刷新工具栏，常驻不淡出
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = Color.White)
            }
            if (vm.scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp).width(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                IconButton(onClick = { vm.scanAll() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "同步", tint = Color.White)
                }
            }
        }
    }
}

/**
 * 动态分区：kind 基础分区 + genres 扩展分区。
 *
 * 1. kind 分区：MOVIE → 电影；SERIES → 剧集（只取顶层 kind，EPISODE 不独立分区）
 * 2. genre 分区：从所有条目的 genres 里提取（按英文逗号 / 中文逗号 / 顿号分隔），
 *    每个出现 ≥2 次的 genre 单独一个 section。Emby/TVBox 源的条目 genres 字段里
 *    通常有「动作,科幻,剧情」这样的逗号分隔字符串，拆分后能得到 3~8 个分区，
 *    解决之前只有 2~3 个 kind 分区太单调的问题。
 *
 * 条目可以同时出现在多个 section（一部科幻片既在「电影」又在「科幻」），
 * 这是主流流媒体的做法，用户接受且有利于发现内容。
 */
private const val LAZY_TAKE = 10
private const val MIN_GENRE_ITEMS = 2
private const val MAX_GENRE_SECTIONS = 4

private fun LazyListScope.DynamicGallerySections(
    library: List<MediaEntity>,
    cardWidth: Dp,
    onMore: (kind: String?, genre: String?) -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    if (library.isEmpty()) return

    // 1. kind 基础分区：MOVIE / SERIES / 其他
    val kindBlocks = library
        .filter { it.kind.isNotBlank() && it.kind != MediaKind.EPISODE }
        .groupBy { it.kind }
        .entries
        .sortedBy { (kind, _) ->
            when (kind) {
                MediaKind.MOVIE -> 0
                MediaKind.SERIES -> 1
                else -> 2
            }
        }

    // 2. genre 扩展分区：从所有条目的 genres 拆分
    val genreToItems = mutableMapOf<String, MutableList<MediaEntity>>()
    val genrePattern = Regex("[,，、/\\s]+")
    for (item in library) {
        // distinct() 防止 genres 字段有重复标签（如 "科幻,科幻,动作"）导致同一 item 被加两次
        val parts = item.genres.split(genrePattern).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        for (g in parts) {
            genreToItems.getOrPut(g) { mutableListOf() }.add(item)
        }
    }
    // 过滤数量 < MIN_GENRE_ITEMS、kind 分区已覆盖的 genre（如「电影」genre 映射到 MOVIE kind），再按数量倒序取前 MAX_GENRE_SECTIONS
    val kindAsGenre = setOf("电影", "剧集", "单集")
    val genreBlocks = genreToItems.entries
        .map { (g, items) -> g to items.distinctBy { it.id } }
        .filter { (g, items) -> items.size >= MIN_GENRE_ITEMS && g !in kindAsGenre }
        .sortedByDescending { it.second.size }
        .take(MAX_GENRE_SECTIONS)

    // 先 kind 后 genre
    for ((kind, items) in kindBlocks) {
        val title = when (kind) {
            MediaKind.MOVIE -> "电影"
            MediaKind.SERIES -> "剧集"
            else -> kind
        }
        GallerySection(
            title = title,
            items = items.take(LAZY_TAKE),
            cardWidth = cardWidth,
            onMore = { onMore(kind, null) },
            onOpenDetail = onOpenDetail
        )
    }
    for ((genre, items) in genreBlocks) {
        GallerySection(
            title = genre,
            items = items.take(LAZY_TAKE),
            cardWidth = cardWidth,
            onMore = { onMore(null, genre) },
            onOpenDetail = onOpenDetail
        )
    }
}

/** 横向画廊区块：最新 N 条 + 「更多」入口，超出屏幕左右滑动展示 */
private fun LazyListScope.GallerySection(
    title: String,
    items: List<MediaEntity>,
    cardWidth: Dp,
    onMore: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    if (items.isEmpty()) return
    item { SectionTitle(title, trailing = { MoreLink(onMore) }) }
    item {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(items, key = { "${title}_${it.id}" }) { item ->
                Box(Modifier.width(cardWidth)) {
                    PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                }
            }
        }
    }
}

/** 标题右侧的「更多 ›」入口 */
@Composable
private fun MoreLink(onMore: () -> Unit) {
    Text(
        "更多 ›",
        style = MaterialTheme.typography.labelMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onMore)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
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

// ================= 分类浏览页 =================

/** 分类标签：kind 与 genre 组合定义一类（都为 null = 全部） */
private data class GenreTab(val label: String, val kind: String?, val genre: String?)

private const val GENRE_PAGE_SIZE = 60

/**
 * 分类浏览页：顶部标签横滑，下方纵向海报网格分页加载。
 * 「动漫」等题材与「电影/剧集」类型共用同一套浏览体验。
 */
@Composable
fun GenreScreen(
    vm: LibraryViewModel,
    isTv: Boolean,
    initialKind: String?,
    initialGenre: String?,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    val columns = if (isTv) 6 else 3
    val hSpacing = if (isTv) 14.dp else 9.dp

    var kind by remember { mutableStateOf(initialKind) }
    var genre by remember { mutableStateOf(initialGenre) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        tags = runCatching { vm.genreTags() }.getOrDefault(emptyList())
    }

    LaunchedEffect(kind, genre) {
        loading = true
        items = runCatching { vm.browseGenre(kind, genre, GENRE_PAGE_SIZE, 0) }
            .getOrDefault(emptyList())
        loading = false
    }

    // 标签列表：预设类型 + 库内高频题材
    val tabs = remember(tags) {
        buildList {
            add(GenreTab("全部", null, null))
            add(GenreTab("电影", MediaKind.MOVIE, null))
            add(GenreTab("剧集", MediaKind.SERIES, null))
            add(GenreTab("动漫", null, "动漫"))
            tags.forEach { add(GenreTab(it, null, it)) }
        }
    }
    val currentTab = tabs.firstOrNull { it.kind == kind && it.genre == genre }

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to AppColors.Brand.copy(alpha = 0.09f),
                    0.22f to AppColors.Bg,
                    1f to AppColors.Bg
                )
            )
    ) {
        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppColors.TextSecondary
                )
            }
            Text(
                "分类浏览",
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        // ---------- 标签（渐变分隔线 + 紧凑 filter chip） ----------
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to AppColors.Bg
                    )
                )
                .padding(vertical = 4.dp)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(tabs) { tab ->
                    val selected = currentTab == tab
                    FilterChip(
                        selected = selected,
                        onClick = { kind = tab.kind; genre = tab.genre },
                        label = { Text(tab.label, fontWeight = FontWeight.Medium) },
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

        // ---------- 网格 ----------
        when {
            loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(strokeWidth = 2.dp, color = AppColors.Brand) }

            items.isEmpty() -> Text(
                "该分类下暂无内容",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextTertiary,
                modifier = Modifier.fillMaxWidth().padding(36.dp)
            )

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                item {
                    Text(
                        "共 ${items.size} 条" + if (items.size >= GENRE_PAGE_SIZE) "（持续加载中）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
                items(items.chunked(columns)) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = if (hSpacing > 10.dp) 8.dp else 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(hSpacing)
                    ) {
                        row.forEach { item ->
                            Box(Modifier.weight(1f)) {
                                PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                // 分页加载更多
                if (items.size >= GENRE_PAGE_SIZE) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = AppColors.Brand)
                            } else {
                                Button(
                                    onClick = {
                                        loadingMore = true
                                        scope.launch {
                                            val more = runCatching {
                                                vm.browseGenre(kind, genre, GENRE_PAGE_SIZE, items.size)
                                            }.getOrDefault(emptyList())
                                            // 追加下一页，去重防止同步间隙产生重复
                                            items = items + more.filterNot { next -> items.any { it.id == next.id } }
                                            loadingMore = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.SurfaceHi,
                                        contentColor = AppColors.TextPrimary
                                    )
                                ) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 全局搜索：在已同步的电影/剧集中按标题模糊匹配。
 *
 * 输入防抖 300ms，避免每敲一个字都打一次库。
 */
@Composable
fun SearchScreen(
    vm: LibraryViewModel,
    isTv: Boolean,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        results = vm.searchMedia(q)
        searching = false
    }

    val columns = if (isTv) 6 else 3
    val hSpacing = if (isTv) 14.dp else 9.dp

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppColors.TextSecondary
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索标题…", color = AppColors.TextTertiary) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        when {
            query.isBlank() -> HintText("输入关键字，搜索已同步的电影与剧集")

            searching && results.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(strokeWidth = 2.dp, color = AppColors.Brand) }

            results.isEmpty() -> HintText("没有找到「${query.trim()}」相关内容")

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                item { SectionTitle("搜索结果", trailing = { CountBadge(results.size) }) }
                items(results.chunked(columns)) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = if (hSpacing > 10.dp) 8.dp else 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(hSpacing)
                    ) {
                        row.forEach { item ->
                            Box(Modifier.weight(1f)) {
                                PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextTertiary,
        modifier = Modifier.fillMaxWidth().padding(36.dp)
    )
}
