package com.tabbit.myvidhub.data.tvbox

import android.content.Context
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TVBox type 3 spider JAR 运行时加载器。
 *
 * 流程：
 * 1) 解析配置里的 spider URL（可能带 ;md5;xxx 校验）
 * 2) 下载 JAR 到本地缓存，解压出 classes.dex + native so + guard 文件
 * 3) DexClassLoader 加载 dex，parent 用 app 的 ClassLoader
 * 4) 反射触发 Init 类的静态初始化（让它解密 assets 里的 .guard 到内存 dex）
 * 5) 按 site.api 找 Guard 类（去掉 csp_ 前缀 → com.github.catvod.spider.XxxGuard）
 * 6) 反射调用 Spider 基类的 5 个标准方法
 */
class SpiderLoader(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "SpiderLoader"
    }

    private val cacheDir: File = File(context.cacheDir, "spiders").also { it.mkdirs() }
    private val nativeDir: File = File(context.filesDir, "spider_libs").also { it.mkdirs() }

    private val loaders = ConcurrentHashMap<String, DexClassLoader>()
    private val instances = ConcurrentHashMap<String, SpiderProxy>()

    private fun parseSpider(raw: String): Pair<String, String> {
        val parts = raw.trim().split(';').map { it.trim() }
        val url = parts.firstOrNull().orEmpty()
        val md5 = parts.getOrNull(2).orEmpty()
        return url to md5
    }

    suspend fun ensureLoaded(spiderRaw: String): String = withContext(Dispatchers.IO) {
        val (url, md5) = parseSpider(spiderRaw)
        check(url.isNotBlank()) { "spider URL 为空" }

        val key = md5.ifBlank { md5Hex(url) }
        loaders[key]?.let { return@withContext key }

        val jarFile = File(cacheDir, "$key.jar")
        if (!jarFile.exists() || (md5.isNotBlank() && md5Hex(jarFile) != md5)) {
            download(url, jarFile)
            check(md5.isBlank() || md5Hex(jarFile) == md5) {
                "spider JAR MD5 校验失败，可能下载了错误文件"
            }
        }

        val dexFile = File(cacheDir, "$key.dex")
        if (!dexFile.exists()) {
            extractJar(jarFile, dexFile)
        }

        val dexOptDir = File(context.cacheDir, "spider_opt").also { it.mkdirs() }
        val loader = DexClassLoader(
            dexFile.absolutePath,
            dexOptDir.absolutePath,
            nativeDir.absolutePath,
            context.classLoader
        )

        runCatching {
            val init = loader.loadClass("com.github.catvod.spider.Init")
            val initMethod = init.getDeclaredMethod("init", Context::class.java)
            initMethod.invoke(null, context)
        }.onFailure { Log.e(TAG, "Init 调用失败: ${it.message}", it) }

        loaders[key] = loader
        key
    }

    fun getSpider(loaderKey: String, api: String, ext: Any?): SpiderProxy? {
        val cacheKey = "$loaderKey:$api"
        instances[cacheKey]?.let { return it }

        val loader = loaders[loaderKey] ?: return null
        val className = resolveClassName(api)

        val clazz = runCatching { loader.loadClass(className) }.getOrNull()
            ?: runCatching { loader.loadClass(api) }.getOrNull()
            ?: run {
                Log.e(TAG, "找不到 Guard 类: $className (loaderKey=$loaderKey)")
                return null
            }

        val instance = runCatching {
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        }.getOrElse {
            Log.e(TAG, "实例化 Guard 失败 $className: ${it.message}", it)
            return null
        }

        runCatching {
            val initM = clazz.getMethod("init", Context::class.java, String::class.java)
            val extendStr = when (ext) {
                null -> ""
                is String -> ext
                else -> ext.toString()
            }
            initM.invoke(instance, context, extendStr)
        }.onFailure { Log.w(TAG, "Guard.init 调用失败（不致命）: ${it.message}") }

        val proxy = SpiderProxy(clazz, instance)
        instances[cacheKey] = proxy
        return proxy
    }

    private fun resolveClassName(api: String): String {
        var name = api
        if (name.startsWith("csp_")) name = name.removePrefix("csp_")
        if (name.startsWith("com.github.catvod.spider.")) return api
        return "com.github.catvod.spider.$name"
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "下载 spider JAR 失败 HTTP ${resp.code}: $url" }
            val body = resp.body ?: throw IllegalStateException("下载 spider JAR 返回空 body")
            FileOutputStream(target).use { fos -> body.byteStream().copyTo(fos) }
        }
    }

    private fun extractJar(jar: File, outDex: File) {
        ZipInputStream(jar.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                when {
                    entry.name == "classes.dex" -> FileOutputStream(outDex).use { fos -> zis.copyTo(fos) }
                    entry.name.startsWith("assets/") -> {
                        val targetName = File(entry.name).name
                        if (targetName.isNotBlank()) {
                            FileOutputStream(File(nativeDir, targetName)).use { fos -> zis.copyTo(fos) }
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun md5Hex(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { it.copyTo(object : java.io.OutputStream() {
            override fun write(b: Int) { md.update(b.toByte()) }
            override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len) }
        }) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Spider 实例的反射代理。
 * CatVod Spider 基类定义的 5 个标准方法：
 *   void init(Context context, String extend)
 *   String homeContent(boolean filter)
 *   String categoryContent(String tid, String pg, boolean filter, HashMap<String,String> extend)
 *   String detailContent(List<String> ids)
 *   String playerContent(String flag, String id, List<String> vipFlags)
 * 子类还可能覆盖 searchContent(String key, boolean quick)
 */
class SpiderProxy(private val clazz: Class<*>, private val instance: Any) {

    fun homeContent(filter: Boolean = true): String? = runCatching {
        clazz.getMethod("homeContent", Boolean::class.javaPrimitiveType).invoke(instance, filter) as String
    }.getOrNull()

    fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean = true,
        extend: Map<String, String>? = null
    ): String? = runCatching {
        val map = (extend as? HashMap<String, String>) ?: HashMap()
        val m = clazz.getMethod(
            "categoryContent",
            String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, HashMap::class.java
        )
        m.invoke(instance, tid, pg, filter, map) as String
    }.getOrNull()

    fun detailContent(ids: List<String>): String? = runCatching {
        clazz.getMethod("detailContent", List::class.java).invoke(instance, ids) as String
    }.getOrNull()

    fun playerContent(flag: String, id: String, vipFlags: List<String>? = null): String? = runCatching {
        val flags = vipFlags ?: emptyList<String>()
        val m = clazz.getMethod("playerContent", String::class.java, String::class.java, List::class.java)
        m.invoke(instance, flag, id, flags) as String
    }.getOrNull()

    fun searchContent(key: String, quick: Boolean = true): String? = runCatching {
        clazz.getMethod("searchContent", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(instance, key, quick) as String
    }.getOrNull()
}
