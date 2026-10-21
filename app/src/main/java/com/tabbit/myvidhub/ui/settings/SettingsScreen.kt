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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

        // ---------- 关于 ----------
        SettingCard(title = "关于") {
            Text(
                "MyVidHub 2.0\n\n" +
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
            "播放器为自研实现，请仅用于播放你拥有合法权利的内容。",
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
