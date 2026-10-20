package com.tabbit.myvidhub

import android.app.Application
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.db.AppDatabase

class MyVidHubApp : Application() {

    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
    }

    companion object {
        lateinit var instance: MyVidHubApp
            private set
    }
}
