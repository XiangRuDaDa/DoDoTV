package com.tabbit.myvidhub.data.emby

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Emby 客户端。
 *
 * 只用公开的 REST API，覆盖三件事：
 * 1) 用户名密码换取 AccessToken（Authorization: MediaBrowser 头）
 * 2) 拉取媒体库里的电影与剧集（含海报/背景图/简介/评分）
 * 3) 生成可直接交给 ExoPlayer 的播放直链
 */
class EmbyClient(
    private val serverUrl: String,
    private val token: String = ""
) {

    private val base: String = serverUrl.trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class Auth(val userId: String, val token: String, val userName: String)

    data class Item(
        val id: String,
        val name: String,
        val isSeries: Boolean,
        val year: Int,
        val overview: String,
        val posterUrl: String,
        val backdropUrl: String,
        val rating: Double,
        val genres: String,
        val runTimeMs: Long,
        val indexNumber: Int,
        val parentIndexNumber: Int,
        val seriesId: String
    )

    private fun authHeader(): String =
        "MediaBrowser Client=\"MyVidHub\", Device=\"Android\", DeviceId=\"myvidhub\", " +
            "Version=\"1.0.0\", Token=\"$token\""

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val url = ("$base$path").toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            if (token.isNotBlank()) addQueryParameter("api_key", token)
        }?.build() ?: return null

        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("Accept", "application/json")
            .get()
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                JSONObject(body)
            }
        }.getOrNull()
    }

    /** 账号密码登录换取 token */
    suspend fun authenticate(userName: String, password: String): Auth? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$base/Users/AuthenticateByName".toHttpUrlOrNull() ?: return@runCatching null
                val payload = JSONObject().apply {
                    put("Username", userName)
                    put("Pw", password)
                }.toString()

                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    val user = json.optJSONObject("User")
                    val accessToken = json.optString("AccessToken")
                    if (accessToken.isBlank()) return@use null
                    Auth(
                        userId = user?.optString("Id").orEmpty(),
                        token = accessToken,
                        userName = user?.optString("Name").orEmpty()
                    )
                }
            }.getOrNull()
        }

    /** 获取媒体库（电影 + 剧集）条目 */
    suspend fun fetchLibrary(limit: Int = 600): List<Item> = withContext(Dispatchers.IO) {
        val userId = currentUserId() ?: return@withContext emptyList()

        val json = get(
            "/Users/$userId/Items",
            mapOf(
                "IncludeItemTypes" to "Movie,Series",
                "Recursive" to "true",
                "Fields" to "Overview,Genres,CommunityRating,RunTimeTicks,ProductionYear,PrimaryImageAspectRatio",
                "ImageTypeLimit" to "1",
                "EnableImageTypes" to "Primary,Backdrop",
                "SortBy" to "DateCreated",
                "SortOrder" to "Descending",
                "Limit" to limit.toString()
            )
        ) ?: return@withContext emptyList()

        parseItems(json.optJSONArray("Items"))
    }

    /** 剧集的季与分集 */
    suspend fun fetchEpisodes(seriesId: String): List<Item> = withContext(Dispatchers.IO) {
        val json = get(
            "/Shows/$seriesId/Episodes",
            mapOf(
                "Fields" to "Overview,RunTimeTicks,ProductionYear",
                "EnableImageTypes" to "Primary",
                "SortBy" to "ParentIndexNumber,IndexNumber"
            )
        ) ?: return@withContext emptyList()

        parseItems(json.optJSONArray("Items"))
    }

    /** 搜索（TV 端全局搜索用） */
    suspend fun search(keyword: String, limit: Int = 40): List<Item> = withContext(Dispatchers.IO) {
        val userId = currentUserId() ?: return@withContext emptyList()
        val json = get(
            "/Users/$userId/Items",
            mapOf(
                "SearchTerm" to keyword,
                "IncludeItemTypes" to "Movie,Series",
                "Recursive" to "true",
                "Fields" to "Overview,Genres,CommunityRating,ProductionYear",
                "Limit" to limit.toString()
            )
        ) ?: return@withContext emptyList()
        parseItems(json.optJSONArray("Items"))
    }

    private var cachedUserId: String? = null

    private fun currentUserId(): String? {
        cachedUserId?.let { return it }
        val json = get("/Users/Me") ?: return null
        val id = json.optString("Id").takeIf { it.isNotBlank() } ?: return null
        cachedUserId = id
        return id
    }

    private fun parseItems(arr: JSONArray?): List<Item> {
        if (arr == null) return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("Id")
            if (id.isBlank()) continue
            val type = o.optString("Type")
            val imageTags = o.optJSONObject("ImageTags")
            val backdropTags = o.optJSONObject("BackdropImageTags")

            val primaryTag = imageTags?.optString("Primary").orEmpty()
            val backdropTag = backdropTags?.let {
                if (it.length() > 0) it.optString(it.keys().next()) else ""
            }.orEmpty()

            out += Item(
                id = id,
                name = o.optString("Name"),
                isSeries = type == "Series",
                year = o.optInt("ProductionYear"),
                overview = o.optString("Overview"),
                posterUrl = if (primaryTag.isNotBlank())
                    "$base/Items/$id/Images/Primary?tag=$primaryTag&maxHeight=600&quality=90" else "",
                backdropUrl = if (backdropTag.isNotBlank())
                    "$base/Items/$id/Images/Backdrop?tag=$backdropTag&maxWidth=1280&quality=90" else "",
                rating = o.optDouble("CommunityRating", 0.0),
                genres = o.optJSONArray("Genres")?.let { g ->
                    (0 until g.length()).mapNotNull { g.optString(it).takeIf { s -> s.isNotBlank() } }
                        .joinToString(" / ")
                }.orEmpty(),
                runTimeMs = (o.optLong("RunTimeTicks") / 10_000L),
                indexNumber = o.optInt("IndexNumber"),
                parentIndexNumber = o.optInt("ParentIndexNumber"),
                seriesId = o.optString("SeriesId")
            )
        }
        return out
    }

    companion object {
        /**
         * Emby 播放直链。
         * static=true 表示不转码直接原码流传输 —— 对局域网与性能最好的选择。
         * 若客户端解码能力不足，可改为 false 让服务器转码。
         */
        fun playbackUrl(serverUrl: String, itemId: String, token: String, static: Boolean = true): String {
            val base = serverUrl.trimEnd('/')
            return "$base/Videos/$itemId/stream?static=$static&api_key=$token"
        }

        /** 字幕轨道（外挂 srt/ass） */
        fun subtitleUrl(serverUrl: String, itemId: String, index: Int, token: String): String {
            val base = serverUrl.trimEnd('/')
            return "$base/Videos/$itemId/Subtitles/$index/Stream.srt?api_key=$token"
        }

        fun isValidServer(url: String): Boolean {
            val u = Uri.parse(url.trim())
            return (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
        }
    }
}
