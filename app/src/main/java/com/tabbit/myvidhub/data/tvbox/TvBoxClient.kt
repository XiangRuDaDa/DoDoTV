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
 * 支持两种「源」：
 * 1) 苹果CMS / MacCMS 风格 JSON 接口（type 1）：
 *      ?ac=videolist&pg=1     → 列表
 *      ?ac=videolist&wd=关键词 → 搜索
 *      ?ac=detail&ids=vodId   → 详情（含播放列表）
 * 2) TVBox 配置 JSON（形如 {"sites":[{"type":1,"api":"..."}]}）：
 *      自动取出其中 type=1 的 CMS 接口，依次聚合首页列表
 *
 * 与之前版本最大的区别：网络/解析失败不再静默返回空，而是抛出带中文说明的异常，
 * 由上层展示给用户 —— 否则用户永远只看到「未获取到内容」，无法定位问题。
 */
class TvBoxClient(endpoint: String) {

    /** 用户可能只填了 192.168.1.2:8080 或 example.com/api.php/provide/vod/，这里自动补协议 */
    private val base: String = endpoint.trim()
        .let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it" }

    private val origin: String = base.toHttpUrlOrNull()?.let { url ->
        val defaultPort = if (url.scheme == "https") 443 else 80
        buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != defaultPort) append(':').append(url.port)
        }
    }.orEmpty()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
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
        /** 来自 TVBox 配置时，标记所属站点，保证 remoteId 不冲突 */
        val siteKey: String = "",
        val siteName: String = "",
        /** 解析后的播放源列表：源名 → 剧集列表 */
        val playSources: List<PlaySource> = emptyList()
    )

    data class PlaySource(val name: String, val episodes: List<Episode>)
    data class Episode(val name: String, val url: String)

    private data class CmsSite(val key: String, val name: String, val api: String)

    // ---------- HTTP ----------

    private fun requestJson(baseUrl: String, params: Map<String, String>): JSONObject {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: throw IllegalStateException("接口地址无效：$baseUrl（需以 http:// 或 https:// 开头）")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/3.12.0")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("接口返回 HTTP ${response.code}：$baseUrl")
                }
                if (body.isBlank()) throw IllegalStateException("接口返回内容为空：$baseUrl")
                return runCatching { JSONObject(body) }.getOrElse {
                    throw IllegalStateException("接口返回的不是 JSON：$baseUrl（请确认填的是 CMS 接口或 TVBox 配置地址）")
                }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("无法连接接口：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 兼容 {"list":[]} 与 {"data":{"list":[]}} 两种包装 */
    private fun responseList(json: JSONObject): JSONArray? =
        json.optJSONArray("list") ?: json.optJSONObject("data")?.optJSONArray("list")

    // ---------- 列表 / 搜索 / 详情 ----------

    /**
     * 首页列表。
     * · 普通 CMS 接口：直接取第一页
     * · TVBox 配置：解析出所有 type=1 站点，聚合各自第一页，最多返回 limit 条
     * · 部分 CMS 在缺少 t（分类）时返回空：自动取 ac=list 的第一个分类重试
     */
    suspend fun homeContent(limit: Int = 100): List<Vod> = withContext(Dispatchers.IO) {
        val root = requestJson(base, mapOf("ac" to "videolist", "pg" to "1"))

        responseList(root)?.let { return@withContext parseList(it, "", "").take(limit) }

        val sites = parseSites(root)
        if (sites.isNotEmpty()) {
            val merged = LinkedHashMap<String, Vod>()
            for (site in sites.take(8)) {
                val json = runCatching {
                    requestJson(site.api, mapOf("ac" to "videolist", "pg" to "1"))
                }.getOrNull() ?: continue
                val list = responseList(json) ?: continue
                parseList(list, site.key, site.name).forEach {
                    merged["${site.key}:${it.vodId}"] = it
                }
                if (merged.size >= limit) break
            }
            return@withContext merged.values.take(limit).toList()
        }

        // 兜底：先拿分类，再按第一个分类拉列表
        val firstCategory = runCatching { categories() }.getOrDefault(emptyList()).firstOrNull()
            ?: return@withContext emptyList()
        val retry = requestJson(base, mapOf("ac" to "videolist", "t" to firstCategory.first, "pg" to "1"))
        responseList(retry)?.let { return@withContext parseList(it, "", "").take(limit) }
        emptyList()
    }

    suspend fun category(typeId: String, page: Int = 1): List<Vod> = withContext(Dispatchers.IO) {
        val json = requestJson(base, mapOf("ac" to "videolist", "t" to typeId, "pg" to page.toString()))
        parseList(responseList(json), "", "")
    }

    suspend fun search(keyword: String): List<Vod> = withContext(Dispatchers.IO) {
        val json = requestJson(base, mapOf("ac" to "videolist", "wd" to keyword))
        parseList(responseList(json), "", "")
    }

    /**
     * 取详情：拿回播放地址列表（这是实际能播的关键）。
     * @param siteKey 来自 TVBox 配置时按站点定位接口；空表示直接使用填写的接口
     */
    suspend fun detail(vodId: String, siteKey: String = ""): Vod? = withContext(Dispatchers.IO) {
        if (siteKey.isBlank()) {
            fetchDetail(base, vodId, "", "")?.let { return@withContext it }
        } else {
            val sites = runCatching { resolveSites() }.getOrDefault(emptyList())
            sites.firstOrNull { it.key == siteKey }?.let { site ->
                fetchDetail(site.api, vodId, site.key, site.name)?.let { return@withContext it }
            }
            // 配置更新后 key 可能变化，逐个尝试
            for (site in sites) {
                fetchDetail(site.api, vodId, site.key, site.name)?.let { return@withContext it }
            }
            return@withContext null
        }

        // 填的是 TVBox 配置、但旧数据没有 siteKey：逐个站点尝试
        for (site in runCatching { resolveSites() }.getOrDefault(emptyList())) {
            fetchDetail(site.api, vodId, site.key, site.name)?.let { return@withContext it }
        }
        null
    }

    suspend fun categories(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val json = requestJson(base, mapOf("ac" to "list"))
        val classes = json.optJSONArray("class") ?: return@withContext emptyList()
        (0 until classes.length()).mapNotNull { i ->
            val o = classes.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("type_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = o.optString("type_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }
    }

    // ---------- 内部 ----------

    private fun fetchDetail(api: String, vodId: String, siteKey: String, siteName: String): Vod? {
        val json = runCatching {
            requestJson(api, mapOf("ac" to "detail", "ids" to vodId))
        }.getOrNull() ?: return null
        val list = responseList(json) ?: return null
        if (list.length() == 0) return null
        val obj = list.optJSONObject(0) ?: return null
        return parseVod(obj, withPlay = true).copy(siteKey = siteKey, siteName = siteName)
    }

    /** 配置 JSON 里的 type=1（CMS）站点 */
    private fun parseSites(root: JSONObject): List<CmsSite> {
        val arr = root.optJSONArray("sites") ?: return emptyList()
        val out = ArrayList<CmsSite>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "1") continue
            val api = o.optString("api").trim()
            if (!api.startsWith("http", true)) continue
            val rawKey = o.optString("key").ifBlank { o.optString("name") }.ifBlank { "site$i" }
            val name = o.optString("name").ifBlank { rawKey }
            // key 会拼进 remoteId，去掉分隔符避免解析歧义
            out += CmsSite(rawKey.replace(":", ""), name, api)
        }
        return out.distinctBy { it.key }
    }

    private fun resolveSites(): List<CmsSite> =
        parseSites(requestJson(base, mapOf("ac" to "videolist", "pg" to "1")))

    private fun parseList(arr: JSONArray?, siteKey: String, siteName: String): List<Vod> {
        if (arr == null) return emptyList()
        val out = ArrayList<Vod>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val vod = parseVod(o, withPlay = false)
            if (vod.vodId.isBlank()) continue
            out += vod.copy(siteKey = siteKey, siteName = siteName)
        }
        return out
    }

    private fun parseVod(o: JSONObject, withPlay: Boolean): Vod {
        val id = o.optString("vod_id")
        return Vod(
            vodId = id,
            name = o.optString("vod_name"),
            pic = absoluteUrl(o.optString("vod_pic")),
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

        return groups.mapIndexedNotNull group@{ gi, group ->
            val episodes = group.split('#').mapIndexedNotNull episode@{ idx, piece ->
                if (piece.isBlank()) return@episode null
                val sep = piece.indexOf('$')
                if (sep < 0) {
                    val url = absolutePlayUrl(piece.trim()) ?: return@episode null
                    Episode("正片", url)
                } else {
                    val name = piece.substring(0, sep).trim().ifBlank { "第${idx + 1}集" }
                    val url = absolutePlayUrl(piece.substring(sep + 1).trim())
                        ?: return@episode null
                    Episode(name, url)
                }
            }
            if (episodes.isEmpty()) return@group null
            val name = fromNames.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: "线路${gi + 1}"
            PlaySource(name, episodes)
        }
    }

    /** 只保留 http(s) 直链线路，避免把迅雷/磁力等客户端专用协议塞给 ExoPlayer */
    private fun absolutePlayUrl(raw: String): String? =
        absoluteUrl(raw).takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** 兼容 //host/x、/path/x 这类相对地址 */
    private fun absoluteUrl(raw: String): String {
        val url = raw.trim()
        return when {
            url.isBlank() -> ""
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") && origin.isNotBlank() -> origin + url
            else -> url
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
