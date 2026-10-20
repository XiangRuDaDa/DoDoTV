package com.tabbit.myvidhub.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors

@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    var forceTv by remember { mutableStateOf(Prefs.forceTvMode) }

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

        // ---------- 显示 ----------
        SettingCard(title = "显示") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "强制电视模式",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "电视盒子未被识别为 TV 时开启：改用左侧导航、放大卡片间距、强化遥控器焦点",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
                Switch(
                    checked = forceTv,
                    onCheckedChange = {
                        forceTv = it
                        Prefs.forceTvMode = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AppColors.Brand,
                        uncheckedThumbColor = AppColors.TextTertiary,
                        uncheckedTrackColor = AppColors.SurfaceHi
                    )
                )
            }
        }

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

        // ---------- 关于 ----------
        SettingCard(title = "关于") {
            Text(
                "MyVidHub 1.0\n\n" +
                    "一个自研的安卓个人媒体中心，手机与电视共用一个安装包。\n\n" +
                    "· 播放内核 Media3 / ExoPlayer，支持 MKV、HEVC、HLS 等主流格式\n" +
                    "· Emby 直连：静态直传原码流，局域网不转码\n" +
                    "· TVBox：兼容苹果CMS JSON 接口，自动解析多线路播放地址\n" +
                    "· 断点续播、自动连播、海报墙与剧集详情\n\n" +
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
