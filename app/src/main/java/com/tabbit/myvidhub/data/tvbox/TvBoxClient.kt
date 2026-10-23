package com.tabbit.myvidhub.data.tvbox

import android.content.Context
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
 * 支持两类「源」：
 * 1) 苹果CMS / MacCMS 风格 JSON 接口（type 1）：
 *      ?ac=videolist&pg=1     → 列表
 *      ?ac=videolist&wd=关键词 → 搜索
 *      ?ac=detail&ids=vodId   → 详情（含播放列表）
 * 2) TVBox 配置 JSON（形如 {"sites":[...]}）：
 *      · 自动取出 type=1 的 CMS 接口，依次聚合首页列表
 *      · 同时取出 type=3 的 Spider 爬虫站点，通过 [SpiderLoader] 动态加载并反射调用
 *
 * type=3 是 TVBox 现代配置的主力格式，爬虫逻辑封装在 spider JAR 里，
 * 运行时 DexClassLoader 加载 + 反射调用 Spider 基类的 5 个标准方法。
 */
class TvBoxClient(
    endpoint: String,
    context: Context? = null
) {

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

    /** Spider 加载器（type 3 需要 Android Context） */
    private val spiderLoader: SpiderLoader? = context?.applicationContext?.let { SpiderLoader(it) }

    /** 配置根级别的 spider URL（type 3 共用同一个 JAR） */
    private var configSpiderUrl: String = ""

    /** type 3 spider 站点列表 */
    private var spiderSites: List<SpiderSite> = emptyList()

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
        var playSources: List<PlaySource> = emptyList()
    )

    data class PlaySource(val name: String, val episodes: List<Episode>)
    data class Episode(val name: String, val url: String)

    private data class CmsSite(val key: String, val name: String, val api: String)
    private data class SpiderSite(val key: String, val name: String, val api: String, val ext: Any?)

    companion object {
        /** CatVodResultParser 可调用的 URL 归一化（顶级函数，无 origin 上下文做相对 → 绝对转换） */
        internal fun AbsoluteUrl(raw: String): String {
            val url = raw.trim()
            return when {
                url.isBlank() -> ""
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.startsWith("//") -> "https:$url"
                else -> url
            }
        }

        /** 播放 URL 过滤：只保留 http(s) 直链 */
        internal fun AbsolutePlayUrl(raw: String): String? {
            val url = AbsoluteUrl(raw)
            return if (url.startsWith("http://") || url.startsWith("https://")) url else null
        }
    }

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
     * · TVBox 配置：
     *   - type=1 CMS 站点：聚合各自第一页
     *   - type=3 Spider 站点：通过 SpiderLoader 取 homeContent
     */
    suspend fun homeContent(limit: Int = 100): List<Vod> = withContext(Dispatchers.IO) {
        // 先不带任何参数拉一次 —— 如果这是 TVBox 配置 JSON，响应里会有 sites/spider 字段。
        // 如果这是 CMS 接口，响应里会有 list 字段（或 ac=videolist 的数据）。
        val raw = runCatching { requestRaw(base) }.getOrNull()
        val configRoot = raw?.takeIf { it.has("sites") }
        val cmsList = raw?.let { responseList(it) }

        if (configRoot != null) {
            // ===== TVBox 配置 JSON 路径 =====
            val cmsSites = parseCmsSites(configRoot)
            val spSites = parseSpiderSites(configRoot)

            if (cmsSites.isEmpty() && spSites.isEmpty()) return@withContext emptyList()

            configSpiderUrl = configRoot.optString("spider")

            val merged = LinkedHashMap<String, Vod>()
            // 先拉 type 1 CMS 站点
            for (site in cmsSites.take(6)) {
                val json = runCatching {
                    requestJson(site.api, mapOf("ac" to "videolist", "pg" to "1"))
                }.getOrNull() ?: continue
                val list = responseList(json) ?: continue
                parseList(list, site.key, site.name).forEach {
                    merged["${site.key}:${it.vodId}"] = it
                }
                if (merged.size >= limit) break
            }

            // 再拉 type 3 spider 站点
            if (merged.size < limit && spSites.isNotEmpty() && spiderLoader != null && configSpiderUrl.isNotBlank()) {
                loadSpiderHome(spSites, merged, limit)
            }
            spiderSites = spSites
            return@withContext merged.values.take(limit).toList()
        }

        // ===== CMS 接口路径 =====
        if (cmsList != null) {
            return@withContext parseList(cmsList, "", "").take(limit)
        }

        // 兜底：加 ac=videolist 查询参数再试一次
        val retry = runCatching { requestJson(base, mapOf("ac" to "videolist", "pg" to "1")) }.getOrNull()
        retry?.let { responseList(it)?.let { list -> return@withContext parseList(list, "", "").take(limit) } }

        // 再兜底：用 ac=list 取第一个分类
        val firstCategory = runCatching { categories() }.getOrDefault(emptyList()).firstOrNull()
            ?: return@withContext emptyList()
        runCatching { requestJson(base, mapOf("ac" to "videolist", "t" to firstCategory.first, "pg" to "1")) }
            .getOrNull()?.let { responseList(it)?.let { list -> return@withContext parseList(list, "", "").take(limit) } }
        emptyList()
    }

    /** 不带参数的原始请求，用来判断 URL 类型（CMS 还是 TVBox 配置） */
    private fun requestRaw(rawUrl: String): JSONObject {
        val request = Request.Builder().url(rawUrl).get().build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                JSONObject(body)
            }
        }.getOrNull() ?: JSONObject()
    }

    /** type 3 spider 站点首页聚合 */
    private suspend fun loadSpiderHome(
        sites: List<SpiderSite>,
        merged: LinkedHashMap<String, Vod>,
        limit: Int
    ) = withContext(Dispatchers.IO) {
        val loaderKey = runCatching { spiderLoader!!.ensureLoaded(configSpiderUrl) }.getOrNull() ?: return@withContext
        for (site in sites.take(3)) {
            if (merged.size >= limit) break
            val proxy = spiderLoader!!.getSpider(loaderKey, site.api, site.ext) ?: continue
            val json = proxy.homeContent(filter = true) ?: continue
            CatVodResultParser.parseVodList(json, site.key, site.name).forEach {
                merged["${site.key}:${it.vodId}"] = it
            }
        }
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
        // 先尝试直接 CMS 接口（用户填的就是 CMS API 地址）
        if (siteKey.isBlank()) {
            fetchCmsDetail(base, vodId, "", "")?.let { return@withContext it }
        }

        val root = runCatching { requestJson(base, emptyMap()) }.getOrNull()
        val cmsSites = root?.let { parseCmsSites(it) } ?: emptyList()
        val spSites = root?.let { parseSpiderSites(it) } ?: spiderSites

        // 优先按 siteKey 精确匹配
        if (siteKey.isNotBlank()) {
            cmsSites.firstOrNull { it.key == siteKey }?.let { site ->
                fetchCmsDetail(site.api, vodId, site.key, site.name)?.let { return@withContext it }
            }
            // Spider 路径
            if (spSites.isNotEmpty() && root != null) {
                configSpiderUrl = root.optString("spider")
            }
            spSites.firstOrNull { it.key == siteKey }?.let { site ->
                val result = fetchSpiderDetail(site, vodId)
                if (result != null) return@withContext result
            }
        }

        // 兜底：所有 CMS 站点逐个尝试
        for (site in cmsSites) {
            fetchCmsDetail(site.api, vodId, site.key, site.name)?.let { return@withContext it }
        }

        // 再兜底：所有 Spider 站点
        if (spSites.isNotEmpty()) {
            if (configSpiderUrl.isBlank() && root != null) configSpiderUrl = root.optString("spider")
            if (configSpiderUrl.isBlank() && spSites.any { true /* any */ }) {
                // spider URL 可能从之前的 homeContent 已经拿到了
            }
            for (site in spSites) {
                fetchSpiderDetail(site, vodId)?.let { return@withContext it }
            }
        }

        // 最后兜底：纯 CMS 接口
        if (siteKey.isBlank()) {
            fetchCmsDetail(base, vodId, "", "")?.let { return@withContext it }
        }
        null
    }

    private suspend fun fetchSpiderDetail(site: SpiderSite, vodId: String): Vod? {
        val loader = spiderLoader ?: return null
        if (configSpiderUrl.isBlank()) return null
        val loaderKey = runCatching { loader.ensureLoaded(configSpiderUrl) }.getOrNull() ?: return null
        val proxy = loader.getSpider(loaderKey, site.api, site.ext) ?: return null
        val json = proxy.detailContent(listOf(vodId)) ?: return null
        val vod = CatVodResultParser.parseDetail(json, site.key, site.name) ?: return null

        // 如果 playSources 里的播放地址不是 http 直链，还需要 playerContent 解析
        val needsResolve = vod.playSources.any { it.episodes.any { ep ->
            !ep.url.startsWith("http://") && !ep.url.startsWith("https://")
        } }
        if (needsResolve) {
            vod.playSources = vod.playSources.map { src ->
                src.copy(episodes = src.episodes.map { ep ->
                    val resolved = runCatching {
                        val pcJson = proxy.playerContent(src.name, ep.url, emptyList())
                        pcJson?.let { CatVodResultParser.parsePlayer(it) }?.url ?: ep.url
                    }.getOrDefault(ep.url)
                    ep.copy(url = resolved)
                })
            }
        }
        return vod
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

    private fun fetchCmsDetail(api: String, vodId: String, siteKey: String, siteName: String): Vod? {
        val json = runCatching {
            requestJson(api, mapOf("ac" to "detail", "ids" to vodId))
        }.getOrNull() ?: return null
        val list = responseList(json) ?: return null
        if (list.length() == 0) return null
        val obj = list.optJSONObject(0) ?: return null
        return parseVod(obj, withPlay = true).copy(siteKey = siteKey, siteName = siteName)
    }

    /** 配置 JSON 里的 type=1（CMS）站点 */
    private fun parseCmsSites(root: JSONObject): List<CmsSite> {
        val arr = root.optJSONArray("sites") ?: return emptyList()
        val out = ArrayList<CmsSite>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "1") continue
            val api = o.optString("api").trim()
            if (!api.startsWith("http", true)) continue
            val rawKey = o.optString("key").ifBlank { o.optString("name") }.ifBlank { "site$i" }
            val name = o.optString("name").ifBlank { rawKey }
            out += CmsSite(rawKey.replace(":", ""), name, api)
        }
        return out.distinctBy { it.key }
    }

    /** 配置 JSON 里的 type=3（Spider）站点 */
    private fun parseSpiderSites(root: JSONObject): List<SpiderSite> {
        val arr = root.optJSONArray("sites") ?: return emptyList()
        val out = ArrayList<SpiderSite>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "3") continue
            val api = o.optString("api").trim()
            if (api.isBlank()) continue
            val rawKey = o.optString("key").ifBlank { o.optString("name") }.ifBlank { "spider$i" }
            val name = o.optString("name").ifBlank { rawKey }
            val ext = o.opt("ext")
            out += SpiderSite(rawKey.replace(":", ""), name, api, ext)
        }
        return out.distinctBy { it.key }
    }

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
