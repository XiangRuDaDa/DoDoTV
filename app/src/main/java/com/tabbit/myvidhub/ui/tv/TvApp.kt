package com.tabbit.myvidhub.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.player.tv.TvPlayerActivity
import com.tabbit.myvidhub.ui.settings.SettingsScreen
import com.tabbit.myvidhub.ui.sources.SourcesScreen

/**
 * 电视端外壳（完全独立于手机端 PhoneApp）。
 *
 * 差异是结构性的，不是加个 isTv 参数：
 *   · 没有底部标签栏 —— 遥控器在底部导航上来回按体验极差，改用顶部横排入口；
 *   · 页面切换用淡入淡出，不用左右推入 —— 十英尺距离下横向位移过大会让人失去方位感；
 *   · 全局 BackHandler 逐层回退，符合遥控器返回键直觉。
 */
internal sealed interface TvRoute {
    data object Home : TvRoute
    data object Search : TvRoute
    data class Detail(val mediaId: Long) : TvRoute
    data class Genre(val kind: String?, val genre: String?) : TvRoute
    data object Sources : TvRoute
    data object Settings : TvRoute
}

@Composable
fun TvApp(vm: LibraryViewModel) {
    val context = LocalContext.current
    var route by remember { mutableStateOf<TvRoute>(TvRoute.Home) }

    BackHandler(enabled = route !is TvRoute.Home) {
        route = TvRoute.Home
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = route,
            transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(160))) },
            label = "tv_route",
            modifier = Modifier.fillMaxSize()
        ) { target ->
            when (target) {
                is TvRoute.Home -> TvHomeScreen(
                    vm = vm,
                    onSearch = { route = TvRoute.Search },
                    onDetail = { id -> route = TvRoute.Detail(id) },
                    onGenre = { kind, genre -> route = TvRoute.Genre(kind, genre) },
                    onPlay = { item: MediaEntity ->
                        context.startActivity(TvPlayerActivity.intent(context, item.id))
                    },
                    onSources = { route = TvRoute.Sources },
                    onSettings = { route = TvRoute.Settings }
                )

                is TvRoute.Search -> TvSearchScreen(
                    vm = vm,
                    onBack = { route = TvRoute.Home },
                    onDetail = { id -> route = TvRoute.Detail(id) }
                )

                is TvRoute.Genre -> TvGenreScreen(
                    vm = vm,
                    initialKind = target.kind,
                    initialGenre = target.genre,
                    onBack = { route = TvRoute.Home },
                    onDetail = { id -> route = TvRoute.Detail(id) }
                )

                is TvRoute.Detail -> TvDetailScreen(
                    vm = vm,
                    mediaId = target.mediaId,
                    onBack = { route = TvRoute.Home },
                    onPlay = { id ->
                        context.startActivity(TvPlayerActivity.intent(context, id))
                    }
                )

                is TvRoute.Sources -> SourcesScreen(vm = vm, isTv = true)
                is TvRoute.Settings -> SettingsScreen(vm = vm)
            }
        }
    }
}
