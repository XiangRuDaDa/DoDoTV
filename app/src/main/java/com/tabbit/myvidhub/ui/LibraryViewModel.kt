package com.tabbit.myvidhub.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabbit.myvidhub.data.MediaRepository
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.tvbox.TvBoxClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)

    val sources: StateFlow<List<SourceEntity>> = repo.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val library: StateFlow<List<MediaEntity>> = repo.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<MediaEntity>> = repo.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featured: StateFlow<List<MediaEntity>> = repo.observeFeatured()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var scanning by mutableStateOf(false)
        private set

    var scanMessage by mutableStateOf("")
        private set

    var message by mutableStateOf<String?>(null)

    // ---------- 来源管理 ----------

    fun addSource(
        name: String,
        kind: SourceKind,
        location: String,
        username: String,
        password: String
    ) {
        if (name.isBlank() || location.isBlank()) {
            message = "名称与地址不能为空"
            return
        }
        viewModelScope.launch {
            val entity = SourceEntity(
                name = name.trim(),
                kind = kind,
                location = location.trim().let {
                    if (kind == SourceKind.EMBY) it.trimEnd('/') else it
                },
                username = username.trim(),
                password = password
            )
            val id = repo.addSource(entity)

            // Emby 立即尝试登录换取 token，失败也保留来源便于用户修正
            if (kind == SourceKind.EMBY) {
                val created = entity.copy(id = id)
                val error = repo.embyLogin(created)
                message = error ?: "已添加 Emby：${entity.name}"
            } else {
                message = "已添加 TVBox：${entity.name}"
            }
        }
    }

    fun removeSource(source: SourceEntity) {
        viewModelScope.launch {
            repo.deleteSource(source)
            message = "已删除：${source.name}"
        }
    }

    /** 强制重新登录 Emby（token 过期时用） */
    fun reloginEmby(source: SourceEntity) {
        viewModelScope.launch {
            scanning = true
            val error = repo.embyLogin(source)
            message = error ?: "已重新登录：${source.name}"
            scanning = false
        }
    }

    // ---------- 扫描 ----------

    fun scanAll() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            scanMessage = "准备同步…"
            var total = 0
            try {
                for (src in sources.value) {
                    scanMessage = "正在同步 ${src.name}…"
                    total += when (src.kind) {
                        SourceKind.EMBY -> repo.syncEmby(src) { c -> scanMessage = "${src.name}：$c" }
                        SourceKind.TVBOX -> repo.syncTvBox(src) { c -> scanMessage = "${src.name}：$c" }
                    }
                }
                message = if (total > 0) "同步完成，共 $total 个条目" else "没有获取到内容"
            } catch (e: Exception) {
                message = "同步失败：${e.message ?: "网络或接口异常"}"
            } finally {
                scanning = false
                scanMessage = ""
            }
        }
    }

    fun scanOne(source: SourceEntity) {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            try {
                val added = when (source.kind) {
                    SourceKind.EMBY -> repo.syncEmby(source) { c -> scanMessage = "${source.name}：$c" }
                    SourceKind.TVBOX -> repo.syncTvBox(source) { c -> scanMessage = "${source.name}：$c" }
                }
                message = if (added > 0) "${source.name}：$added 个条目" else "${source.name}：无内容"
            } catch (e: Exception) {
                message = "同步失败：${e.message ?: "未知错误"}"
            } finally {
                scanning = false
                scanMessage = ""
            }
        }
    }

    // ---------- 详情 ----------

    /** TVBox 详情按需拉取分集 */
    suspend fun loadTvBoxDetail(sourceId: Long, remoteId: String): List<TvBoxClient.PlaySource> {
        val src = repo.getSource(sourceId) ?: return emptyList()
        return runCatching { repo.loadTvBoxDetail(src, remoteId) }.getOrDefault(emptyList())
    }

    suspend fun episodesOf(seriesKey: String): List<MediaEntity> = repo.episodes(seriesKey)
    suspend fun seasonsOf(seriesKey: String): List<Int> = repo.seasons(seriesKey)
    suspend fun mediaOf(id: Long): MediaEntity? = repo.getMedia(id)
    suspend fun sourceOf(id: Long): SourceEntity? = repo.getSource(id)

    fun saveProgress(mediaId: Long, positionMs: Long) {
        viewModelScope.launch { repo.saveProgress(mediaId, positionMs) }
    }
}
