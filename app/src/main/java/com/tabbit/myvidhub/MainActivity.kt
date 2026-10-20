package com.tabbit.myvidhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.detail.DetailScreen
import com.tabbit.myvidhub.ui.home.HomeScreen
import com.tabbit.myvidhub.ui.player.ViewerActivity
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
    data class Detail(val mediaId: Long) : Route
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
    val isTv = remember { context.isTelevision() }
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
        is Route.Home, is Route.Detail -> Tab.HOME
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
                NavigationRail(containerColor = AppColors.Surface, modifier = Modifier.width(88.dp)) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = currentTab == t,
                            onClick = { route = tabRoute(t) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
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

            // ---------- 页面切换过渡 ----------
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    when {
                        // 进入详情：从右侧轻推入 + 淡入，营造"下钻"层次
                        targetState is Route.Detail ->
                            (slideInHorizontally(animationSpec = tween(300)) { it / 6 } +
                                fadeIn(tween(260)))
                                .togetherWith(fadeOut(tween(160)))

                        // 从详情返回：详情向左退出，列表从侧后浮现
                        initialState is Route.Detail && targetState is Route.Home ->
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
                            onOpenDetail = { id -> route = Route.Detail(id) },
                            onPlay = { item: MediaEntity ->
                                context.startActivity(ViewerActivity.intent(context, item.id))
                            }
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
