package com.tabbit.myvidhub

import android.app.Application
import android.util.Log
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.db.AppDatabase
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy

class MyVidHubApp : Application() {

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

    companion object {
        lateinit var instance: MyVidHubApp
            private set
    }
}
