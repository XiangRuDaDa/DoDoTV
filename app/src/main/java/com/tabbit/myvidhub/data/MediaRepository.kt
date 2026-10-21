package com.tabbit.myvidhub.data

import android.content.Context
import com.tabbit.myvidhub.MyVidHubApp
import com.tabbit.myvidhub.data.db.AppDatabase
import com.tabbit.myvidhub.data.emby.EmbyClient
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.tvbox.TvBoxClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 媒体库仓储。
 *
 * 两类来源走两条完全不同的入库路径：
 * · Emby：一次拉回库里的电影/剧集，剧集再按 seriesId 逐部取分集
 * · TVBox：列表只入库「剧集壳」，分集等用户点进详情页再按需拉取
 *   （CMS 接口的详情才带播放地址，提前全量拉取会打爆请求量）
 */
class MediaRepository(context: Context) {

    private val db: AppDatabase = MyVidHubApp.instance.db
    private val sourceDao = db.sourceDao()
    private val mediaDao = db.mediaDao()

    fun observeSources(): Flow<List<SourceEntity>> = sourceDao.observeAll()
    fun observeLibrary(): Flow<List<MediaEntity>> = mediaDao.observeLibrary()
    fun observeContinueWatching(): Flow<List<MediaEntity>> = mediaDao.observeContinueWatching()
    fun observeFeatured(): Flow<List<MediaEntity>> = mediaDao.observeFeatured()
    fun observeFavorites(): Flow<List<MediaEntity>> = mediaDao.observeFavorites()

    suspend fun search(keyword: String): List<MediaEntity> = mediaDao.search(keyword)
    suspend fun setFavorite(id: Long, favorite: Boolean) = mediaDao.setFavorite(id, favorite)

    suspend fun addSource(source: SourceEntity): Long = sourceDao.insert(source)
    suspend fun getSource(id: Long): SourceEntity? = sourceDao.get(id)
    suspend fun allSources(): List<SourceEntity> = sourceDao.getAll()
    suspend fun enabledSources(): List<SourceEntity> = sourceDao.getEnabled()
    suspend fun updateSource(source: SourceEntity) = sourceDao.update(source)
    suspend fun setSourceEnabled(id: Long, enabled: Boolean) = sourceDao.setEnabled(id, enabled)

    suspend fun deleteSource(source: SourceEntity) {
        mediaDao.deleteBySource(source.id)
        sourceDao.delete(source)
    }

    suspend fun getMedia(id: Long): MediaEntity? = mediaDao.get(id)
    suspend fun byRemoteId(remoteId: String): MediaEntity? = mediaDao.byRemoteId(remoteId)
    suspend fun episodes(seriesKey: String): List<MediaEntity> = mediaDao.episodes(seriesKey)
    suspend fun seasons(seriesKey: String): List<Int> = mediaDao.seasons(seriesKey)
    suspend fun hasAnyMedia(): Boolean = mediaDao.countAll() > 0

    // ---------------- 首页分类 / 浏览页 ----------------

    /** 首页画廊：每类只取最新 N 条（懒加载，避免整库刷进内存） */
    fun observeRecent(kind: String, limit: Int): Flow<List<MediaEntity>> =
        mediaDao.observeRecent(kind, limit)

    fun observeAnime(limit: Int): Flow<List<MediaEntity>> = mediaDao.observeAnime(limit)

    /** 分类浏览页分页查询 */
    suspend fun byGenre(kind: String?, genre: String?, limit: Int, offset: Int): List<MediaEntity> =
        mediaDao.byGenre(kind, genre, limit, offset)

    /** 类型标签：拆分聚合后按出现次数排序 */
    suspend fun genreTags(limit: Int = 16): List<String> =
        mediaDao.distinctGenres()
            .flatMap { it.split(" / ", "/", ",", "，", " ") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    /**
     * 按 remoteId 写入元数据。
     * 已存在时保留 id / 播放进度 / 收藏 / 添加时间，只刷新标题、海报与播放地址。
     * 不能用 upsert(REPLACE)：那会删行重插，进度和收藏都会丢。
     */
    private suspend fun saveMedia(entity: MediaEntity) {
        val existing = mediaDao.byRemoteId(entity.remoteId)
        if (existing == null) {
            mediaDao.insert(entity)
        } else {
            mediaDao.update(
                entity.copy(
                    id = existing.id,
                    positionMs = existing.positionMs,
                    lastPlayedAt = existing.lastPlayedAt,
                    favorite = existing.favorite,
                    addedAt = existing.addedAt,
                    durationMs = if (entity.durationMs > 0) entity.durationMs else existing.durationMs
                )
            )
        }
    }

    // ---------------- Emby ----------------

    /**
     * Emby 登录并保存 token。
     * @return null 表示成功，否则返回错误信息
     */
    suspend fun embyLogin(source: SourceEntity): String? = withContext(Dispatchers.IO) {
        try {
            val client = EmbyClient(source.location)
            val auth = client.authenticate(source.username, source.password)
            sourceDao.update(
                source.copy(token = auth.token, userId = auth.userId)
            )
            null
        } catch (e: Exception) {
            e.message ?: "登录失败：请检查服务器地址、用户名或密码"
        }
    }

    /**
     * 同步 Emby 媒体库 —— 整库只发一次列表请求。
     *
     * 与 VidHub 等主流播放器一致：列表阶段只入库「电影 + 剧集壳」，
     * 分集在用户点进详情页时按需加载（loadEmbyDetail）。
     * 之前逐部剧集循环拉分集，几十部剧就是几十个请求，又慢又容易被服务器限流。
     */
    suspend fun syncEmby(source: SourceEntity, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            if (source.token.isBlank()) {
                throw IllegalStateException("Emby 尚未登录，请先点刷新重新登录")
            }
            val client = EmbyClient(source.location, source.token, source.userId)

            // 旧版本剧集壳的 remoteId 没带 sourceId，会与新数据重复，先清掉
            mediaDao.deleteLegacyEmbyShells(source.id)

            val items = client.fetchLibrary()
            if (items.isEmpty()) return@withContext 0

            var count = 0
            for (item in items) {
                // remoteId 统一带 sourceId：多台 Emby 时不会因 itemId 相同而互相覆盖
                val key = "emby:${source.id}:${item.id}"
                val isSeries = item.isSeries
                saveMedia(
                    MediaEntity(
                        sourceId = source.id,
                        uri = if (isSeries) "" else EmbyClient.playbackUrl(source.location, item.id, source.token),
                        remoteId = key,
                        seriesKey = if (isSeries) key else "",
                        title = item.name,
                        kind = if (isSeries) MediaKind.SERIES else MediaKind.MOVIE,
                        year = item.year,
                        overview = item.overview,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        rating = item.rating,
                        genres = item.genres,
                        durationMs = item.runTimeMs
                    )
                )
                count++
                if (count % 20 == 0) onProgress(count)
            }
            count
        }

    /**
     * 详情页按需拉取 Emby 分集并入库。
     * @return true 表示拿到了分集
     */
    suspend fun loadEmbyDetail(source: SourceEntity, remoteId: String): Boolean =
        withContext(Dispatchers.IO) {
            // remoteId 形如 emby:{sourceId}:{itemId}
            val itemId = remoteId.removePrefix("emby:${source.id}:")
            if (itemId.isBlank() || ':' in itemId) return@withContext false
            val shell = mediaDao.byRemoteId(remoteId) ?: return@withContext false
            val seriesKey = shell.seriesKey.ifBlank { remoteId }

            val client = EmbyClient(source.location, source.token, source.userId)
            val episodes = client.fetchEpisodes(itemId)
            episodes.forEachIndexed { index, ep ->
                saveMedia(
                    MediaEntity(
                        sourceId = source.id,
                        uri = EmbyClient.playbackUrl(source.location, ep.id, source.token),
                        remoteId = "emby:${source.id}:${ep.id}",
                        seriesKey = seriesKey,
                        title = ep.name.ifBlank { "第 ${index + 1} 集" },
                        kind = MediaKind.EPISODE,
                        season = ep.parentIndexNumber,
                        episode = ep.indexNumber,
                        overview = ep.overview,
                        durationMs = ep.runTimeMs,
                        posterUrl = ep.posterUrl,
                        backdropUrl = shell.backdropUrl
                    )
                )
            }
            episodes.isNotEmpty()
        }

    // ---------------- TVBox ----------------

    /** 同步 TVBox 首页列表（只入库剧集壳） */
    suspend fun syncTvBox(source: SourceEntity, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val client = TvBoxClient(source.location)
            val list = client.homeContent()
            if (list.isEmpty()) {
                throw IllegalStateException("接口未返回任何影片，请确认地址能返回 ac=videolist 的 JSON")
            }

            var count = 0
            for (vod in list) {
                val key = tvboxKey(source.id, vod.siteKey, vod.vodId)
                saveMedia(
                    MediaEntity(
                        sourceId = source.id,
                        uri = "",
                        remoteId = key,
                        seriesKey = key,
                        title = vod.name,
                        kind = MediaKind.SERIES,
                        year = vod.year,
                        overview = vod.content,
                        posterUrl = vod.pic,
                        backdropUrl = vod.pic,
                        rating = vod.score,
                        genres = vod.typeName
                    )
                )
                count++
                if (count % 10 == 0) onProgress(count)
            }
            count
        }

    /**
     * 拉取 TVBox 详情并入库分集。
     * 由详情页触发，返回值供 UI 立即展示线路列表。
     */
    suspend fun loadTvBoxDetail(
        source: SourceEntity,
        remoteId: String
    ): List<TvBoxClient.PlaySource> = withContext(Dispatchers.IO) {
        val (siteKey, vodId) = parseTvBoxKey(source.id, remoteId)
        val client = TvBoxClient(source.location)
        val detail = client.detail(vodId, siteKey) ?: return@withContext emptyList()

        val shell = mediaDao.byRemoteId(remoteId)
        val seriesKey = shell?.seriesKey?.takeIf { it.isNotBlank() } ?: remoteId

        // 用详情补全海报与简介
        shell?.let {
            mediaDao.update(
                it.copy(
                    overview = detail.content.ifBlank { it.overview },
                    posterUrl = detail.pic.ifBlank { it.posterUrl },
                    backdropUrl = detail.pic.ifBlank { it.backdropUrl },
                    rating = if (detail.score > 0) detail.score else it.rating,
                    year = if (detail.year > 0) detail.year else it.year
                )
            )
        }

        // 取第一条可用线路入库
        val primary = detail.playSources.firstOrNull() ?: return@withContext detail.playSources

        primary.episodes.forEachIndexed { index, ep ->
            saveMedia(
                MediaEntity(
                    sourceId = source.id,
                    uri = ep.url,
                    remoteId = "$remoteId:$index",
                    seriesKey = seriesKey,
                    title = ep.name,
                    kind = MediaKind.EPISODE,
                    season = 1,
                    episode = index + 1,
                    overview = detail.content,
                    posterUrl = detail.pic,
                    backdropUrl = detail.pic
                )
            )
        }

        detail.playSources
    }

    /** TVBox 条目唯一键：配置型源可能含多个站点，用 siteKey 区分 */
    private fun tvboxKey(sourceId: Long, siteKey: String, vodId: String): String =
        if (siteKey.isBlank()) "tvbox:$sourceId:$vodId" else "tvbox:$sourceId:$siteKey:$vodId"

    private fun parseTvBoxKey(sourceId: Long, remoteId: String): Pair<String, String> {
        val rest = remoteId.removePrefix("tvbox:$sourceId:")
        val idx = rest.lastIndexOf(':')
        return if (idx > 0) rest.substring(0, idx) to rest.substring(idx + 1) else "" to rest
    }

    // ---------------- 进度 ----------------

    suspend fun saveProgress(mediaId: Long, positionMs: Long) {
        mediaDao.saveProgress(mediaId, positionMs, System.currentTimeMillis())
    }

    suspend fun fillDuration(mediaId: Long, durationMs: Long) {
        mediaDao.fillDuration(mediaId, durationMs)
    }
}
