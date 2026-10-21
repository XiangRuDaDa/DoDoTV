package com.tabbit.myvidhub.ui.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import androidx.media3.ui.PlayerView
import com.tabbit.myvidhub.data.MediaRepository
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.player.PlayerFactory
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.MyVidHubTheme
import kotlinx.coroutines.launch

/**
 * 播放页（VidHub 风格）。
 *
 * 核心设计：PlayerView 默认控制条负责 seek bar 拖动、播放暂停、快进快退 ——
 * 这些手势最复杂的控件交给 Media3 原生处理，不再被 Compose Overlay 拦截。
 * Compose Overlay 只画倍速/字幕/比例/横屏四个按钮，且**随控制条一起显隐**：
 * 控制条显示时按钮可见，控制条隐藏时按钮也隐藏，避免 seek bar 手势冲突。
 *
 * 支持：倍速选择、音轨+字幕轨道选择、字幕背景透明度/字号持久化调节、
 * 画面比例切换、横竖屏切换、同剧集自动接下一集、继续观看进度保存。
 */
@UnstableApi
class ViewerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var current: MediaEntity? = null
    private var lastSavedAt = 0L

    private lateinit var prefs: SharedPreferences

    private var titleState by mutableStateOf("")
    private var toastState by mutableStateOf<String?>(null)
    private var speedState by mutableStateOf(1f)

    /** 0=适应 1=铺满 2=缩放 */
    private var resizeModeState by mutableStateOf(0)

    /** Compose Overlay 按钮随 PlayerView 控制条一起显隐 */
    private var controlsVisible by mutableStateOf(false)

    /** 字幕样式：背景不透明度与字号 */
    private var subtitleBgAlpha by mutableStateOf(DEFAULT_SUBTITLE_BG_ALPHA)
    private var subtitleTextSize by mutableStateOf(DEFAULT_SUBTITLE_TEXT_SIZE)

    private var showTrackDialog by mutableStateOf(false)
    private var showSubtitleStyleDialog by mutableStateOf(false)

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
                    onSpeed = {
                        speedState = it
                        player?.setPlaybackSpeed(it)
                    },
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
                    controlsVisible = controlsVisible,
                    resizeMode = resizeModeState,
                    onResizeMode = { mode ->
                        resizeModeState = mode
                        playerView?.resizeMode = when (mode) {
                            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                    onToggleOrientation = { toggleOrientation() },
                    onShowTrackDialog = { showTrackDialog = true },
                    onShowSubtitleStyleDialog = { showSubtitleStyleDialog = true },
                    onViewCreated = { view ->
                        playerView = view
                        player?.let { view.player = it }
                        configurePlayerView(view)
                        applySubtitleStyle()
                    }
                )

                if (showTrackDialog) {
                    TrackDialog(
                        tracks = loadTracks(),
                        onDismiss = { showTrackDialog = false },
                        onSelect = {
                            selectTrack(it)
                            showTrackDialog = false
                        },
                        onStyle = {
                            showSubtitleStyleDialog = true
                        }
                    )
                }

                if (showSubtitleStyleDialog) {
                    SubtitleStyleDialog(
                        bgAlpha = subtitleBgAlpha,
                        textSize = subtitleTextSize,
                        onChange = { bg, size ->
                            subtitleBgAlpha = bg
                            subtitleTextSize = size
                            applySubtitleStyle()
                            prefs.edit()
                                .putFloat(KEY_SUBTITLE_BG_ALPHA, bg)
                                .putFloat(KEY_SUBTITLE_TEXT_SIZE, size)
                                .apply()
                        },
                        onDismiss = { showSubtitleStyleDialog = false }
                    )
                }
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
            titleState = displayTitle(item)
            startPlayback(item)
        }
    }

    private fun configurePlayerView(view: PlayerView) {
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            controlsVisible = visibility == View.VISIBLE
        })
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

    /** 字幕外观：背景不透明度 + 字号 */
    private fun applySubtitleStyle() {
        val sv = playerView?.subtitleView ?: return
        val bg = android.graphics.Color.argb(
            (subtitleBgAlpha.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0
        )
        sv.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE,
                bg,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                bg,
                android.graphics.Color.TRANSPARENT,
                null
            )
        )
        sv.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
    }

    private fun toggleOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation =
            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ---------- 轨道选择 ----------

    private fun loadTracks(): List<TrackOption> {
        val p = player ?: return emptyList()
        val out = mutableListOf<TrackOption>()
        val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        for (group in p.currentTracks.groups) {
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (type == C.TRACK_TYPE_AUDIO && format.sampleMimeType == null) continue
                val label = buildString {
                    when (type) {
                        C.TRACK_TYPE_AUDIO -> {
                            append(format.label ?: format.language ?: "音轨")
                            format.channelCount.takeIf { it > 0 }?.let { append(" · ${it}声道") }
                        }
                        else -> append(format.label ?: format.language ?: "字幕")
                    }
                }
                out += TrackOption(label, type, group, i, group.isTrackSelected(i))
            }
        }
        return out + TrackOption(
            "关闭字幕", C.TRACK_TYPE_TEXT,
            p.currentTracks.groups.firstOrNull() ?: return out,
            -1, textDisabled
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
    onSubtitleStyle: (Float, Float) -> Unit,
    controlsVisible: Boolean,
    resizeMode: Int,
    onResizeMode: (Int) -> Unit,
    isLandscape: Boolean,
    onToggleOrientation: () -> Unit,
    onShowTrackDialog: () -> Unit,
    onShowSubtitleStyleDialog: () -> Unit,
    onViewCreated: (PlayerView) -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

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
                }.also { onViewCreated(it) }
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

        // Compose Overlay 只画标题和 toast —— 播放器控件完全交给 PlayerView 默认控制条
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
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
        }

        // VidHub 风格：倍速/字幕按钮随控制条一起显隐，画在控制条区域上方
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 64.dp) // 避开 PlayerView 默认控制条（通常在底部 ~80dp 区域）
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    speeds.forEach { s ->
                        val selected = kotlin.math.abs(speed - s) < 0.01f
                        ControlChip(
                            label = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x",
                            selected = selected
                        ) { onSpeed(s) }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ControlChip("音轨 · 字幕") { onShowTrackDialog() }
                    ControlChip(
                        label = when (resizeMode) { 1 -> "铺满"; 2 -> "缩放"; else -> "适应" }
                    ) { onResizeMode((resizeMode + 1) % 3) }
                    ControlChip(if (isLandscape) "小屏" else "全屏") { onToggleOrientation() }
                }
            }
        }
    }
}

@Composable
private fun ControlChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) AppColors.Brand
                else Color.Black.copy(alpha = 0.55f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TrackDialog(
    tracks: List<TrackOption>,
    onDismiss: () -> Unit,
    onSelect: (TrackOption) -> Unit,
    onStyle: () -> Unit
) {
    val audio = tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
    val text = tracks.filter { it.type == C.TRACK_TYPE_TEXT }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        containerColor = AppColors.Surface,
        title = { Text("音轨与字幕", color = AppColors.TextPrimary) },
        text = {
            Column {
                if (audio.isNotEmpty()) {
                    Text("音轨", color = AppColors.Brand, style = MaterialTheme.typography.titleSmall)
                    audio.forEach { TrackRow(it) { onSelect(it) } }
                }
                if (text.isNotEmpty()) {
                    Text("字幕", color = AppColors.Brand, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp))
                    text.forEach { TrackRow(it) { onSelect(it) } }
                }
                if (tracks.isEmpty()) {
                    Text("未检测到可选轨道", color = AppColors.TextSecondary)
                }
            }
        }
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
        if (option.selected) Text("✓", color = AppColors.Brand,
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SubtitleStyleDialog(
    bgAlpha: Float,
    textSize: Float,
    onChange: (Float, Float) -> Unit,
    onDismiss: () -> Unit
) {
    val bgValues = listOf(0f, 0.15f, 0.35f, 0.55f, 0.8f, 1f)
    val sizeValues = listOf(12f, 14f, 16f, 18f, 20f, 22f, 26f, 32f)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        containerColor = AppColors.Surface,
        title = { Text("字幕样式", color = AppColors.TextPrimary) },
        text = {
            Column {
                Text(
                    "背景不透明度  ${(bgAlpha * 100).toInt()}%",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    bgValues.forEach { v ->
                        val selected = kotlin.math.abs(v - bgAlpha) < 0.05f
                        Button(
                            onClick = { onChange(v, textSize) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) AppColors.Brand else AppColors.SurfaceHi,
                                contentColor = if (selected) Color.White else AppColors.TextSecondary
                            ),
                            modifier = Modifier.height(32.dp)
                        ) { Text("${(v * 100).toInt()}%") }
                    }
                }
                Text(
                    "字号  ${textSize.toInt()} sp",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizeValues.forEach { v ->
                        val selected = kotlin.math.abs(v - textSize) < 1f
                        Button(
                            onClick = { onChange(bgAlpha, v) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) AppColors.Brand else AppColors.SurfaceHi,
                                contentColor = if (selected) Color.White else AppColors.TextSecondary
                            ),
                            modifier = Modifier.height(32.dp)
                        ) { Text("${v.toInt()}sp") }
                    }
                }
            }
        }
    )
}
