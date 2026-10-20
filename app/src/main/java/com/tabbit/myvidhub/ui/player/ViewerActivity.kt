package com.tabbit.myvidhub.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放页（手机 + TV 共用）。
 *
 * · 遥控器：左/右 = ±10 秒，确认 = 暂停/播放，返回 = 退出（Media3 原生支持）
 * · 触摸：单击唤出控制条，横向拖动 seek
 * · 结束自动接下一集（同 seriesKey 内 episode+1）
 * · 每 5 秒落一次进度，用于首页「继续观看」
 */
@UnstableApi
class ViewerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var current: MediaEntity? = null
    private var lastSavedAt = 0L

    private var titleState by mutableStateOf("")
    private var toastState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

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
                    onViewCreated = { view ->
                        playerView = view
                        player?.let { view.player = it }
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
            titleState = displayTitle(item)
            startPlayback(item)
        }
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

        p.setMediaItem(MediaItem.fromUri(Uri.parse(item.uri)))
        p.prepare()
        if (startPosition > 3_000L) p.seekTo(startPosition)
        p.playWhenReady = true

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playNext()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                toastState = "播放失败：${error.errorCodeName}"
            }
        })
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
                p.setMediaItem(MediaItem.fromUri(Uri.parse(next.uri)))
                p.prepare()
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

        fun intent(context: Context, mediaId: Long): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_MEDIA_ID, mediaId)
    }
}

@UnstableApi
@Composable
private fun PlayerScreen(
    title: String,
    toast: String?,
    onViewCreated: (PlayerView) -> Unit
) {
    var showTitle by remember { mutableStateOf(true) }

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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    controllerShowTimeoutMs = 3_500
                    onViewCreated(this)
                }
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

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                "← →  快进 / 快退     确认键  暂停 / 播放     返回键  退出",
                color = AppColors.TextTertiary.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
