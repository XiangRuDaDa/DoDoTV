package com.tabbit.myvidhub.data.tvbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TVBox 客户端。
 *
 * TVBox 生态里最通用的是「苹果CMS / MacCMS」风格的 JSON 接口（type 1）：
 *   ?ac=videolist&t=分类&pg=页码     → 列表
 *   ?ac=videolist&wd=关键词          → 搜索
 *   ?ac=detail&ids=vodId            → 详情（含播放列表）
 *
 * 也支持直接给一份 TVBox 配置 JSON（内含 sites 数组），取其 type=1 的源。
 */
class TvBoxClient(private val endpoint: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class Vod(
        val vodId: String,
        val name: String,
        val pic: String,
        val remarks: String,
        val year: Int,
        val area: String,
        val typeName: String,
        val content: String,
        val score: Double,
        /** 解析后的播放源列表：源名 → 剧集列表 */
        val playSources: List<PlaySource> = emptyList()
    )

    data class PlaySource(val name: String, val episodes: List<Episode>)
    data class Episode(val name: String, val url: String)

    // ---------- HTTP ----------

    private fun requestJson(params: Map<String, String>): JSONObject? {
        val url = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: return null

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/3.12.0")
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

    // ---------- 列表 / 搜索 / 详情 ----------

    suspend fun homeContent(): List<Vod> = withContext(Dispatchers.IO) {
        val json = requestJson(mapOf("ac" to "videolist", "pg" to "1")) ?: return@withContext emptyList()
        parseList(json.optJSONArray("list"))
    }

    suspend fun category(typeId: String, page: Int = 1): List<Vod> = withContext(Dispatchers.IO) {
        val json = requestJson(
            mapOf("ac" to "videolist", "t" to typeId, "pg" to page.toString())
        ) ?: return@withContext emptyList()
        parseList(json.optJSONArray("list"))
    }

    suspend fun search(keyword: String): List<Vod> = withContext(Dispatchers.IO) {
        val json = requestJson(mapOf("ac" to "videolist", "wd" to keyword)) ?: return@withContext emptyList()
        parseList(json.optJSONArray("list"))
    }

    /** 取详情：拿回播放地址列表（这是实际能播的关键） */
    suspend fun detail(vodId: String): Vod? = withContext(Dispatchers.IO) {
        val json = requestJson(mapOf("ac" to "detail", "ids" to vodId)) ?: return@withContext null
        val list = json.optJSONArray("list") ?: return@withContext null
        if (list.length() == 0) return@withContext null
        parseVod(list.optJSONObject(0) ?: return@withContext null, withPlay = true)
    }

    suspend fun categories(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val json = requestJson(mapOf("ac" to "list")) ?: return@withContext emptyList()
        val classes = json.optJSONArray("class") ?: return@withContext emptyList()
        (0 until classes.length()).mapNotNull { i ->
            val o = classes.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("type_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = o.optString("type_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }
    }

    // ---------- 解析 ----------

    private fun parseList(arr: JSONArray?): List<Vod> {
        if (arr == null) return emptyList()
        val out = ArrayList<Vod>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += parseVod(o, withPlay = false)
        }
        return out
    }

    private fun parseVod(o: JSONObject, withPlay: Boolean): Vod {
        val id = o.optString("vod_id")
        return Vod(
            vodId = id,
            name = o.optString("vod_name"),
            pic = o.optString("vod_pic"),
            remarks = o.optString("vod_remarks"),
            year = o.optString("vod_year").toIntOrNull() ?: 0,
            area = o.optString("vod_area"),
            typeName = o.optString("vod_class").ifBlank { o.optString("type_name") },
            content = HtmlText.strip(o.optString("vod_content")),
            score = o.optString("vod_score").toDoubleOrNull() ?: 0.0,
            playSources = if (withPlay) parsePlaySources(o) else emptyList()
        )
    }

    /**
     * vod_play_from = "线路1$$$线路2"
     * vod_play_url  = "第1集$url#第2集$url$$$第1集$url#..."
     * 分隔符：$$$ 分线路，# 分集，$ 分「集名 / 地址」
     */
    private fun parsePlaySources(o: JSONObject): List<PlaySource> {
        val from = o.optString("vod_play_from")
        val urlStr = o.optString("vod_play_url")
        if (urlStr.isBlank()) return emptyList()

        val fromNames = from.split("$$$").map { it.trim() }
        val groups = urlStr.split("$$$")

        return groups.mapIndexedNotNull { gi, group ->
            val episodes = group.split('#').mapIndexedNotNull { idx, piece ->
                if (piece.isBlank()) return@mapIndexedNotNull null
                val sep = piece.indexOf('$')
                if (sep < 0) {
                    val u = piece.trim()
                    if (u.startsWith("http")) Episode("正片", u) else null
                } else {
                    val name = piece.substring(0, sep).trim().ifBlank { "第${idx + 1}集" }
                    val url = piece.substring(sep + 1).trim()
                    if (url.isBlank()) null else Episode(name, url)
                }
            }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            val name = fromNames.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: "线路${gi + 1}"
            PlaySource(name, episodes)
        }.filter { src ->
            // 只保留 http(s) 直链线路，避免把迅雷/磁力等客户端专用协议塞给 ExoPlayer
            src.episodes.firstOrNull()?.url?.let { it.startsWith("http") } ?: false
        }
    }

}

/** 极简 HTML 清理，用于 CMS 返回的简介字段 */
object HtmlText {
    private val tag = Regex("<[^>]+>")
    private val spaces = Regex("\\s{2,}")
    private val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\""
    )

    fun strip(input: String): String {
        var s = input.replace(tag, " ")
        entities.forEach { (k, v) -> s = s.replace(k, v) }
        return s.replace(spaces, " ").trim()
    }
}
