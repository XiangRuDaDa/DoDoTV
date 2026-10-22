package com.tabbit.myvidhub.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.io.File

/**
 * 播放器工厂 —— 手机端与电视端各自一套参数。
 *
 * 起播慢的根因通常不是带宽，而是三件事：
 *   1. `bufferForPlaybackMs` 太大：明明已经拿到足够数据，还要再等满 2 秒才出画面。
 *      这里压到 1 秒，首帧明显提前。
 *   2. `targetBufferBytes` 用默认值（很小）：高清流会频繁触发内部缓冲回收，
 *      表现为「转圈 → 播两秒 → 再转圈」。按端各给 64MB / 96MB。
 *   3. 失败不重试：跨域/跨境流第一次握手超时就直接报错结束。
 *      换成允许重试 6 次的错误策略，弱网下自愈概率高很多。
 *
 * `minBufferMs` 只影响「播放中继续预取多少」，不影响首帧，所以放宽到 20~30 秒，
 * 换取拖动进度条后不再频繁卡顿。
 */
@UnstableApi
object PlayerFactory {

    /** 磁盘缓存上限：回看同一片段不重复下载，1GB 足够覆盖连续剧的日常来回拖拽 */
    private const val CACHE_MAX_BYTES = 1024L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    private fun cacheInstance(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.cacheDir, "media_cache"),
                LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(context)
            ).also { cache = it }
        }

    /** 手机端：屏幕小、常切前后台，缓冲激进一些以保证滑动响应 */
    fun createPhonePlayer(context: Context): ExoPlayer = build(context, isTv = false)

    /** 电视端：网口带宽通常更稳但码率更高，缓冲池更大、超时更宽松 */
    fun createTvPlayer(context: Context): ExoPlayer = build(context, isTv = true)

    private fun build(context: Context, isTv: Boolean): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            // 超时不能给太长：跨境链路常见的表现是「连上了但不发数据」，
            // 早点失败交给重试策略重新握手，比死等 20 秒更快出画面。
            .setConnectTimeoutMs(if (isTv) 12_000 else 8_000)
            .setReadTimeoutMs(if (isTv) 18_000 else 12_000)
            .setUserAgent(if (isTv) "DouDouTV/3.0 (AndroidTV)" else "DouDouTV/3.0 (Android)")

        val cached = CacheDataSource.Factory()
            .setCache(cacheInstance(context))
            .setUpstreamDataSourceFactory(http)
            // 缓存写坏时立刻回源，不让坏块把整次播放拖死
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isTv) 30_000 else 20_000,   // 最小缓冲：保证拖动后不立刻断流
                if (isTv) 180_000 else 120_000, // 最大缓冲
                1_000,                          // 起播所需：由 2s 压到 1s，首帧提前
                2_500                           // 卡顿后重缓冲门槛
            )
            .setTargetBufferBytes(if (isTv) 96 * 1024 * 1024 else 64 * 1024 * 1024)
            // 优先保证时长而不是内存占用：宁可多占一点内存，也别因为到顶而停止预读
            .setPrioritizeTimeOverSizeThresholds(true)
            // 回退缓冲：回拖进度条时不重新走网络
            .setBackBuffer(30_000, false)
            .build()

        // 默认只重试 3 次且退避很快，弱网下经常来不及恢复
        val errorPolicy = DefaultLoadErrorHandlingPolicy(6)

        val mediaSourceFactory = DefaultMediaSourceFactory(cached)
            .setLoadErrorHandlingPolicy(errorPolicy)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /** 退出应用时释放磁盘缓存（SimpleCache 同一时刻只能被一个实例持有） */
    fun releaseCache() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
