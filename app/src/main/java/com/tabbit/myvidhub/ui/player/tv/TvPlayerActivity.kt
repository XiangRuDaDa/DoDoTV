package com.tabbit.myvidhub.ui.player.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.tabbit.myvidhub.player.PlayerFactory
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.MyVidHubTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 电视端播放器（与手机端完全独立的一套实现）。
 *
 * 交互遵循遥控器心智，不做触摸手势、不做比例切换：
 *   · OK 键：暂停 / 继续，并显示进度条
 *   · 下方向键：弹出功能面板（倍速 / 字幕 / 音轨 / 选集），3 秒无操作自动收起
 *   · 左右方向键：快退 / 快进 10 秒，带时间提示
 *   · 返回键：面板打开时先关面板，再按退出播放
 * 始终全屏，不提供「适应 / 铺满 / 缩放」切换。
 */
@UnstableApi
class TvPlayerActivity : ComponentActivity() {

    private lateinit var repo: MediaRepository
    private var player: ExoPlayer? = null
    private var mediaId = 0L
    private var current: MediaEntity? = null
    private var episodes: List<MediaEntity> = emptyList()

    private val lastSavedAt = AtomicLong(0L)
    private val handler = Handler(Looper.getMainLooper())

    // ---------- 供 Compose 读取的界面状态 ----------
    private var overlayVisible by mutableStateOf(true)
    private var panelTab by mutableStateOf<TvPanelTab?>(null)
    private var toast by mutableStateOf<String?>(null)
    private var seekHint by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var speed by mutableStateOf(1f)
    private var titleText by mutableStateOf("")
    private var trackOptions by mutableStateOf<List<TvTrack>>(emptyList())

    /** 字幕是否处于关闭态。自己记录而不是反查播放器，避免不同 Media3 小版本 API 差异 */
    private var textDisabled by mutableStateOf(false)

    /** 交互计数：每次按键 / 选项切换都 +1，用于重置 3 秒自动隐藏 */
    private var tick by mutableStateOf(0)

    private val progressTicker = object : Runnable {
        override fun run() {
            player?.let { p ->
                positionMs = p.currentPosition.coerceAtLeast(0L)
                durationMs = p.duration.takeIf { it > 0L } ?: 0L
                isPlaying = p.isPlaying
                if (p.isPlaying) saveProgress(force = false)
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        repo = MediaRepository(applicationContext)
        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, 0L)

        player = PlayerFactory.createTvPlayer(this)

        setContent {
            MyVidHubTheme {
                TvPlayerScreen()
            }
        }

        loadInitial()
    }

    // ---------- 装载 ----------

    private fun loadInitial() {
        lifecycleScope0 {
            val item = runCatching { repo.getMedia(mediaId) }.getOrNull()
            if (item == null) {
                toast = "找不到该媒体"
                return@lifecycleScope0
            }
            current = item
            titleText = displayTitle(item)
            play(item, item.positionMs)
            refreshTracks()
            val list = runCatching { repo.episodes(item.seriesKey) }.getOrDefault(emptyList())
            episodes = list.filter { it.seriesKey == item.seriesKey }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            refreshTracks()
        }
    }

    private fun play(item: MediaEntity, startMs: Long) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(item.uri))
        p.prepare()
        if (startMs > 3_000L) p.seekTo(startMs)
        p.setPlaybackSpeed(speed)
        p.playWhenReady = true
        titleText = displayTitle(item)
    }

    private fun switchEpisode(ep: MediaEntity) {
        current = ep
        play(ep, ep.positionMs)
        panelTab = null
        tick++
    }

    // ---------- 进度保存 ----------

    /**
     * 用独立 scope 保存进度。
     * Activity 销毁时 lifecycleScope 会被取消，若仍挂在上面，退出前的最后一笔进度会丢。
     */
    private fun saveProgress(force: Boolean) {
        val item = current ?: return
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSavedAt.get() < 5_000L) return
        lastSavedAt.set(now)

        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = p.duration.takeIf { it > 0L } ?: 0L

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                repo.saveProgress(item.id, pos)
                if (dur > 0L) repo.fillDuration(item.id, dur)
            }
        }
    }

    // ---------- 轨道 ----------

    private fun refreshTracks() {
        val p = player ?: return
        val out = mutableListOf<TvTrack>()

        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> buildString {
                        append(format.label ?: format.language ?: "声道 ${i + 1}")
                        format.channelCount.takeIf { it > 0 }?.let { append(" · ${it}ch") }
                    }
                    else -> format.label ?: format.language ?: "字幕 ${i + 1}"
                }
                out += TvTrack(label, group.type, group, i, group.isTrackSelected(i))
            }
        }
        if (out.none { it.type == C.TRACK_TYPE_TEXT }) {
            out += TvTrack("关闭字幕", C.TRACK_TYPE_TEXT, null, -1, textDisabled)
        }
        trackOptions = out
    }

    private fun selectTrack(track: TvTrack) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (track.type == C.TRACK_TYPE_TEXT && track.trackIndex < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            textDisabled = true
        } else {
            // 关键：从 player.currentTracks 里找当前最新的 group（快照 group 可能已失效）
            val targetMediaGroup = track.group?.mediaTrackGroup ?: return
            val freshGroup = p.currentTracks.groups.firstOrNull { it.mediaTrackGroup === targetMediaGroup }
                ?: return
            builder.setTrackTypeDisabled(track.type, false)
            builder.setOverrideForType(TrackSelectionOverride(freshGroup.mediaTrackGroup, track.trackIndex))
            if (track.type == C.TRACK_TYPE_TEXT) textDisabled = false
        }
        p.trackSelectionParameters = builder.build()
        refreshTracks()
        tick++
    }

    private fun cycleSpeed(value: Float) {
        speed = value
        player?.setPlaybackSpeed(value)
        toast = "倍速 ${fmtSpeed(value)}"
        tick++
    }

    // ---------- 按键 ----------

    private fun seekBy(delta: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0L } ?: return
        val target = (p.currentPosition + delta).coerceIn(0L, dur)
        p.seekTo(target)
        seekHint = "${if (delta > 0) "快进" else "快退"} ${fmtTime(target)} / ${fmtTime(dur)}"
        tick++
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        isPlaying = p.isPlaying
        overlayVisible = true
        tick++
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 面板打开时，方向键交给 Compose 焦点系统在选项间移动
        if (panelTab != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                panelTab = null
                tick++
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                panelTab = null
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        saveProgress(force = true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTicker)
        saveProgress(force = true)
        player?.release()
        player = null
        PlayerFactory.releaseCache()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // 避免与 lifecycle-runtime-ktx 的 lifecycleScope 扩展名混淆，这里包一层
    private fun lifecycleScope0(block: suspend () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { block() }
    }

    // ---------- Compose 界面 ----------

    @OptIn(UnstableApi::class)
    @Composable
    private fun TvPlayerScreen() {
        val ctx = LocalContext.current
        val rootFocus = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            rootFocus.requestFocus()
            handler.post(progressTicker)
        }

        // 3 秒自动隐藏：面板优先收起，其次收起进度层；暂停时不自动隐藏进度条
        LaunchedEffect(tick, panelTab, isPlaying) {
            if (panelTab != null) {
                delay(3_000L)
                panelTab = null
            } else if (overlayVisible && isPlaying && seekHint == null) {
                delay(3_000L)
                overlayVisible = false
            }
        }

        // token 提示自动消失
        LaunchedEffect(toast) {
            if (toast != null) {
                delay(1_600L)
                toast = null
            }
        }
        LaunchedEffect(seekHint) {
            if (seekHint != null) {
                delay(1_200L)
                seekHint = null
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (panelTab != null) return@onPreviewKeyEvent false // 交给选项自己处理
                            togglePlayPause()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (panelTab != null) return@onPreviewKeyEvent false
                            panelTab = TvPanelTab.SPEED
                            overlayVisible = false
                            tick++
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (panelTab != null) return@onPreviewKeyEvent false
                            seekBy(-10_000L)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (panelTab != null) return@onPreviewKeyEvent false
                            seekBy(10_000L)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            togglePlayPause()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            seekBy(30_000L)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            seekBy(-10_000L)
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        // 电视端固定 16:9 适应，不提供比例切换
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        player = this@TvPlayerActivity.player
                    }
                },
                update = { it.player = this@TvPlayerActivity.player }
            )

            // ---------- 进度层 ----------
            AnimatedVisibility(
                visible = overlayVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TvProgressBar(
                    title = titleText,
                    position = positionMs,
                    duration = durationMs,
                    playing = isPlaying
                )
            }

            // ---------- 功能面板（下键唤出） ----------
            AnimatedVisibility(
                visible = panelTab != null,
                enter = slideInVertically { it / 3 } + fadeIn(),
                exit = slideOutVertically { it / 3 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TvFunctionPanel(
                    tab = panelTab ?: TvPanelTab.SPEED,
                    speed = speed,
                    tracks = trackOptions,
                    episodes = episodes,
                    currentId = current?.id ?: 0L,
                    onTabChange = { panelTab = it; tick++ },
                    onSpeed = { cycleSpeed(it); panelTab = null },
                    onTrack = { selectTrack(it); panelTab = null },
                    onEpisode = { switchEpisode(it) }
                )
            }

            // 快进/快退时间提示
            seekHint?.let {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 46.dp)
                        .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }

            toast?.let {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"

        fun intent(context: android.content.Context, mediaId: Long) =
            android.content.Intent(context, TvPlayerActivity::class.java)
                .putExtra(EXTRA_MEDIA_ID, mediaId)

        internal val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

// ---------- 面板数据 ----------

internal enum class TvPanelTab(val label: String) {
    SPEED("倍速"),
    SUBTITLE("字幕"),
    AUDIO("音轨"),
    EPISODE("选集")
}

internal data class TvTrack(
    val label: String,
    val type: Int,
    val group: Tracks.Group?,
    val trackIndex: Int,
    val selected: Boolean
)

// ---------- 进度层 ----------

@Composable
private fun TvProgressBar(
    title: String,
    position: Long,
    duration: Long,
    playing: Boolean
) {
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.82f)
                )
            )
            .padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${fmtTime(position)} / ${fmtTime(duration)}",
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // 进度轨道：已播部分高亮
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.26f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.Brand)
            )
        }

        Text(
            if (playing) "播放中 · OK 暂停 · ↓ 功能" else "已暂停 · OK 继续 · ↓ 功能",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// ---------- 功能面板 ----------

@Composable
private fun TvFunctionPanel(
    tab: TvPanelTab,
    speed: Float,
    tracks: List<TvTrack>,
    episodes: List<MediaEntity>,
    currentId: Long,
    onTabChange: (TvPanelTab) -> Unit,
    onSpeed: (Float) -> Unit,
    onTrack: (TvTrack) -> Unit,
    onEpisode: (MediaEntity) -> Unit
) {
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(tab) { runCatching { tabFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black.copy(alpha = 0.88f),
                    1f to Color.Black.copy(alpha = 0.96f)
                )
            )
            .padding(start = 56.dp, end = 56.dp, top = 46.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 一级标签
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvPanelTab.entries.forEach { t ->
                val selected = t == tab
                TvChip(
                    text = t.label,
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(tabFocus) else Modifier,
                    onClick = { onTabChange(t) }
                )
            }
        }

        // 二级选项
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                TvPanelTab.SPEED -> TvPlayerActivity.SPEEDS.forEach { v ->
                    TvChip(
                        text = fmtSpeed(v),
                        selected = kotlin.math.abs(v - speed) < 0.01f,
                        onClick = { onSpeed(v) }
                    )
                }

                TvPanelTab.SUBTITLE -> {
                    val texts = tracks.filter { it.type == C.TRACK_TYPE_TEXT }
                    if (texts.isEmpty()) {
                        TvHint("该视频没有内嵌字幕")
                    } else {
                        texts.forEach { tr ->
                            TvChip(
                                text = tr.label,
                                selected = tr.selected,
                                onClick = { onTrack(tr) }
                            )
                        }
                    }
                }

                TvPanelTab.AUDIO -> {
                    val audios = tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audios.isEmpty()) {
                        TvHint("该视频只有一条音轨")
                    } else {
                        audios.forEach { tr ->
                            TvChip(
                                text = tr.label,
                                selected = tr.selected,
                                onClick = { onTrack(tr) }
                            )
                        }
                    }
                }

                TvPanelTab.EPISODE -> {
                    if (episodes.isEmpty()) {
                        TvHint("当前不是剧集，没有可选分集")
                    } else {
                        episodes.take(12).forEach { ep ->
                            TvChip(
                                text = if (ep.episode > 0)
                                    "S${ep.season}E${ep.episode}" else ep.title,
                                selected = ep.id == currentId,
                                onClick = { onEpisode(ep) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHint(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.62f),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun TvChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> AppColors.Brand
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
                    (ev.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.key.nativeKeyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else false
            }
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            color = when {
                focused -> Color.Black
                selected -> Color.White
                else -> Color.White.copy(alpha = 0.88f)
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ---------- 工具 ----------

private fun displayTitle(item: MediaEntity): String = when {
    item.kind == com.tabbit.myvidhub.data.model.MediaKind.EPISODE && item.episode > 0 ->
        "${item.title}  S${item.season}E${item.episode}"
    else -> item.title
}

private fun fmtSpeed(v: Float): String {
    val body = if (v == v.toInt().toFloat()) "${v.toInt()}" else "$v"
    return "${body}x"
}

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
