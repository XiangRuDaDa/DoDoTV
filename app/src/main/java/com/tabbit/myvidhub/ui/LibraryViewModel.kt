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

    val favorites: StateFlow<List<MediaEntity>> = repo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var scanning by mutableStateOf(false)
        private set

    var scanMessage by mutableStateOf("")
        private set

    var message by mutableStateOf<String?>(null)

    init {
        // 冷启动时若已配置来源但本地还没有内容，自动同步一次，避免用户以为"加完就坏了"
        viewModelScope.launch {
            runCatching {
                if (repo.enabledSources().isNotEmpty() && !repo.hasAnyMedia()) {
                    scanAll()
                }
            }
        }
    }

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
                location = normalizeLocation(kind, location),
                username = username.trim(),
                password = password
            )
            val id = repo.addSource(entity)
            val created = entity.copy(id = id)

            // Emby 立即尝试登录换取 token，失败也保留来源便于用户修正
            if (kind == SourceKind.EMBY) {
                val error = repo.embyLogin(created)
                if (error != null) {
                    message = "$error（来源已保存，可稍后编辑修正）"
                    return@launch
                }
            }
            syncAfterChange(repo.getSource(id) ?: created, created.name, "已添加")
        }
    }

    fun updateSource(
        id: Long,
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
            val existing = repo.getSource(id) ?: return@launch
            val newLocation = normalizeLocation(kind, location)
            val newName = name.trim()
            val newUser = username.trim()
            val credentialsChanged = existing.location != newLocation ||
                existing.username != newUser || existing.password != password

            var updated = existing.copy(
                name = newName,
                kind = kind,
                location = newLocation,
                username = newUser,
                password = password,
                token = if (credentialsChanged) "" else existing.token,
                userId = if (credentialsChanged) "" else existing.userId
            )
            repo.updateSource(updated)

            if (kind == SourceKind.EMBY && updated.token.isBlank()) {
                val error = repo.embyLogin(updated)
                if (error != null) {
                    message = "$error（配置已保存，可稍后重试）"
                    return@launch
                }
            }
            updated = repo.getSource(id) ?: updated
            syncAfterChange(updated, newName, "已更新")
        }
    }

    private fun normalizeLocation(kind: SourceKind, location: String): String {
        var url = location.trim()
        if (url.isBlank()) return url
        // 用户常常只填 192.168.1.2:8096 或 example.com/api.php/provide/vod/
        // 不补协议时 OkHttp 会直接解析失败，导致同步静默返回空
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "http://$url"
        }
        if (kind == SourceKind.EMBY) {
            // 从浏览器地址栏复制来的常带 /web/index.html，这里裁回服务器根地址
            url = url.substringBefore("/web/", url).substringBefore("/web", url).trimEnd('/')
        }
        return url
    }

    /** 新增/编辑完成后自动同步该来源，解决“添加后首页无数据” */
    private suspend fun syncAfterChange(source: SourceEntity, label: String, action: String) {
        scanning = true
        scanMessage = "正在同步 $label…"
        try {
            val count = runSync(source)
            message = if (count > 0) "$action $label，已同步 $count 个条目"
            else "$action $label，但未获取到内容"
        } catch (e: Exception) {
            message = "$action $label，同步失败：${e.message ?: "网络或接口异常"}"
        } finally {
            scanning = false
            scanMessage = ""
        }
    }

    private suspend fun runSync(source: SourceEntity): Int = when (source.kind) {
        SourceKind.EMBY -> repo.syncEmby(source) { c -> scanMessage = "${source.name}：$c" }
        SourceKind.TVBOX -> repo.syncTvBox(source) { c -> scanMessage = "${source.name}：$c" }
    }

    fun removeSource(source: SourceEntity) {
        viewModelScope.launch {
            repo.deleteSource(source)
            message = "已删除：${source.name}"
        }
    }

    /** 强制重新登录 Emby（token 过期时用），成功后立即重新同步以刷新播放直链 */
    fun reloginEmby(source: SourceEntity) {
        viewModelScope.launch {
            scanning = true
            val error = repo.embyLogin(source)
            if (error != null) {
                message = error
                scanning = false
                return@launch
            }
            val refreshed = repo.getSource(source.id) ?: source
            message = try {
                val count = runSync(refreshed)
                "已重新登录：${source.name}，同步 $count 个条目"
            } catch (e: Exception) {
                "已重新登录，但同步失败：${e.message ?: "网络或接口异常"}"
            } finally {
                scanning = false
                scanMessage = ""
            }
        }
    }

    /** 启用 / 停用来源：停用后不同步，其内容也从首页隐藏 */
    fun toggleSourceEnabled(source: SourceEntity) {
        viewModelScope.launch {
            repo.setSourceEnabled(source.id, !source.enabled)
            message = if (source.enabled) "已停用：${source.name}" else "已启用：${source.name}"
        }
    }

    // ---------- 收藏 ----------

    fun toggleFavorite(mediaId: Long, favorite: Boolean) {
        viewModelScope.launch { repo.setFavorite(mediaId, favorite) }
    }

    suspend fun searchMedia(keyword: String): List<MediaEntity> =
        if (keyword.isBlank()) emptyList() else repo.search(keyword)

    // ---------- 扫描 ----------

    fun scanAll() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            scanMessage = "准备同步…"
            var total = 0
            val errors = mutableListOf<String>()
            try {
                val enabled = repo.enabledSources()
                if (enabled.isEmpty()) {
                    message = "还没有可用的来源，请先添加"
                    return@launch
                }
                for (src in enabled) {
                    scanMessage = "正在同步 ${src.name}…"
                    // 单个来源失败不能中断其他来源的同步
                    try {
                        total += runSync(src)
                    } catch (e: Exception) {
                        errors += "${src.name}：${e.message ?: "网络或接口异常"}"
                    }
                }
                message = when {
                    total > 0 && errors.isEmpty() -> "同步完成，共 $total 个条目"
                    total > 0 -> "同步完成，共 $total 个条目；部分来源失败：${errors.joinToString("；")}"
                    errors.isNotEmpty() -> "同步失败：${errors.joinToString("；")}"
                    else -> "没有获取到内容"
                }
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
                val added = runSync(source)
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
        return try {
            repo.loadTvBoxDetail(src, remoteId)
        } catch (e: Exception) {
            message = "获取详情失败：${e.message ?: "网络或接口异常"}"
            emptyList()
        }
    }

    suspend fun episodesOf(seriesKey: String): List<MediaEntity> = repo.episodes(seriesKey)
    suspend fun seasonsOf(seriesKey: String): List<Int> = repo.seasons(seriesKey)
    suspend fun mediaOf(id: Long): MediaEntity? = repo.getMedia(id)
    suspend fun sourceOf(id: Long): SourceEntity? = repo.getSource(id)

    fun saveProgress(mediaId: Long, positionMs: Long) {
        viewModelScope.launch { repo.saveProgress(mediaId, positionMs) }
    }
}