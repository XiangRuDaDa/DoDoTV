package com.tabbit.myvidhub.ui.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.tabbit.myvidhub.data.MediaRepository
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.player.PlayerFactory
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.MyVidHubTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放页（手机 + TV 共用），播放内核 Media3/ExoPlayer —— 当前 Android 主流播放器。
 *
 * · 遥控器：左/右 = ±10 秒，确认 = 暂停/播放，返回 = 退出
 * · 触摸：单击唤出控制条（倍速/音轨/字幕/比例/横竖屏按钮随控制条一起显隐）
 * · 结束自动接下一集；每 5 秒落一次进度
 * · 音轨 / 字幕轨道选择，字幕背景透明度与字号可调（持久化）
 * · 比例切换：适应 / 铺满 / 缩放；横竖屏切换
 * · 杜比视界 / 杜比全景声：Emby static 直传原码流，设备硬解 + 音频直通自动生效
 *
 * 注意：不主动挂载外挂字幕 —— 盲猜字幕轨 index 会在资源无字幕时触发
 * HTTP 404，Media3 会抛 ERROR_CODE_IO_BAD_HTTP_STATUS 导致整段播放失败。
 * 内嵌字幕轨（MKV 封装内）由 ExoPlayer 原生暴露，可直接在「音轨·字幕」中选择。
 */
@UnstableApi
class ViewerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var current: MediaEntity? = null
    private var source: SourceEntity? = null
    private var lastSavedAt = 0L

    private lateinit var prefs: SharedPreferences

    private var titleState by mutableStateOf("")
    private var toastState by mutableStateOf<String?>(null)
    private var speedState by mutableStateOf(1f)

    /** 0=适应 1=铺满 2=缩放 */
    private var resizeModeState by mutableStateOf(0)

    /** 字幕样式：背景不透明度（0~1）与字号（sp），设置持久化 */
    private var subtitleBgAlpha by mutableStateOf(DEFAULT_SUBTITLE_BG_ALPHA)
    private var subtitleTextSize by mutableStateOf(DEFAULT_SUBTITLE_TEXT_SIZE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        subtitleBgAlpha = prefs.getFloat(KEY_SUBTITLE_BG_ALPHA, DEFAULT_SUBTITLE_BG_ALPHA)
        subtitleTextSize = prefs.getFloat(KEY_SUBTITLE_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE)

        val mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, 0L)
        if (mediaId == 0L) {
            finish()
            return
        }

        setContent {
            MyVidHubTheme {
                PlayerScreen(
                    title = titleState,
                    toast = toastState,
                    speed = speedState,
                    onSpeed = { applySpeed(it) },
                    subtitleBgAlpha = subtitleBgAlpha,
                    subtitleTextSize = subtitleTextSize,
                    onSubtitleStyle = { bg, size ->
                        subtitleBgAlpha = bg
                        subtitleTextSize = size
                        applySubtitleStyle()
                        prefs.edit()
                            .putFloat(KEY_SUBTITLE_BG_ALPHA, bg)
                            .putFloat(KEY_SUBTITLE_TEXT_SIZE, size)
                            .apply()
                    },
                    loadTracks = { loadTracks() },
                    onSelectTrack = { selectTrack(it) },
                    resizeMode = resizeModeState,
                    onResizeMode = { resizeModeState = it },
                    isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                    onToggleOrientation = { toggleOrientation() },
                    onViewCreated = { view ->
                        playerView = view
                        player?.let { view.player = it }
                        applySubtitleStyle()
                    }
                )
            }
        }

        lifecycleScope.launch {
            val repo = MediaRepository(this@ViewerActivity)
            val item = repo.getMedia(mediaId)
            if (item == null) {
                toastState = "条目不存在"
                return@launch
            }
            current = item
            source = runCatching { repo.getSource(item.sourceId) }.getOrNull()
            titleState = displayTitle(item)
            startPlayback(item)
        }
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    private fun applySpeed(speed: Float) {
        speedState = speed
        player?.setPlaybackSpeed(speed)
    }

    /** 应用字幕外观：背景透明度 + 字号 */
    private fun applySubtitleStyle() {
        val sv = playerView?.subtitleView ?: return
        val bg = android.graphics.Color.argb(
            (subtitleBgAlpha.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0
        )
        sv.setStyle(
            CaptionStyleCompat(
                android.graphics.Color.WHITE,
                bg,
                CaptionStyleCompat.EDGE_TYPE_NONE,
                bg,
                android.graphics.Color.TRANSPARENT,
                null
            )
        )
        sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
    }

    private fun displayTitle(item: MediaEntity): String =
        if (item.kind == MediaKind.EPISODE && item.episode > 0) {
            "%s  S%02dE%02d".format(item.title, item.season, item.episode)
        } else {
            item.title
        }

    private fun startPlayback(item: MediaEntity, startPosition: Long = item.positionMs) {
        if (item.uri.isBlank()) {
            toastState = "该条目没有可播放地址（可能需要在详情页重新加载）"
            return
        }

        val p = PlayerFactory.createPlayer(this)
        player = p
        playerView?.player = p
        applySubtitleStyle()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playNext()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                toastState = "播放失败：${error.errorCodeName}"
            }
        })

        p.setMediaItem(MediaItem.fromUri(item.uri))
        p.prepare()
        if (startPosition > 3_000L) p.seekTo(startPosition)
        p.setPlaybackSpeed(speedState)
        p.playWhenReady = true
    }

    // ---------- 轨道选择 ----------

    /** 当前可选的音轨 / 字幕轨 */
    private fun loadTracks(): List<TrackOption> {
        val p = player ?: return emptyList()
        val out = mutableListOf<TrackOption>()
        val textDisabled = p.trackSelectionParameters
            .disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        p.currentTracks.groups.forEach { group ->
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) return@forEach
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (type == C.TRACK_TYPE_AUDIO && format.sampleMimeType == null) continue
                val label = buildString {
                    when (type) {
                        C.TRACK_TYPE_AUDIO -> {
                            append(format.label ?: format.language ?: "音轨")
                            format.channelCount.takeIf { it > 0 }?.let { append("  ·  ${it} 声道") }
                        }
                        else -> {
                            append(format.label ?: format.language ?: "字幕")
                            format.language?.let { if (format.label != null) append("  ·  $it") }
                        }
                    }
                }
                out += TrackOption(label, type, group, i, group.isTrackSelected(i))
            }
        }
        return out + TrackOption(
            "关闭字幕", C.TRACK_TYPE_TEXT, out.firstOrNull()?.group ?: return out, -1, textDisabled
        )
    }

    private fun selectTrack(option: TrackOption) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option.type == C.TRACK_TYPE_TEXT && option.trackIndex < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(option.type, false)
            builder.setOverrideForType(
                TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex)
            )
        }
        p.trackSelectionParameters = builder.build()
    }

    /** 同剧集内接下一集 */
    private fun playNext() {
        val cur = current ?: return
        if (cur.kind != MediaKind.EPISODE || cur.seriesKey.isBlank()) return

        lifecycleScope.launch {
            val repo = MediaRepository(this@ViewerActivity)
            val next = repo.episodes(cur.seriesKey)
                .filter { it.episode > cur.episode && it.uri.isNotBlank() }
                .minByOrNull { it.episode } ?: return@launch

            saveProgress(force = true)

            current = next
            titleState = displayTitle(next)

            player?.let { p ->
                p.setMediaItem(MediaItem.fromUri(next.uri))
                p.prepare()
                p.setPlaybackSpeed(speedState)
                p.playWhenReady = true
            }
        }
    }

    private fun saveProgress(force: Boolean = false) {
        val item = current ?: return
        val p = player ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < 5_000L) return
        lastSavedAt = now

        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0L } ?: 0L

        lifecycleScope.launch {
            runCatching {
                val repo = MediaRepository(this@ViewerActivity)
                repo.saveProgress(item.id, pos)
                if (dur > 0L) repo.fillDuration(item.id, dur)
            }
        }
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onPause() {
        super.onPause()
        saveProgress(force = true)
    }

    override fun onDestroy() {
        saveProgress(force = true)
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                player?.let { it.seekTo(it.currentPosition + 30_000L) }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                player?.let { it.seekTo((it.currentPosition - 30_000L).coerceAtLeast(0L)) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_SUBTITLE_BG_ALPHA = "subtitle_bg_alpha"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
        private const val DEFAULT_SUBTITLE_BG_ALPHA = 0.35f
        private const val DEFAULT_SUBTITLE_TEXT_SIZE = 18f

        fun intent(context: Context, mediaId: Long): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_MEDIA_ID, mediaId)
    }
}

/** 可选轨道项；trackIndex = -1 表示「关闭字幕」 */
private data class TrackOption(
    val label: String,
    val type: Int,
    val group: Tracks.Group,
    val trackIndex: Int,
    val selected: Boolean
)

@UnstableApi
@Composable
private fun PlayerScreen(
    title: String,
    toast: String?,
    speed: Float,
    onSpeed: (Float) -> Unit,
    subtitleBgAlpha: Float,
    subtitleTextSize: Float,
    onSubtitleStyle: (bgAlpha: Float, textSize: Float) -> Unit,
    loadTracks: () -> List<TrackOption>,
    onSelectTrack: (TrackOption) -> Unit,
    resizeMode: Int,
    onResizeMode: (Int) -> Unit,
    isLandscape: Boolean,
    onToggleOrientation: () -> Unit,
    onViewCreated: (PlayerView) -> Unit
) {
    var showTitle by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(false) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }

    LaunchedEffect(title) {
        showTitle = true
        delay(3_200)
        showTitle = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                    keepScreenOn = true
                    controllerShowTimeoutMs = 3_500
                    // 自定义按钮与原生控制条同生命周期：触摸唤出控制条时一起出现
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        }
                    )
                    onViewCreated(this)
                }
            },
            update = { view ->
                view.setResizeMode(
                    when (resizeMode) {
                        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                )
            }
        )

        AnimatedVisibility(
            visible = showTitle && title.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                Modifier
                    .padding(20.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        if (!toast.isNullOrBlank()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(toast, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 自定义控制层：随播放控制条一起显隐
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // ---------- 倍速 ----------
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpeedOptions.forEach { option ->
                        val selected = kotlin.math.abs(speed - option) < 0.01f
                        ControlChip(
                            label = formatSpeed(option),
                            selected = selected
                        ) { onSpeed(option) }
                    }
                }

                // ---------- 音轨 / 字幕 / 画面 ----------
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlChip("音轨 · 字幕") { showTrackDialog = true }
                    ControlChip("字幕样式") { showStyleDialog = true }
                    ControlChip(
                        label = when (resizeMode) {
                            1 -> "铺满"
                            2 -> "缩放"
                            else -> "适应"
                        }
                    ) { onResizeMode((resizeMode + 1) % 3) }
                    ControlChip(if (isLandscape) "小屏" else "全屏") { onToggleOrientation() }
                }
            }
        }
    }

    if (showTrackDialog) {
        TrackDialog(
            tracks = loadTracks(),
            onDismiss = { showTrackDialog = false },
            onSelect = {
                onSelectTrack(it)
                showTrackDialog = false
            }
        )
    }

    if (showStyleDialog) {
        SubtitleStyleDialog(
            bgAlpha = subtitleBgAlpha,
            textSize = subtitleTextSize,
            onChange = onSubtitleStyle,
            onDismiss = { showStyleDialog = false }
        )
    }
}

@Composable
private fun ControlChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) AppColors.Brand else Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** 音轨与字幕轨选择 */
@Composable
private fun TrackDialog(
    tracks: List<TrackOption>,
    onDismiss: () -> Unit,
    onSelect: (TrackOption) -> Unit
) {
    val audio = tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
    val text = tracks.filter { it.type == C.TRACK_TYPE_TEXT && it.trackIndex < 0 } +
        tracks.filter { it.type == C.TRACK_TYPE_TEXT && it.trackIndex >= 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        containerColor = AppColors.Surface,
        title = { Text("音轨与字幕", color = AppColors.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (audio.isNotEmpty()) {
                    DialogSection("音轨")
                    audio.forEach { TrackRow(it) { onSelect(it) } }
                }
                if (text.isNotEmpty()) {
                    DialogSection("字幕")
                    text.forEach { TrackRow(it) { onSelect(it) } }
                }
                if (tracks.isEmpty()) {
                    Text(
                        "未检测到可选轨道",
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    )
}

@Composable
private fun DialogSection(title: String) {
    Text(
        title,
        color = AppColors.Brand,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun TrackRow(option: TrackOption, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (option.selected) AppColors.SurfaceHi else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            option.label,
            color = if (option.selected) AppColors.Brand else AppColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (option.selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = AppColors.Brand,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 字幕样式调节：背景透明度 + 字号，实时生效并持久化 */
@Composable
private fun SubtitleStyleDialog(
    bgAlpha: Float,
    textSize: Float,
    onChange: (Float, Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        containerColor = AppColors.Surface,
        title = { Text("字幕样式", color = AppColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "背景不透明度  ${(bgAlpha * 100).toInt()}%",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = bgAlpha,
                    onValueChange = { onChange(it, textSize) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = AppColors.Brand,
                        activeTrackColor = AppColors.Brand
                    )
                )
                Text(
                    "字号  ${textSize.toInt()} sp",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = textSize,
                    onValueChange = { onChange(bgAlpha, it) },
                    valueRange = 12f..32f,
                    colors = SliderDefaults.colors(
                        thumbColor = AppColors.Brand,
                        activeTrackColor = AppColors.Brand
                    )
                )
            }
        }
    )
}

private val SpeedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun formatSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"
