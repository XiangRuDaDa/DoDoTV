package com.tabbit.myvidhub.data.tmdb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TMDB 刮削结果的内存缓存，用来避免「反复打开同一部片就反复请求」。
 *
 * 三层防护，对应三种不同的浪费场景：
 *
 * 1. **正缓存（TTL 7 天）**
 *    同一部片刮过就记住，7 天内再进来直接命中，不发任何网络请求。
 *    元数据变动很慢，7 天足够新鲜；想强制刷新在设置页点「清空刮削缓存」即可。
 *
 * 2. **负缓存（TTL 1 天）**
 *    搜索没搜到（TMDB 里没有这部片 / 片名太怪）也要记住。
 *    否则这类「永远刮不到」的片子，每次打开都白跑一趟，是最费流量和配额的情况。
 *    负缓存 TTL 更短，给以后补录数据留出机会。
 *
 * 3. **并发去重（in-flight 合并）**
 *    快速连按返回再进、或列表预加载时，同一个 key 可能同时触发多次请求。
 *    这里让后到的调用者挂起等待第一次的结果，而不是再发一遍。
 *    注意：等待是通过 CompletableDeferred 在锁**外**完成的，
 *    不能在持锁状态下等待，否则会把其他 key 的请求一起堵死。
 *
 * 容量用 LRU 限制在 300 条，防止长期使用无限增长。
 */
object TmdbCache {

    /** 正缓存有效期：7 天 */
    private const val TTL_HIT_MS = 7L * 24 * 60 * 60 * 1000

    /** 负缓存有效期：1 天（刮不到的结果也记，但更早过期） */
    private const val TTL_MISS_MS = 24L * 60 * 60 * 1000

    /** 最多缓存条数 */
    private const val MAX_ENTRIES = 300

    /** 一条缓存记录：result 为 null 表示「确认刮不到」 */
    private class Entry(val result: TmdbClient.ScrapeResult?, val at: Long, val ttl: Long) {
        fun isValid(now: Long) = now - at < ttl
    }

    // accessOrder = true 即 LRU：每次读把条目移到队尾，淘汰时从队首取最久未用的
    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX_ENTRIES
    }

    /** 保护 entries / inFlight 的锁。只包裹极短的临界区，绝不在持锁时做 IO 或等待。 */
    private val lock = Mutex()

    /** 正在请求中的 key → 结果舱。其他协程 await 它，实现并发合并。 */
    private val inFlight = mutableMapOf<String, CompletableDeferred<TmdbClient.ScrapeResult?>>()

    /**
     * 生成缓存键。
     * 标题做归一化（去空格、转小写），避免「飞行家 」「飞行家」被当成两条；
     * 带上年份和类型，因为同名电影和剧集是两条不同数据。
     */
    fun keyOf(title: String, year: Int, kind: String): String =
        "${title.trim().lowercase()}|$year|$kind"

    /** 读缓存（不加锁，靠 entries 自身的 synchronized 保证安全） */
    private fun readEntry(key: String): Entry? {
        val now = System.currentTimeMillis()
        synchronized(entries) {
            val e = entries[key] ?: return null
            if (!e.isValid(now)) {
                entries.remove(key)
                return null
            }
            return e
        }
    }

    private fun writeEntry(key: String, result: TmdbClient.ScrapeResult?) {
        val now = System.currentTimeMillis()
        val ttl = if (result != null) TTL_HIT_MS else TTL_MISS_MS
        synchronized(entries) { entries[key] = Entry(result, now, ttl) }
    }

    /** 只读查看（不触发请求），用于判断是否值得发起网络请求 */
    fun peek(key: String): TmdbClient.ScrapeResult? = readEntry(key)?.result

    /** 是否有有效缓存（命中与负缓存都算） */
    fun isCached(key: String): Boolean = readEntry(key) != null

    /** 手动写入一条成功结果（外部已有数据时用） */
    fun putHit(key: String, result: TmdbClient.ScrapeResult) = writeEntry(key, result)

    /** 手动写入一条「刮不到」 */
    fun putMiss(key: String) = writeEntry(key, null)

    /**
     * 带并发去重的取值入口。
     *
     * @param fetch 真正的网络请求，只在缓存未命中且无人正在请求时才执行
     * @return 缓存结果或 fetch 的结果；null 表示确认刮不到
     */
    suspend fun getOrFetch(
        key: String,
        fetch: suspend () -> TmdbClient.ScrapeResult?
    ): TmdbClient.ScrapeResult? {
        // 快路径：命中缓存直接返回，完全不进锁（绝大多数调用走这里）
        readEntry(key)?.let { return it.result }

        // 决定「我是发起者」还是「我等待别人」
        var mine: CompletableDeferred<TmdbClient.ScrapeResult?>? = null
        var isOwner = false

        lock.withLock {
            // 双重检查：可能在上一个持锁者退出前已有结果
            readEntry(key)?.let { return it.result }

            val existing = inFlight[key]
            if (existing != null) {
                mine = existing
            } else {
                val deferred = CompletableDeferred<TmdbClient.ScrapeResult?>()
                inFlight[key] = deferred
                mine = deferred
                isOwner = true
            }
        }

        // 等待者：在锁外挂起，拿到发起者的结果就走
        if (!isOwner) return mine!!.await()

        // 发起者：真正联网，结束后把结果广播给所有等待者
        val deferred = mine!!
        try {
            val result = fetch()
            writeEntry(key, result)
            deferred.complete(result)
            return result
        } catch (t: Throwable) {
            // 网络异常不写负缓存：这是临时故障，不该让用户一天内都刮不到
            deferred.complete(null)
            throw t
        } finally {
            lock.withLock { inFlight.remove(key) }
        }
    }

    /** 清空缓存（设置页「清空刮削缓存」入口用） */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** 当前缓存条目数，设置页展示用 */
    fun size(): Int = synchronized(entries) { entries.size }
}
