package com.repository.navigation.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecentDestinationDao {

    @Query("SELECT * FROM recent_destinations ORDER BY lastUsedAt DESC LIMIT 10")
    suspend fun getRecent(): List<RecentDestinationEntity>

    @Query(
        """SELECT * FROM recent_destinations
           WHERE title LIKE '%' || :query || '%'
              OR subtitle LIKE '%' || :query || '%'
           ORDER BY lastUsedAt DESC LIMIT 5"""
    )
    suspend fun search(query: String): List<RecentDestinationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentDestinationEntity)

    @Query(
        """DELETE FROM recent_destinations WHERE id NOT IN
           (SELECT id FROM recent_destinations ORDER BY lastUsedAt DESC LIMIT 1000)"""
    )
    suspend fun pruneOld()
}
