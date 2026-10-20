package com.tabbit.myvidhub.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.SourceEntity

@Database(
    entities = [SourceEntity::class, MediaEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "myvidhub.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
