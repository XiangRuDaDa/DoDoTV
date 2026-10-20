package com.tabbit.myvidhub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tabbit.myvidhub.data.model.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query("SELECT * FROM media WHERE kind IN ('MOVIE','SERIES') ORDER BY addedAt DESC")
    fun observeLibrary(): Flow<List<MediaEntity>>

    @Query(
        "SELECT * FROM media WHERE positionMs > 5000 AND kind IN ('MOVIE','EPISODE') " +
            "ORDER BY lastPlayedAt DESC LIMIT 20"
    )
    fun observeContinueWatching(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE kind = 'MOVIE' ORDER BY addedAt DESC LIMIT 20")
    fun observeMovies(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE kind = 'SERIES' ORDER BY addedAt DESC LIMIT 20")
    fun observeSeries(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE backdropUrl != '' ORDER BY addedAt DESC LIMIT 8")
    fun observeFeatured(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE remoteId = :remoteId LIMIT 1")
    suspend fun byRemoteId(remoteId: String): MediaEntity?

    @Query(
        "SELECT * FROM media WHERE seriesKey = :seriesKey AND kind = 'EPISODE' " +
            "ORDER BY season ASC, episode ASC"
    )
    suspend fun episodes(seriesKey: String): List<MediaEntity>

    @Query("SELECT DISTINCT season FROM media WHERE seriesKey = :seriesKey AND kind='EPISODE' ORDER BY season")
    suspend fun seasons(seriesKey: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: MediaEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaEntity): Long

    @Update
    suspend fun update(item: MediaEntity)

    @Query("UPDATE media SET positionMs = :position, lastPlayedAt = :ts WHERE id = :id")
    suspend fun saveProgress(id: Long, position: Long, ts: Long)

    @Query("UPDATE media SET durationMs = :duration WHERE id = :id AND durationMs = 0")
    suspend fun fillDuration(id: Long, duration: Long)

    @Query("DELETE FROM media WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    @Query("SELECT COUNT(*) FROM media WHERE sourceId = :sourceId")
    suspend fun countBySource(sourceId: Long): Int
}
