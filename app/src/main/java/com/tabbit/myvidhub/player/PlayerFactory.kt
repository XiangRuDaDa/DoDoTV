package com.tabbit.myvidhub.player

import android.content.Context
import android.os.StatFs
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
 * 「播一下就转圈」的真正原因不是带宽不够，而是**起播门槛太低**：
 * 旧配置 bufferForPlaybackMs = 1s，意思是只要缓冲到 1 秒数据就立刻开播。
 * 而一部 1080p 影片每秒要吃掉约 1~2MB，网络实际下载速率只要略低于码率，
 * 这 1 秒的余量会在几秒内被吃干，于是立刻 underrun → 转圈 → 再缓冲 → 再播。
 * 用户看到的就是「播一下就转圈」的循环。
 *
 * 因此这里的关键改动是**抬高起播与重缓冲门槛**，让播放器先攒够余量再出画面：
 *   1. bufferForPlaybackMs 1s → 5s：首帧稍晚一点，但换来连续播放；
 *   2. bufferForPlaybackAfterRebufferMs 2.5s → 5s：卡顿后必须重新攒够 5s 才恢复，
 *      避免「恢复 0.5 秒又卡」的抖动；
 *   3. minBufferMs 20s/30s → 30s/45s：网络抖动时靠本地缓冲兜住；
 *   4. maxBufferMs 与 targetBufferBytes 同步放大，防止缓冲到顶后主动停止预读。
 *
 * 磁盘缓存从 1GB 提到 4GB：连续剧来回拖拽、重看同一集时几乎不再走网络。
 */
@UnstableApi
object PlayerFactory {

    /** 磁盘缓存期望上限：手机 1GB */
    private const val CACHE_PREFERRED_PHONE = 1L * 1024 * 1024 * 1024
    /** 磁盘缓存期望上限：电视盒子普遍只有 8~16GB 存储，给 2GB 足够缓存两三部高码率片 */
    private const val CACHE_PREFERRED_TV = 2L * 1024 * 1024 * 1024
    /** 保底缓存下限：低于这个值连一集都存不下，缓存就失去意义了 */
    private const val CACHE_MIN_BYTES = 96L * 1024 * 1024

    /** 起播门槛：攒够 5 秒再出画面，避免「播两秒转一圈」 */
    private const val BUFFER_FOR_PLAYBACK_MS = 5_000

    /** 卡顿后恢复门槛：同样抬到 5 秒，防止恢复后立刻二次卡顿 */
    private const val BUFFER_AFTER_REBUFFER_MS = 5_000

    @Volatile
    private var cache: SimpleCache? = null

    /**
     * 计算磁盘缓存上限。
     *
     * 缓存放在 cacheDir 里，系统在存储紧张时会直接清空，所以不是越大越好：
     * 1. 先按设备类型取期望值（手机 1GB / 电视 2GB）；
     * 2. 再按当前剩余空间收缩到 1/4，避免把用户存储塞满导致安装、拍照、系统更新失败；
     * 3. 最低保底 96MB，否则连一集都存不下。
     */
    private fun cacheLimitFor(context: Context, isTv: Boolean): Long {
        val preferred = if (isTv) CACHE_PREFERRED_TV else CACHE_PREFERRED_PHONE
        val free = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(preferred * 4)
        return minOf(preferred, maxOf(free / 4, CACHE_MIN_BYTES))
    }

    private fun cacheInstance(context: Context, isTv: Boolean): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: run {
                // LRU 淘汰器容量在构造时固定，因此只在首次创建时按当时剩余空间决策
                SimpleCache(
                    File(context.cacheDir, "media_cache"),
                    LeastRecentlyUsedCacheEvictor(cacheLimitFor(context, isTv)),
                    StandaloneDatabaseProvider(context)
                ).also { cache = it }
            }
        }

    /** 手机端：屏幕小、常切前后台，缓冲略收一点以保证内存占用可控 */
    fun createPhonePlayer(context: Context): ExoPlayer = build(context, isTv = false)

    /** 电视端：码率更高、网络更稳，缓冲池更大、超时更宽松 */
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
            .setCache(cacheInstance(context, isTv))
            .setUpstreamDataSourceFactory(http)
            // 缓存写坏时立刻回源，不让坏块把整次播放拖死
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isTv) 45_000 else 30_000,   // 最小缓冲：网络抖动时靠它兜住
                if (isTv) 240_000 else 180_000, // 最大缓冲
                BUFFER_FOR_PLAYBACK_MS,         // 起播门槛：由 1s 提到 5s，消灭「播一下就转圈」
                BUFFER_AFTER_REBUFFER_MS        // 卡顿后重缓冲门槛
            )
            .setTargetBufferBytes(if (isTv) 192 * 1024 * 1024 else 128 * 1024 * 1024)
            // 优先保证时长而不是内存占用：宁可多占一点内存，也别因为到顶而停止预读
            .setPrioritizeTimeOverSizeThresholds(true)
            // 回退缓冲：回拖进度条时不重新走网络
            .setBackBuffer(60_000, true)
            .build()

        // 默认只重试 3 次且退避很快，弱网下经常来不及恢复
        val errorPolicy = DefaultLoadErrorHandlingPolicy(8)

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
