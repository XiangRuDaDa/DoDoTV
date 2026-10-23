package com.tabbit.myvidhub.data.tvbox

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TVBox type 3 爬虫 JAR 运行时加载器。
 *
 * 这类配置里的 csp_XxxGuard 是**加密壳**：
 *   spider JAR 内只有 39KB 的 classes.dex（壳类 + Init 引导类）、
 *   assets/wexguard_v7.so、assets/wexguard_v8.so（解密用的 native 库）、
 *   assets/wexshinidie.guard（约 1MB 的加密 dex）。
 *
 * 关键点：
 *  1. 必须把**整个 JAR（zip）**交给 DexClassLoader，不能只抽 classes.dex。
 *     因为 Init 通过 Class.getResourceAsStream("assets/wexguard_v8.so") 从自己的
 *     容器里读 so 和 guard；纯 dex 没有资源，读出来是 null，解密必然失败。
 *  2. Init.init(context) 会自己把 so 解出来写进 cacheDir、chmod、System.load，
 *     再用 native 把 .guard 解密成真正的 dex。
 *  3. 真正的 Guard 实现不在壳 dex 里，所以 Init 之后需要重新扫描 cacheDir
 *     找到解密出来的 dex，用「jar + 解密 dex」组成新的 classpath 再加载 Guard。
 */
class SpiderLoader(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jarDir: File = File(context.cacheDir, "spiders").also { it.mkdirs() }
    private val optDir: File = File(context.cacheDir, "spider_opt").also { it.mkdirs() }

    /** key → 可加载 Guard 的 classloader（可能是重建后的） */
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()
    private val instances = ConcurrentHashMap<String, SpiderProxy>()

    /** key → jar 路径 */
    private val jars = ConcurrentHashMap<String, String>()

    /** 最近一次失败原因，供上层把真实错误透出到 UI */
    @Volatile
    var lastError: String = ""
        private set

    companion object {
        private const val TAG = "SpiderLoader"
        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"
    }

    private fun parseSpider(raw: String): Pair<String, String> {
        val parts = raw.trim().split(';').map { it.trim() }
        return parts.firstOrNull().orEmpty() to parts.getOrNull(2).orEmpty()
    }

    suspend fun ensureLoaded(spiderRaw: String): String = withContext(Dispatchers.IO) {
        val (url, md5) = parseSpider(spiderRaw)
        check(url.isNotBlank()) { "配置里没有 spider 字段（此类配置需要 spider 包才能解析）" }

        val key = md5.ifBlank { md5Hex(url) }
        loaders[key]?.let { return@withContext key }

        // 1) 下载整个 JAR（保持 zip 原样，不解压）
        val jarFile = File(jarDir, "$key.jar")
        if (!jarFile.exists() || (md5.isNotBlank() && md5Hex(jarFile) != md5)) {
            download(url, jarFile)
            if (md5.isNotBlank() && md5Hex(jarFile) != md5) {
                fail("spider 包 MD5 校验失败（期望 $md5）")
            }
        }
        // Android 14（targetSdk 34）禁止从可写文件加载 dex，必须置为只读
        if (jarFile.canWrite()) jarFile.setReadOnly()
        jars[key] = jarFile.absolutePath

        // 2) 壳 dex 的 classloader
        val stubLoader = DexClassLoader(jarFile.absolutePath, optDir.absolutePath, null, context.classLoader)

        // 3) 调 Init.init(context) —— 让壳自己去解 so + 解密 guard
        val initStarted = System.currentTimeMillis()
        val initClazz = runCatching { stubLoader.loadClass("com.github.catvod.spider.Init") }
            .getOrElse {
                fail("spider 包缺少 Init 类：${it.message}")
            }
        val initOk = runCatching { invokeInit(initClazz) }
        initOk.exceptionOrNull()?.let {
            Log.w(TAG, "Init.init 调用异常：${it.message}", it)
        }

        // 4) 扫描 Init 写出的解密 dex，重组 classpath
        val decrypted = scanDecryptedDex(initStarted)
        Log.d(TAG, "Init 完成，解密 dex ${decrypted.size} 个：${decrypted.joinToString { File(it).name }}")

        // 解密出的 dex 同样必须只读，否则 Android 14 拒载
        decrypted.forEach { runCatching { File(it).takeIf { f -> f.canWrite() }?.setReadOnly() } }

        val classpath = (listOf(jarFile.absolutePath) + decrypted).joinToString(File.pathSeparator)
        val loader = if (classpath == jarFile.absolutePath) stubLoader
        else DexClassLoader(classpath, optDir.absolutePath, null, context.classLoader)

        loaders[key] = loader
        key
    }

    private fun invokeInit(initClazz: Class<*>): Any? {
        // 常见签名：static void init(Context) / static void init(Context, String)
        runCatching {
            initClazz.getDeclaredMethod("init", Context::class.java).invoke(null, context)
        }.onSuccess { return it }
        return runCatching {
            initClazz.getDeclaredMethod("init", Context::class.java, String::class.java).invoke(null, context, "")
        }.getOrElse { throw it }
    }

    /** 递归扫描 cacheDir 里以 dex 魔数开头的文件（Init 解密产物） */
    private fun scanDecryptedDex(after: Long): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > 4) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.length() > 4096 && f.lastModified() >= after - 2000 && hasDexMagic(f)) {
                    out += f.absolutePath
                }
            }
        }
        walk(context.cacheDir, 0)
        return out
    }

    private fun hasDexMagic(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val buf = ByteArray(4)
            ins.read(buf) == 4 && buf.contentEquals(DEX_MAGIC)
        }
    }.getOrDefault(false)

    fun getSpider(loaderKey: String, api: String, ext: Any?): SpiderProxy? {
        val cacheKey = "$loaderKey:$api"
        instances[cacheKey]?.let { return it }

        val className = resolveClassName(api)
        val clazz = loadGuardClass(loaderKey, className, api) ?: run {
            lastError = "找不到爬虫类 $className（spider 包解密失败或站点名不匹配）"
            Log.e(TAG, lastError)
            return null
        }

        val instance = runCatching {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrElse {
            lastError = "实例化 $className 失败：${it.message}"
            Log.e(TAG, lastError, it)
            return null
        }

        runCatching {
            val initM = clazz.getMethod("init", Context::class.java, String::class.java)
            initM.invoke(instance, context, extStr(ext))
        }.onFailure { Log.w(TAG, "$className.init 失败（不致命）：${it.message}") }

        val proxy = SpiderProxy(clazz, instance)
        instances[cacheKey] = proxy
        return proxy
    }

    /**
     * 加载 Guard 类。首次失败时重新扫描 cacheDir 里的解密 dex 并重建 classloader，
     * 兼容「Init 懒解密 / 首个 class 请求时才落盘」的实现。
     */
    private fun loadGuardClass(loaderKey: String, className: String, api: String): Class<*>? {
        loaders[loaderKey]?.let { l ->
            runCatching { l.loadClass(className) }.getOrNull()?.let { return it }
            runCatching { l.loadClass(api) }.getOrNull()?.let { return it }
        }

        val jarPath = jars[loaderKey] ?: return null
        val decrypted = scanDecryptedDex(System.currentTimeMillis() - 60_000)
        if (decrypted.isEmpty()) return null
        decrypted.forEach { runCatching { File(it).takeIf { f -> f.canWrite() }?.setReadOnly() } }

        val classpath = (listOf(jarPath) + decrypted).joinToString(File.pathSeparator)
        val rebuilt = runCatching {
            DexClassLoader(classpath, optDir.absolutePath, null, context.classLoader)
        }.getOrNull() ?: return null

        loaders[loaderKey] = rebuilt
        return runCatching { rebuilt.loadClass(className) }.getOrNull()
            ?: runCatching { rebuilt.loadClass(api) }.getOrNull()
    }

    private fun extStr(ext: Any?): String = when (ext) {
        null -> ""
        is String -> ext
        else -> ext.toString()
    }

    private fun resolveClassName(api: String): String {
        var name = api
        if (name.startsWith("csp_")) name = name.removePrefix("csp_")
        if (name.startsWith("com.github.catvod.spider.")) return api
        return "com.github.catvod.spider.$name"
    }

    private fun fail(msg: String): Nothing {
        lastError = msg
        Log.e(TAG, msg)
        throw IllegalStateException(msg)
    }

    private fun download(url: String, target: File) {
        // 旧文件可能是只读的（上一轮已置只读），先恢复可写再覆盖
        if (target.exists()) target.setWritable(true)
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) fail("下载 spider 包失败 HTTP ${resp.code}")
            val body = resp.body ?: fail("下载 spider 包返回空内容")
            FileOutputStream(target).use { fos -> body.byteStream().copyTo(fos) }
        }
    }

    private fun md5Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Spider 实例的反射代理。
 * CatVod Spider 基类的标准方法：
 *   void init(Context, String)
 *   String homeContent(boolean)
 *   String categoryContent(String, String, boolean, HashMap<String,String>)
 *   String detailContent(List<String>)
 *   String playerContent(String, String, List<String>)
 *   String searchContent(String, boolean)
 */
class SpiderProxy(private val clazz: Class<*>, private val instance: Any) {

    fun homeContent(filter: Boolean = true): String? = runCatching {
        clazz.getMethod("homeContent", Boolean::class.javaPrimitiveType).invoke(instance, filter) as String
    }.onFailure { Log.w("SpiderProxy", "homeContent 失败：${it.message}") }.getOrNull()

    fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean = true,
        extend: Map<String, String>? = null
    ): String? = runCatching {
        val map = (extend as? HashMap<String, String>) ?: HashMap()
        clazz.getMethod(
            "categoryContent",
            String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, HashMap::class.java
        ).invoke(instance, tid, pg, filter, map) as String
    }.getOrNull()

    fun detailContent(ids: List<String>): String? = runCatching {
        clazz.getMethod("detailContent", List::class.java).invoke(instance, ids) as String
    }.onFailure { Log.w("SpiderProxy", "detailContent 失败：${it.message}") }.getOrNull()

    fun playerContent(flag: String, id: String, vipFlags: List<String>? = null): String? = runCatching {
        clazz.getMethod("playerContent", String::class.java, String::class.java, List::class.java)
            .invoke(instance, flag, id, vipFlags ?: emptyList<String>()) as String
    }.onFailure { Log.w("SpiderProxy", "playerContent 失败：${it.message}") }.getOrNull()

    fun searchContent(key: String, quick: Boolean = true): String? = runCatching {
        clazz.getMethod("searchContent", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(instance, key, quick) as String
    }.getOrNull()
}
