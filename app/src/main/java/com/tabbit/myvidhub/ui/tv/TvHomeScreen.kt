package com.tabbit.myvidhub.ui.tv

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.ui.LibraryViewModel
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
 *
 * 遥控器焦点导航：
 *   TvTopBar 在 Box 顶层悬浮（和 LazyColumn 平级），焦点系统默认不跨子树导航。
 *   通过 onPreviewKeyEvent 拦截 KEYCODE_DPAD_DOWN → 手动把焦点交给 TvHero 播放按钮，
 *   让 LazyColumn 内的嵌套 LazyRow 继续处理后续的上下方向键。
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
    val library by vm.library.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()

    // 优先用有横版背景图的条目做精选，不足时用海报补位
    val heroItems = remember(library) {
        val primary = library.filter { it.backdropUrl.isNotBlank() }
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

    val listState = rememberLazyListState()
    // 滚动距离驱动顶栏透明度（下滑到 heroHeight 后淡出）
    val configuration = LocalConfiguration.current
    val heroCollapseDistPx = (configuration.screenHeightDp * 0.40f).toInt()
    val rawFade = if (heroCollapseDistPx == 0) 0f
        else (listState.firstVisibleItemScrollOffset.toFloat() / heroCollapseDistPx).coerceIn(0f, 1f)
    val topBarAlpha by animateFloatAsState(targetValue = 1f - rawFade, label = "tv_topbar_fade")

    // 焦点 requester 链：TvTopBar ↔ TvHero 播放按钮 ↔ 第一个 TvPosterRow
    val topBarFirstFocus = remember { FocusRequester() }
    val heroPlayFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { heroPlayFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(AppColors.Bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 44.dp)
        ) {
            // ---------- 主视觉（置顶，TvTopBar 悬浮其上） ----------
            if (hero != null) {
                item {
                    TvHero(
                        item = hero,
                        playFocusRequester = heroPlayFocus,
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

            // ---------- 动态分区：根据 library 实际存在的 kind 渲染 ----------
            DynamicTvGallerySections(
                library = library,
                onMore = onGenre,
                onDetail = onDetail
            )
        }

        // ---------- 透明悬浮顶部入口条（压在主视觉之上，滚动淡出；只显示操作按钮，不显示软件名） ----------
        TvTopBar(
            alpha = topBarAlpha,
            scanning = vm.scanning,
            firstFocusRequester = topBarFirstFocus,
            onSearch = onSearch,
            onSources = onSources,
            onSettings = onSettings,
            onRefresh = { vm.scanAll() },
            onDpadDownToHero = { heroPlayFocus.requestFocus() }
        )
    }
}

/** 顶部入口条：只有操作按钮（同步/搜索/来源/设置）。TV 端不展示软件名称。 */
@Composable
private fun TvTopBar(
    alpha: Float,
    firstFocusRequester: FocusRequester,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    scanning: Boolean = false,
    onDpadDownToHero: () -> Unit
) {
    val enabled = alpha > 0.1f

    Row(
        Modifier
            .focusable(enabled = enabled)
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .onPreviewKeyEvent { event ->
                if (enabled && event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    (event.nativeKeyEvent?.keyCode ?: 0) == KeyEvent.KEYCODE_DPAD_DOWN) {
                    onDpadDownToHero()
                    true
                } else false
            }
            .padding(start = 44.dp, end = 44.dp, top = 26.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (scanning) {
            CircularProgressIndicator(color = AppColors.Brand, strokeWidth = 2.dp)
            Spacer(Modifier.width(18.dp))
        } else {
            TvPillButton("同步", primary = false, onClick = onRefresh)
            Spacer(Modifier.width(12.dp))
        }
        TvPillButton(
            "搜索", primary = false, onClick = onSearch,
            modifier = Modifier.focusRequester(firstFocusRequester)
        )
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
    playFocusRequester: FocusRequester,
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
            if (item.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(96.dp).widthIn(max = 460.dp)
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
                    modifier = Modifier.focusRequester(playFocusRequester),
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
    onDetail: (Long) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 44.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(items, key = { "tv_${it.id}" }) { item ->
            TvPosterCard(
                item = item,
                onClick = { onDetail(item.id) }
            )
        }
    }
}

/**
 * 动态分区：根据 library 里实际存在的 kind 渲染画廊块。
 * 有 MOVIE → 电影；有 SERIES → 剧集；有 genres 含「动漫」→ 动漫。
 * 空的跳过，不写死分区标题。
 */
private fun LazyListScope.DynamicTvGallerySections(
    library: List<MediaEntity>,
    onMore: (kind: String?, genre: String?) -> Unit,
    onDetail: (Long) -> Unit
) {
    if (library.isEmpty()) return

    val kindLabel = { kind: String ->
        when (kind) {
            MediaKind.MOVIE -> "电影"
            MediaKind.SERIES -> "剧集"
            MediaKind.EPISODE -> "单集"
            else -> kind
        }
    }

    val blocks = library
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

    for ((kind, items) in blocks) {
        val title = kindLabel(kind)
        item { TvSectionTitle(title) { TvMoreButton { onMore(kind, null) } } }
        item { TvPosterRow(items = items.take(12), onDetail = onDetail) }
    }
}
