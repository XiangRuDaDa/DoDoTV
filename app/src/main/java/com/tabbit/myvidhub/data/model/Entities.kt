package com.tabbit.myvidhub.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 只保留两类来源 */
enum class SourceKind {
    EMBY,   // Emby 媒体服务器（自建影库）
    TVBOX   // TVBox / 苹果CMS 风格 JSON 接口
}

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val kind: SourceKind,
    /** EMBY: 服务器地址 https://emby.example.com ; TVBOX: 配置 JSON 或 CMS 接口地址 */
    val location: String,
    val username: String = "",
    val password: String = "",
    /** Emby 登录后的 AccessToken，避免每次重启重新登录 */
    val token: String = "",
    /** Emby 用户 Id */
    val userId: String = "",
    /** 是否启用：停用后不参与同步，其内容也不在首页展示 */
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

object MediaKind {
    const val MOVIE = "MOVIE"
    const val SERIES = "SERIES"
    const val EPISODE = "EPISODE"
}

@Entity(
    tableName = "media",
    indices = [
        Index("sourceId"),
        Index("seriesKey"),
        // 唯一性放在 remoteId 上：Emby itemId / TVBox vodId:集数
        // 不能放 uri —— 剧集壳条目的 uri 为空字符串，多个壳会互相冲突
        Index(value = ["remoteId"], unique = true)
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceId: Long,
    /** 播放地址：Emby 直链 / TVBox m3u8 */
    val uri: String,
    /** 唯一标识：Emby itemId 或 TVBox siteKey:vodId[:集数] */
    val remoteId: String = "",
    /** 剧集归属键，电影为空；剧集/分集都用同一个 seriesKey */
    val seriesKey: String = "",
    val title: String,
    val kind: String = MediaKind.MOVIE,
    val season: Int = 0,
    val episode: Int = 0,
    val year: Int = 0,
    val overview: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: Double = 0.0,
    val genres: String = "",
    val durationMs: Long = 0L,
    /** 播放进度，驱动「继续观看」 */
    val positionMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
    /** 是否收藏 */
    val favorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)
