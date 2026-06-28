package com.repository.navigation.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface JourneySessionDao {

    @Insert
    suspend fun insert(entity: JourneySessionEntity): Long

    @Update
    suspend fun update(entity: JourneySessionEntity)

    @Query("SELECT * FROM journey_sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): JourneySessionEntity?

    @Query("SELECT * FROM journey_sessions WHERE id = :id")
    suspend fun getById(id: Long): JourneySessionEntity?

    @Query("UPDATE journey_sessions SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Query("DELETE FROM journey_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM journey_sessions WHERE isActive = 0")
    suspend fun deleteAllInactive()

    @Query("UPDATE journey_sessions SET currentStepIndex = :index WHERE id = :id")
    suspend fun updateStepIndex(id: Long, index: Int)
}
