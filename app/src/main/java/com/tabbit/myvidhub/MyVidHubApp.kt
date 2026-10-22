package com.tabbit.myvidhub

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.network.OkHttpNetworkFetcherFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.db.AppDatabase
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy

class MyVidHubApp : Application(), ImageLoaderFactory {

    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)

        // 启动时判定刮削方案：凭据非空走 TMDB，为空则沿用原有方案。
        // 这里只做本地判断，不发网络请求，所以不会拖慢冷启动。
        val engine = ScrapeStrategy.refresh()
        Log.i(
            "MyVidHub",
            "刮削方案：${engine.name} / ${ScrapeStrategy.describe()}" +
                "（TMDB 凭据${if (Prefs.tmdbConfigured) "已配置" else "未配置"}）"
        )
    }

    /**
     * 图片加载专用的 OkHttpClient，带「TMDB 图片域名替换」拦截器。
     *
     * 为什么要在图片层做而不是改数据库里存的 URL：
     * 库里存的是 TMDB 官方地址（image.tmdb.org），如果按域名改写，
     * 用户之后改了镜像、或者镜像失效，只需改配置即可，历史数据不用重刮。
     */
    private val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val base = Prefs.tmdbImageBase
                // 只在配置了镜像、且确实是 TMDB 官方图片域名时才改写
                if (base.isNotBlank() && req.url.host == "image.tmdb.org") {
                    val mirror = base.toHttpUrlOrNull()
                    if (mirror != null) {
                        // 保留镜像自身的路径前缀，再拼上原来的 /t/p/w500/xxx.jpg
                        val prefix = mirror.encodedPath.trimEnd('/')
                        val newUrl = req.url.newBuilder()
                            .scheme(mirror.scheme)
                            .host(mirror.host)
                            .port(mirror.port)
                            .encodedPath(prefix + req.url.encodedPath)
                            .build()
                        return@addInterceptor chain.proceed(
                            req.newBuilder().url(newUrl).build()
                        )
                    }
                }
                chain.proceed(req)
            }
            .build()
    }

    /**
     * Coil 全局 ImageLoader。
     * 统一走上面带镜像拦截的 client，并开启淡入，
     * 让「先出占位、再到清晰图」的切换更平滑，不闪白也不闪黑。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .crossfade(true)
            .crossfade(220)
            .build()

    companion object {
        lateinit var instance: MyVidHubApp
            private set
    }
}
