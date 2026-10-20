package com.tabbit.myvidhub.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量本地配置。
 * Emby 的 AccessToken 存在数据库里（可续期），这里只放应用级偏好。
 */
object Prefs {

    private const val FILE = "myvidhub_prefs"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

}
