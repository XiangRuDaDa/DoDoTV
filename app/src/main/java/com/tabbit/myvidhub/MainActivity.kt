package com.tabbit.myvidhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.detail.DetailScreen
import com.tabbit.myvidhub.ui.home.GenreScreen
import com.tabbit.myvidhub.ui.home.HomeScreen
import com.tabbit.myvidhub.ui.home.SearchScreen
import com.tabbit.myvidhub.ui.player.ViewerActivity
import com.tabbit.myvidhub.ui.settings.forceTvMode
import com.tabbit.myvidhub.ui.settings.SettingsScreen
import com.tabbit.myvidhub.ui.sources.SourcesScreen
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.MyVidHubTheme
import com.tabbit.myvidhub.ui.theme.isTelevision

/** 应用内路由 */
private sealed interface Route {
    data object Home : Route
    data object Sources : Route
    data object Settings : Route
    data object Search : Route
    data class Detail(val mediaId: Long) : Route

    /** 分类浏览页：kind=电影/剧集类型，genre=动漫等题材 */
    data class Genre(val kind: String?, val genre: String?) : Route
}

/**
 * 手机与 TV 共用的单 Activity 结构。
 *
 * 分叉点只有两处：
 * · 导航形态：手机底部栏 / 电视左侧栏（遥控器上下移动更自然）
 * · 内容密度：电视用更多列、更大间距，适配十英尺观看距离
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyVidHubTheme { AppRoot() }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("媒体库", Icons.Filled.Home),
    SOURCES("来源", Icons.Filled.Storage),
    SETTINGS("设置", Icons.Filled.Settings)
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val forceTv = remember { context.forceTvMode() }
    val isTv = remember { context.isTelevision() || forceTv }
    val vm: LibraryViewModel = viewModel()

    var route by remember { mutableStateOf<Route>(Route.Home) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        val msg = vm.message
        if (!msg.isNullOrBlank()) {
            snackbarHost.showSnackbar(msg)
            vm.message = null
        }
    }

    // 从详情页返回；其余页面返回则停在首页
    BackHandler(enabled = route !is Route.Home) {
        route = Route.Home
    }

    val currentTab = when (route) {
        is Route.Home, is Route.Detail, is Route.Search, is Route.Genre -> Tab.HOME
        is Route.Sources -> Tab.SOURCES
        is Route.Settings -> Tab.SETTINGS
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = AppColors.Bg,
        bottomBar = {
            if (!isTv) {
                NavigationBar(containerColor = AppColors.Surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = currentTab == t,
                            onClick = { route = tabRoute(t) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.Brand,
                                selectedTextColor = AppColors.Brand,
                                unselectedIconColor = AppColors.TextTertiary,
                                unselectedTextColor = AppColors.TextTertiary,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (isTv) {
                TvNavigationRail(
                    currentTab = currentTab,
                    onSelect = { route = tabRoute(it) }
                )
            }

            // ---------- 页面切换过渡 ----------
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    when {
                        // 进入详情/分类：从右侧轻推入 + 淡入，营造"下钻"层次
                        targetState is Route.Detail || targetState is Route.Genre ->
                            (slideInHorizontally(animationSpec = tween(300)) { it / 6 } +
                                fadeIn(tween(260)))
                                .togetherWith(fadeOut(tween(160)))

                        // 从详情返回：详情向左退出，列表从侧后浮现
                        (initialState is Route.Detail || initialState is Route.Genre) &&
                            targetState is Route.Home ->
                            (fadeIn(tween(260)) +
                                slideInHorizontally(animationSpec = tween(300)) { -it / 12 })
                                .togetherWith(
                                    slideOutHorizontally(animationSpec = tween(260)) { it / 4 } +
                                        fadeOut(tween(200))
                                )

                        // 底部标签之间：单纯交叉淡入，避免方向混乱
                        else -> fadeIn(tween(220)).togetherWith(fadeOut(tween(160)))
                    }
                },
                label = "route_transition",
                modifier = Modifier.fillMaxSize()
            ) { target ->
                Box(Modifier.fillMaxSize()) {
                    when (target) {
                        is Route.Home -> HomeScreen(
                            vm = vm,
                            isTv = isTv,
                            onOpenSearch = { route = Route.Search },
                            onOpenDetail = { id -> route = Route.Detail(id) },
                            onOpenGenre = { kind, genre -> route = Route.Genre(kind, genre) },
                            onPlay = { item: MediaEntity ->
                                context.startActivity(ViewerActivity.intent(context, item.id))
                            }
                        )

                        is Route.Genre -> GenreScreen(
                            vm = vm,
                            isTv = isTv,
                            initialKind = target.kind,
                            initialGenre = target.genre,
                            onBack = { route = Route.Home },
                            onOpenDetail = { id -> route = Route.Detail(id) }
                        )

                        is Route.Detail -> DetailScreen(
                            vm = vm,
                            mediaId = target.mediaId,
                            isTv = isTv,
                            onBack = { route = Route.Home },
                            onPlay = { id ->
                                context.startActivity(ViewerActivity.intent(context, id))
                            }
                        )

                        is Route.Search -> SearchScreen(
                            vm = vm,
                            isTv = isTv,
                            onBack = { route = Route.Home },
                            onOpenDetail = { id -> route = Route.Detail(id) }
                        )

                        is Route.Sources -> SourcesScreen(vm = vm, isTv = isTv)
                        is Route.Settings -> SettingsScreen(vm = vm)
                    }
                }
            }
        }
    }
}

private fun tabRoute(tab: Tab): Route = when (tab) {
    Tab.HOME -> Route.Home
    Tab.SOURCES -> Route.Sources
    Tab.SETTINGS -> Route.Settings
}

/**
 * TV 左侧导航栏（VidHub 形态）。
 *
 * 默认收成只剩图标的窄条，遥控器焦点移到栏内时平滑展开显示文字标签，
 * 焦点离开后自动收回，把画面宽度还给海报墙。
 */
@Composable
private fun TvNavigationRail(currentTab: Tab, onSelect: (Tab) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (expanded) 188.dp else 72.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "rail_width"
    )

    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(AppColors.Surface)
            .onFocusChanged { expanded = it.hasFocus }
    ) {
        // 顶部留白，避免贴边
        Box(Modifier.height(28.dp))

        Tab.entries.forEach { t ->
            val selected = currentTab == t
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            focused || selected -> AppColors.SurfaceHi
                            else -> Color.Transparent
                        }
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(t) }
                    )
                    .focusable()
                    .padding(horizontal = 12.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    t.icon,
                    contentDescription = t.label,
                    tint = if (focused || selected) AppColors.Brand else AppColors.TextTertiary,
                    modifier = Modifier.size(22.dp)
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(90))
                ) {
                    Text(
                        t.label,
                        modifier = Modifier.padding(start = 12.dp),
                        color = if (focused || selected) AppColors.Brand else AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
