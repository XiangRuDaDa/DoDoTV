package com.tabbit.myvidhub.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.player.PlayerFactory
import com.tabbit.myvidhub.ui.components.TmdbDisclaimer
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.launch

private const val PREFS_NAME = "app_prefs"
private const val KEY_FORCE_TV = "force_tv_mode"

/** 强制 TV 模式开关（测试用）。true 时覆盖系统判断 */
fun Context.forceTvMode(): Boolean =
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_FORCE_TV, false)

@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var forceTv by remember { mutableStateOf(prefs.getBoolean(KEY_FORCE_TV, false)) }
    // 总开关：默认关闭。关闭时不渲染任何 TMDB 设置、数据来源与声明
    var tmdbOn by remember { mutableStateOf(Prefs.tmdbEnabled) }
    // 只保留 v4 持续访问令牌（官方推荐，令牌走请求头不进 URL）
    var tmdbToken by remember { mutableStateOf(Prefs.tmdbReadToken) }

    // 凭据变更计数：自增即触发重组，并重新判定刮削引擎
    var engineTick by remember { mutableStateOf(0) }
    // 只在记忆化里做纯计算（读凭据推导），不写全局状态
    val activeEngine = remember(engineTick) { ScrapeStrategy.resolve() }

    // 写操作放到副作用里：凭据一变就同步给全局，供其他页面读取
    LaunchedEffect(engineTick) { ScrapeStrategy.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp, 20.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineMedium,
            color = AppColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // ---------- 状态 ----------
        SettingCard(title = "状态") {
            StatRow("来源数量", "${sources.size}")
            StatRow("媒体条目", "${library.size}")
            StatRow(
                "Emby 已登录",
                sources.count { it.kind == com.tabbit.myvidhub.data.model.SourceKind.EMBY && it.token.isNotBlank() }
                    .toString()
            )
        }

        // ---------- 界面（合并 TV 强制模式 + 海报尺寸） ----------
        SettingCard(title = "界面") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AppColors.SurfaceHi, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "强制 TV 模式",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        "手机上开启可模拟 TV 界面布局",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
                Switch(
                    checked = forceTv,
                    onCheckedChange = { forceTv = it; prefs.edit().putBoolean(KEY_FORCE_TV, it).apply() }
                )
            }

            Text(
                "缩略图大小",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary
            )
            val sizes = Prefs.PosterSize.values()
            var current by remember { mutableStateOf(Prefs.posterSize) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                sizes.forEach { s ->
                    val selected = s == current
                    AssistChip(
                        onClick = { Prefs.posterSize = s; current = s },
                        label = {
                            Text(
                                when (s) {
                                    Prefs.PosterSize.SMALL -> "小"
                                    Prefs.PosterSize.MEDIUM -> "中"
                                    Prefs.PosterSize.LARGE -> "大"
                                }
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) AppColors.Brand.copy(alpha = 0.18f) else AppColors.Surface,
                            labelColor = if (selected) AppColors.Brand else AppColors.TextSecondary
                        )
                    )
                }
            }
        }

        // ---------- 刮削 ----------
        // 设计原则：默认关闭。开关关掉时，这里只有一行开关，
        // 令牌、镜像、状态、缓存、声明全部不渲染，界面上看不到任何 TMDB 痕迹。
        SettingCard(title = "刮削") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "使用 TMDB 刮削",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextPrimary
                        )
                        Text(
                            "开启后自动补全海报、评分、简介与演职员；关闭则只用本地元数据。",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary
                        )
                    }
                    Switch(
                        checked = tmdbOn,
                        onCheckedChange = {
                            tmdbOn = it
                            Prefs.tmdbEnabled = it
                            engineTick++
                        }
                    )
                }

                // ---- 以下仅在开关打开时出现 ----
                if (tmdbOn) {
                    Text(
                        "Read Access Token（v4）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextPrimary
                    )
                    OutlinedTextField(
                        value = tmdbToken,
                        onValueChange = {
                            tmdbToken = it
                            Prefs.tmdbReadToken = it
                            engineTick++
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("粘贴以 eyJ 开头的令牌") },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextSecondary,
                            focusedBorderColor = AppColors.Brand,
                            unfocusedBorderColor = AppColors.SurfaceHi
                        )
                    )
                    Text(
                        "TMDB 账号 → Settings → API → Read Access Token，整串复制。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )

                    // ---- 生效状态 ----
                    val tmdbActive = activeEngine == com.tabbit.myvidhub.data.scrape.ScrapeEngine.TMDB
                    Text(
                        when {
                            tmdbActive -> "当前方案：TMDB 刮削"
                            Prefs.tmdbReadToken.isBlank() -> "当前方案：本地元数据（尚未填写令牌）"
                            else -> "当前方案：本地元数据"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tmdbActive) AppColors.Brand else AppColors.TextSecondary
                    )

                    // ---- 缓存大小 + 清除 ----
                    var cacheCount by remember { mutableStateOf(vm.scrapeCacheSize()) }
                    var clearing by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "刮削缓存",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextPrimary
                            )
                            Text(
                                "已缓存 $cacheCount 条，同一影片 7 天内不重复请求。",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary
                            )
                        }
                        Button(
                            onClick = {
                                if (clearing) return@Button
                                clearing = true
                                scope.launch {
                                    vm.clearScrapeCache()
                                    cacheCount = vm.scrapeCacheSize()
                                    clearing = false
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.SurfaceHi,
                                contentColor = AppColors.TextPrimary
                            )
                        ) {
                            Text(
                                if (clearing) "清除中…" else "清除缓存",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }

        // ---------- 数据来源与声明：仅开启 TMDB 时出现 ----------
        if (tmdbOn) {
            SettingCard(title = "数据来源与声明") {
                TmdbDisclaimer()
            }
        }

        // ---------- 播放缓存 ----------
        SettingCard(title = "播放缓存") {
            var cacheBytes by remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) {
                cacheBytes = PlayerFactory.cacheSizeBytes(ctx)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "当前占用：${PlayerFactory.formatBytes(cacheBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary
                )
                Button(
                    onClick = {
                        PlayerFactory.clearCache(ctx)
                        cacheBytes = PlayerFactory.cacheSizeBytes(ctx)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Brand,
                        contentColor = Color.White
                    )
                ) { Text("清除缓存") }
            }
            Text(
                "视频播放产生的临时缓存，关闭播放器时会自动清理。也可以手动清除释放存储。",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
        }

        // ---------- 关于（精简） ----------
        SettingCard(title = "关于") {
            Text(
                "DouDouTV 2.2",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextPrimary
            )
            Text(
                "自用安卓媒体中心，手机与电视同一安装包。\n" +
                    "Media3 播放内核 · Emby 直连 · TVBox 兼容 · 断点续播 · 海报墙",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
        }

        Text(
            "播放器为自研实现，请仅播放你拥有合法权利的内容。",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.Brand
            )
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceHi, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary
        )
    }
}
