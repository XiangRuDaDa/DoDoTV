package com.tabbit.myvidhub.data.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TMDB (The Movie Database) 刮削客户端。
 *
 * 作为 Emby 刮削的补充：TVBox 源本身没有元数据，Emby 源也可能刮削不全，
 * 这里用 TMDB 公开 API 按「标题 + 年份」搜索并拉取海报/背景图/Logo/评分/简介/演职员。
 *
 * 需要用户在设置里填入 TMDB API Key（v3）。没有 key 时所有方法返回 null，不影响原有流程。
 */
class TmdbClient(private val apiKey: String) {

    /** 刮削结果：与 EmbyClient.ItemDetail 对齐，便于详情页统一渲染 */
    data class ScrapeResult(
        val overview: String = "",
        val rating: Double = 0.0,
        val genres: String = "",
        val posterUrl: String = "",
        val backdropUrl: String = "",
        val logoUrl: String = "",
        val tagline: String = "",
        val directors: List<Person> = emptyList(),
        val actors: List<Person> = emptyList()
    )

    data class Person(val name: String, val role: String, val imageUrl: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        if (apiKey.isBlank()) return null
        val url = StringBuilder("$BASE$path?api_key=$apiKey&language=zh-CN")
        params.forEach { (k, v) -> url.append("&").append(k).append("=").append(v) }
        val req = Request.Builder().url(url.toString())
            .header("Accept", "application/json")
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.let { JSONObject(it) }
            }
        }.getOrNull()
    }

    /**
     * 刮削单个条目。
     * @param title 原始标题
     * @param year  年份，0 表示未知
     * @param kind  MediaKind.MOVIE / SERIES
     */
    suspend fun scrape(title: String, year: Int, kind: String): ScrapeResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || title.isBlank()) return@withContext null

            val id = searchId(title, year, kind) ?: return@withContext null
            val isMovie = kind != "SERIES"
            val detailPath = if (isMovie) "/movie/$id" else "/tv/$id"
            val detail = get(detailPath, mapOf("append_to_response" to "images,credits"))
                ?: return@withContext null

            val images = detail.optJSONObject("images")
            val logoPath = pickLogo(images?.optJSONArray("logos"))
            val posterPath = detail.optString("poster_path", "")
            val backdropPath = detail.optString("backdrop_path", "")

            val credits = detail.optJSONObject("credits")
            val directors = credits?.optJSONArray("crew")
                ?.filterObjects { it.optString("job") == "Director" }
                ?.take(3)
                ?.map {
                    Person(
                        name = it.optString("name", ""),
                        role = it.optString("job", ""),
                        imageUrl = imageUrl(it.optString("profile_path", ""), "w185")
                    )
                }.orEmpty()

            val actors = credits?.optJSONArray("cast")
                ?.filterObjects { it.optInt("order") in 0..19 }
                ?.take(12)
                ?.map {
                    Person(
                        name = it.optString("name", ""),
                        role = it.optString("character", ""),
                        imageUrl = imageUrl(it.optString("profile_path", ""), "w185")
                    )
                }.orEmpty()

            val genres = detail.optJSONArray("genres")
                ?.mapObjects { it.optString("name", "") }
                ?.joinToString(" / ")
                .orEmpty()

            ScrapeResult(
                overview = detail.optString("overview", ""),
                rating = detail.optDouble("vote_average", 0.0),
                genres = genres,
                posterUrl = imageUrl(posterPath, "w500"),
                backdropUrl = imageUrl(backdropPath, "original"),
                logoUrl = imageUrl(logoPath, "original"),
                tagline = detail.optString("tagline", ""),
                directors = directors,
                actors = actors
            )
        }

    /** 搜索并返回最匹配的 TMDB id */
    private fun searchId(title: String, year: Int, kind: String): Int? {
        val isMovie = kind != "SERIES"
        val path = if (isMovie) "/search/movie" else "/search/tv"
        val params = buildMap {
            put("query", title)
            if (year > 0) put(if (isMovie) "year" else "first_air_date_year", year.toString())
        }
        val results = get(path, params)?.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        // 取第一条（TMDB 按相关度排序）
        return results.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    /** 从 logos 数组里挑一张：优先中文，其次英文，再其次任意 */
    private fun pickLogo(logos: JSONArray?): String {
        if (logos == null || logos.length() == 0) return ""
        var fallback = ""
        var en = ""
        for (i in 0 until logos.length()) {
            val obj = logos.optJSONObject(i) ?: continue
            val path = obj.optString("file_path", "")
            if (path.isBlank()) continue
            val lang = obj.optString("iso_639_1", "")
            when (lang) {
                "zh" -> return path
                "en" -> en = path
                else -> if (fallback.isBlank()) fallback = path
            }
        }
        return en.ifBlank { fallback }
    }

    private fun imageUrl(path: String, size: String): String =
        if (path.isBlank()) "" else "$IMAGE_BASE/$size$path"

    private fun JSONArray.filterObjects(predicate: (JSONObject) -> Boolean): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            if (predicate(obj)) out += obj
        }
        return out
    }

    private fun JSONArray.mapObjects(transform: (JSONObject) -> String): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            out += transform(obj)
        }
        return out
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
    }
}
