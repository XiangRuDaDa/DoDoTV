package com.tabbit.myvidhub.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.SourceEntity

@Database(
    entities = [SourceEntity::class, MediaEntity::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun mediaDao(): MediaDao

    companion object {
        /**
         * v4 → v5：新增 scrapedAt 列（TMDB 刮削时间戳）。
         * 用 ADD COLUMN 增量迁移，而不是全局的破坏式重建，
         * 这样老用户升级后媒体库、播放进度、收藏都不会丢。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN scrapedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "myvidhub.db"
                )
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
