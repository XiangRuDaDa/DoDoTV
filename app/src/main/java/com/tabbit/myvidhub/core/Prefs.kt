package com.tabbit.myvidhub.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量本地配置。
 * Emby 的 AccessToken 存在数据库里（可续期），这里只放应用级偏好。
 */
object Prefs {

    private const val FILE = "myvidhub_prefs"
    private const val KEY_TMDB_API_KEY = "tmdb_api_key"
    private const val KEY_TMDB_READ_TOKEN = "tmdb_read_token"
    private const val KEY_TMDB_IMAGE_BASE = "tmdb_image_base"
    private const val KEY_TONE_PREFIX = "detail_tone_"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * TMDB API Key（v3 auth）。
     * 走 query 参数 `?api_key=...`，是最常用的方式。
     */
    var tmdbApiKey: String
        get() = sp.getString(KEY_TMDB_API_KEY, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_TMDB_API_KEY, value.trim()).apply()
        }

    /**
     * TMDB Read Access Token（v4 auth）。
     * 走 HTTP 头 `Authorization: Bearer ...`，和 v3 Key 二选一即可。
     * 两者都填时优先用 v4 令牌，因为它不会把密钥暴露在 URL 里。
     */
    var tmdbReadToken: String
        get() = sp.getString(KEY_TMDB_READ_TOKEN, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_TMDB_READ_TOKEN, value.trim()).apply()
        }

    /**
     * TMDB 图片域名替换（可选）。
     *
     * 国内直连 image.tmdb.org 往往很慢甚至失败，这是刮削「图片出不来、海报迟迟不显示」的主因。
     * 填一个可用的镜像地址后，所有 image.tmdb.org 请求都会自动改写到该域名，
     * 图片加载速度通常有数倍提升。留空则继续用官方域名。
     *
     * 填法：只要域名部分即可，例如 https://tmdb-image.example.com
     * 若镜像带路径前缀（如 https://cdn.example.com/tmdb），也会自动拼在前面。
     */
    var tmdbImageBase: String
        get() = sp.getString(KEY_TMDB_IMAGE_BASE, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_TMDB_IMAGE_BASE, value.trim().trimEnd('/')).apply()
        }

    /**
     * 详情页主色持久化（按标题做 key）。
     *
     * 只存在内存里的话，冷启动第一次进详情页仍然要等海报下载完才能取到颜色，
     * 那段时间页面只能退回近黑的兜底色 —— 这就是「刚进详情页海报下方是黑的」的直接原因。
     * 把算好的主色按标题落盘，下次进入时第一帧就能直接用上，不用等图片。
     */
    fun detailTone(title: String): Int {
        if (title.isBlank()) return 0
        return sp.getInt(KEY_TONE_PREFIX + title.hashCode(), 0)
    }

    fun putDetailTone(title: String, argb: Int) {
        if (title.isBlank()) return
        sp.edit().putInt(KEY_TONE_PREFIX + title.hashCode(), argb).apply()
    }

    /** 是否已配置任一 TMDB 凭据 */
    val tmdbConfigured: Boolean
        get() = tmdbApiKey.isNotBlank() || tmdbReadToken.isNotBlank()
}
