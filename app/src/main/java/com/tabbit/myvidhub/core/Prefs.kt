package com.tabbit.myvidhub.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 轻量本地配置。
 * Emby 的 AccessToken 存在数据库里（可续期），这里只放应用级偏好。
 */
object Prefs {

    private const val FILE = "myvidhub_prefs"
    private const val KEY_TV_MODE = "force_tv_mode"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var forceTvMode: Boolean
        get() = sp.getBoolean(KEY_TV_MODE, false)
        set(value) = sp.edit { putBoolean(KEY_TV_MODE, value) }
}
