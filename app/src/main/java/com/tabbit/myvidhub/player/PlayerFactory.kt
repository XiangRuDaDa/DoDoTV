package com.tabbit.myvidhub.player

import android.content.Context
import android.os.StatFs
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
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
 * 磁盘缓存默认 LRU 自动淘汰，但如果用户长时间不用、缓存越积越多，
 * 可以通过设置页手动清空。关闭单个播放器时也会调用 clearCacheFor(context) 清一次。
 */
@UnstableApi
object PlayerFactory {

    /** 缓存目录名称 */
    private const val CACHE_DIR_NAME = "media_cache"

    /** 磁盘缓存期望上限：手机 256MB（之前 1GB 太激进，看个 20G 电影缓存膨胀到 20G 就是这个原因） */
    private const val CACHE_PREFERRED_PHONE = 256L * 1024 * 1024
    /** 磁盘缓存期望上限：电视盒子 512MB */
    private const val CACHE_PREFERRED_TV = 512L * 1024 * 1024
    /** 保底缓存下限：低于这个值连一集都存不下，缓存就失去意义了 */
    private const val CACHE_MIN_BYTES = 64L * 1024 * 1024

    /** 起播门槛：攒够 5 秒再出画面，避免「播两秒转一圈」 */
    private const val BUFFER_FOR_PLAYBACK_MS = 5_000

    /** 卡顿后恢复门槛：同样抬到 5 秒，防止恢复后立刻二次卡顿 */
    private const val BUFFER_AFTER_REBUFFER_MS = 5_000

    @Volatile
    private var cache: SimpleCache? = null

    /** 计算磁盘缓存上限 */
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
                SimpleCache(
                    File(context.cacheDir, CACHE_DIR_NAME),
                    LeastRecentlyUsedCacheEvictor(cacheLimitFor(context, isTv)),
                    StandaloneDatabaseProvider(context)
                ).also { cache = it }
            }
        }

    /** 手机端：屏幕小、常切前后台，缓冲略收一点以保证内存占用可控 */
    fun createPhonePlayer(
        context: Context,
        /** Emby 反向代理 / 严格鉴权时需要额外 header（如 X-Emby-Authorization） */
        headers: Map<String, String> = emptyMap()
    ): ExoPlayer = build(context, isTv = false, headers = headers)

    /** 电视端：码率更高、网络更稳，缓冲池更大、超时更宽松 */
    fun createTvPlayer(
        context: Context,
        headers: Map<String, String> = emptyMap()
    ): ExoPlayer = build(context, isTv = true, headers = headers)

    private fun build(
        context: Context,
        isTv: Boolean,
        headers: Map<String, String> = emptyMap()
    ): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(if (isTv) 12_000 else 8_000)
            .setReadTimeoutMs(if (isTv) 18_000 else 12_000)
            .setUserAgent(if (isTv) "DouDouTV/3.0 (AndroidTV)" else "DouDouTV/3.0 (Android)")
            .apply {
                if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            }

        // CacheDataSink fragmentSize —— 行业标准做法：把大文件切成 2MB 碎片存入 cache
        // 每个碎片是一个独立的 cache entry，LRU 就能在写入新碎片时淘汰远离当前
        // 播放位置的旧碎片，实现滑动窗口式缓存（Netflix/HLS 天然是这样，Emby 客户端
        // 用这个参数模拟同样效果）。没有这个参数的话，一个 20GB 电影就是一个 entry，
        // LRU 对正在 active 的 entry 毫无办法，缓存只会涨到文件大小。
        val dataSink = CacheDataSink.Factory()
            .setCache(cacheInstance(context, isTv))
            .setFragmentSize(2 * 1024 * 1024) // 2MB 一片，越大 IO 越少但 LRU 粒度越粗

        val cached = CacheDataSource.Factory()
            .setCache(cacheInstance(context, isTv))
            .setUpstreamDataSourceFactory(http)
            .setCacheWriteDataSinkFactory(dataSink)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isTv) 45_000 else 30_000,
                if (isTv) 240_000 else 180_000,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(if (isTv) 192 * 1024 * 1024 else 128 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(60_000, true)
            .build()

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

    /** 读取缓存目录当前占用（字节数），设置页展示用 */
    fun cacheSizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) return 0L
        fun walk(f: File): Long {
            return if (f.isFile) f.length()
            else f.listFiles()?.sumOf { walk(it) } ?: 0L
        }
        return walk(dir)
    }

    /** 清理磁盘缓存。SimpleCache 必须 release 后才能删目录，否则会被锁。 */
    fun clearCache(context: Context) {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
        }
        runCatching {
            val dir = File(context.cacheDir, CACHE_DIR_NAME)
            if (dir.exists()) {
                // 先删 SimpleCache 自己的 db 文件，确保下次创建不冲突
                dir.listFiles()?.forEach { it.deleteRecursively() }
                dir.delete()
            }
        }
    }

    /** 释放当前 SimpleCache 实例（退出应用 / 播放器 Activity onDestroy 时调用） */
    fun releaseCache() {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
        }
    }

    /** 字节数格式化，展示友好 */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "%.1f GB".format(bytes / gb)
            bytes >= mb -> "%.1f MB".format(bytes / mb)
            else -> "%.0f KB".format(bytes / kb)
        }
    }
}
