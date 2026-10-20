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

    // ---------------- Emby ----------------

    /**
     * Emby 登录并保存 token。
     * @return null 表示成功，否则返回错误信息
     */
    suspend fun embyLogin(source: SourceEntity): String? = withContext(Dispatchers.IO) {
        val client = EmbyClient(source.location)
        val auth = client.authenticate(source.username, source.password)
            ?: return@withContext "登录失败：请检查服务器地址、用户名或密码"

        sourceDao.update(
            source.copy(token = auth.token, userId = auth.userId)
        )
        null
    }

    /** 同步 Emby 媒体库 */
    suspend fun syncEmby(source: SourceEntity, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val token = source.token.ifBlank { return@withContext 0 }
            val client = EmbyClient(source.location, token)

            val items = client.fetchLibrary()
            if (items.isEmpty()) return@withContext 0

            var count = 0

            for (item in items) {
                if (item.isSeries) {
                    val seriesKey = "emby:${source.id}:${item.id}"
                    val shell = MediaEntity(
                        sourceId = source.id,
                        uri = "",
                        remoteId = "emby:${item.id}",
                        seriesKey = seriesKey,
                        title = item.name,
                        kind = MediaKind.SERIES,
                        year = item.year,
                        overview = item.overview,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        rating = item.rating,
                        genres = item.genres
                    )
                    mediaDao.upsert(shell)
                    count++

                    // 拉分集
                    val episodes = client.fetchEpisodes(item.id)
                    for (ep in episodes) {
                        val epKey = "emby:${source.id}:${ep.id}"
                        mediaDao.insert(
                            MediaEntity(
                                sourceId = source.id,
                                uri = EmbyClient.playbackUrl(source.location, ep.id, token),
                                remoteId = epKey,
                                seriesKey = seriesKey,
                                title = ep.name,
                                kind = MediaKind.EPISODE,
                                season = ep.parentIndexNumber,
                                episode = ep.indexNumber,
                                overview = ep.overview,
                                durationMs = ep.runTimeMs,
                                posterUrl = ep.posterUrl,
                                backdropUrl = item.backdropUrl
                            )
                        )
                    }
                    onProgress(count)
                } else {
                    val key = "emby:${source.id}:${item.id}"
                    mediaDao.insert(
                        MediaEntity(
                            sourceId = source.id,
                            uri = EmbyClient.playbackUrl(source.location, item.id, token),
                            remoteId = key,
                            seriesKey = "",
                            title = item.name,
                            kind = MediaKind.MOVIE,
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
                    if (count % 5 == 0) onProgress(count)
                }
            }
            count
        }

    // ---------------- TVBox ----------------

    /** 同步 TVBox 首页列表（只入库剧集壳） */
    suspend fun syncTvBox(source: SourceEntity, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val client = TvBoxClient(source.location)
            val list = client.homeContent()
            if (list.isEmpty()) return@withContext 0

            var count = 0
            for (vod in list) {
                val key = "tvbox:${source.id}:${vod.vodId}"
                val seriesKey = "tvbox:${source.id}:${vod.vodId}"

                mediaDao.insert(
                    MediaEntity(
                        sourceId = source.id,
                        uri = "",
                        remoteId = key,
                        seriesKey = seriesKey,
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
        val vodId = remoteId.substringAfterLast(':')
        val client = TvBoxClient(source.location)
        val detail = client.detail(vodId) ?: return@withContext emptyList()

        val seriesKey = "tvbox:${source.id}:$vodId"

        // 用详情补全海报与简介
        mediaDao.byRemoteId(remoteId)?.let { shell ->
            mediaDao.update(
                shell.copy(
                    overview = detail.content.ifBlank { shell.overview },
                    posterUrl = detail.pic.ifBlank { shell.posterUrl },
                    backdropUrl = detail.pic.ifBlank { shell.backdropUrl },
                    rating = if (detail.score > 0) detail.score else shell.rating,
                    year = if (detail.year > 0) detail.year else shell.year
                )
            )
        }

        // 取第一条可用线路入库
        val primary = detail.playSources.firstOrNull() ?: return@withContext detail.playSources

        primary.episodes.forEachIndexed { index, ep ->
            val epRemote = "tvbox:${source.id}:$vodId:$index"
            mediaDao.insert(
                MediaEntity(
                    sourceId = source.id,
                    uri = ep.url,
                    remoteId = epRemote,
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

    // ---------------- 进度 ----------------

    suspend fun saveProgress(mediaId: Long, positionMs: Long) {
        mediaDao.saveProgress(mediaId, positionMs, System.currentTimeMillis())
    }

    suspend fun fillDuration(mediaId: Long, durationMs: Long) {
        mediaDao.fillDuration(mediaId, durationMs)
    }
}
