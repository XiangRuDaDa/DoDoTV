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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.ui.components.TmdbDisclaimer
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors

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
    var tmdbKey by remember { mutableStateOf(Prefs.tmdbApiKey) }
    var tmdbToken by remember { mutableStateOf(Prefs.tmdbReadToken) }
    var tmdbMirror by remember { mutableStateOf(Prefs.tmdbImageBase) }

    // 凭据变更计数：自增即触发重组，并重新判定刮削引擎
    var engineTick by remember { mutableStateOf(0) }
    // 只在记忆化里做纯计算（读凭据推导），不写全局状态
    val activeEngine = remember(engineTick) { ScrapeStrategy.resolve() }
    val activeEngineDesc = remember(activeEngine) { ScrapeStrategy.describe() }

    // 写操作放到副作用里：凭据一变就同步给全局，供其他页面读取
    LaunchedEffect(engineTick) { ScrapeStrategy.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineMedium,
            color = AppColors.TextPrimary
        )

        // ---------- 状态 ----------
        SettingCard(title = "当前状态") {
            StatRow("来源数量", "${sources.size}")
            StatRow("媒体条目", "${library.size}")
            StatRow(
                "Emby 已登录",
                sources.count { it.kind == com.tabbit.myvidhub.data.model.SourceKind.EMBY && it.token.isNotBlank() }
                    .toString()
            )
        }

        // ---------- 界面 ----------
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
        }

        // ---------- 刮削 ----------
        SettingCard(title = "刮削") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "填入后，详情页会自动从 TMDB 拉取海报、背景图、艺术字 Logo、评分、简介与演职员。\n" +
                        "前往 themoviedb.org 注册账号，在 Settings → API 里申请免费凭据（个人非商业用途免费）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary
                )

                // ---- 凭据一：API Key (v3) ----
                Text(
                    "API Key（v3 auth）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary
                )
                OutlinedTextField(
                    value = tmdbKey,
                    onValueChange = {
                        tmdbKey = it
                        Prefs.tmdbApiKey = it
                        // 改动后立即重新判定：填了就走 TMDB，清空了立刻退回原有方案，无需重启
                        engineTick++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("粘贴 API Key（32 位字符）") },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextSecondary,
                        focusedBorderColor = AppColors.Brand,
                        unfocusedBorderColor = AppColors.SurfaceHi
                    )
                )

                // ---- 凭据二：Read Access Token (v4) ----
                Text(
                    "Read Access Token（v4 auth）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary
                )
                Text(
                    "上面两项二选一即可，都留空则关闭 TMDB 刮削。\n" +
                        "两项都填时优先用 v4 令牌，因为它通过请求头传递，密钥不会出现在 URL 里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary
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
                    placeholder = { Text("粘贴 Read Access Token（较长串）") },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextSecondary,
                        focusedBorderColor = AppColors.Brand,
                        unfocusedBorderColor = AppColors.SurfaceHi
                    )
                )

                // ---- 图片镜像（国内加速）----
                Text(
                    "图片镜像域名（可选，国内加速）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary
                )
                Text(
                    "国内直连 image.tmdb.org 常常很慢，海报要等十几秒才出来，期间详情页只能显示兜底色。\n" +
                        "填一个可用镜像后，所有 TMDB 图片请求会自动改写过去，出图速度通常快数倍。\n" +
                        "只填域名即可，例如 https://tmdb-image.example.com；留空则用官方域名。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = tmdbMirror,
                    onValueChange = {
                        tmdbMirror = it
                        Prefs.tmdbImageBase = it
                        engineTick++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://你的镜像域名") },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextSecondary,
                        focusedBorderColor = AppColors.Brand,
                        unfocusedBorderColor = AppColors.SurfaceHi
                    )
                )

                // ---- 当前生效状态 ----
                val active = when {
                    Prefs.tmdbReadToken.isNotBlank() -> "已配置（v4 令牌优先）"
                    Prefs.tmdbApiKey.isNotBlank() -> "已配置（v3 Key）"
                    else -> "未配置"
                }
                Text(
                    "凭据状态：$active",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (Prefs.tmdbConfigured) AppColors.Brand else AppColors.TextTertiary
                )
                Text(
                    "当前刮削方案：$activeEngineDesc",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activeEngine == com.tabbit.myvidhub.data.scrape.ScrapeEngine.TMDB)
                        AppColors.Brand else AppColors.TextSecondary
                )
                Text(
                    if (activeEngine == com.tabbit.myvidhub.data.scrape.ScrapeEngine.TMDB)
                        "已启用 TMDB 刮削：详情页会用 TMDB 的海报、艺术字 Logo、评分与简介补全元数据。"
                    else
                        "未填写凭据，继续使用原有刮削方案（Emby 自带元数据 / TVBox 返回字段），不发起外部请求。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary
                )

                // ---- 刮削缓存管理 ----
                // 这里既说明防重复请求的机制，也给一个手动清空入口
                if (activeEngine == com.tabbit.myvidhub.data.scrape.ScrapeEngine.TMDB) {
                    var cacheCount by remember { mutableStateOf(vm.scrapeCacheSize()) }
                    var clearing by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    Text(
                        "防频繁请求：同一部影片 7 天内只刮一次，刮不到的结果也缓存 1 天，" +
                            "同一时刻的重复请求会自动合并；结果还会写入本地媒体库，重启后不重复联网。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                    Text(
                        "当前内存缓存：$cacheCount 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextSecondary
                    )

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
                            if (clearing) "正在清空…" else "清空刮削缓存",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(
                        "清空后下次打开详情页会重新向 TMDB 刮削；已存下的海报、简介等字段不会被抹掉。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
            }
        }

        // ---------- TMDB 署名与免责声明 ----------
        SettingCard(title = "数据来源与声明") {
            TmdbDisclaimer()
        }

        // ---------- 关于 ----------
        SettingCard(title = "关于") {
            Text(
                "DouDouTV 2.2\n\n" +
                    "一个自研的安卓个人媒体中心，手机与电视共用一个安装包。\n\n" +
                    "· 播放内核 Media3 / ExoPlayer，支持 MKV、HEVC、HLS 等主流格式\n" +
                    "· Emby 直连：静态直传原码流，局域网不转码\n" +
                    "· TVBox：兼容苹果CMS JSON 接口，自动解析多线路播放地址\n" +
                    "· 断点续播、自动连播、海报墙与剧集详情\n" +
                    "· 安装包升级：同版本号覆盖安装保留数据\n\n" +
                    "全部代码归你所有，可自由修改与分发。",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextTertiary
            )
        }

        Text(
            "播放器为自研实现，请仅用于播放你拥有合法权利的内容。\n" +
                "TMDB 数据按其使用条款用于个人非商业展示，应用内不得移除来源署名。",
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
