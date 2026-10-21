package com.tabbit.myvidhub.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * 播放器工厂。
 *
 * Emby 与 TVBox 都是标准 HTTP(S) 流，因此不需要自定义数据源；
 * 关键配置只剩两点：
 * · 较大的缓冲区间 —— 网络剧集拖动进度条时不至于频繁卡顿
 * · 512MB 磁盘缓存 —— 回看同一段不重复下载
 */
@UnstableApi
object PlayerFactory {

    private const val CACHE_MAX_BYTES = 512L * 1024 * 1024

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

    fun createPlayer(context: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent("DouDouTV/2.0 (Android)")

        val cached = CacheDataSource.Factory()
            .setCache(cacheInstance(context))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                40_000,   // 最小缓冲：网络剧集留足冗余
                150_000,  // 最大缓冲
                2_000,    // 起播所需
                4_000     // 卡顿后重缓冲
            )
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cached))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    fun releaseCache() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
