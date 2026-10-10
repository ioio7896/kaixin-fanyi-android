package com.localtranslate.hymt2.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: TranslationRecord)

    @Delete
    suspend fun delete(record: TranslationRecord)

    @Query("SELECT * FROM translation_history ORDER BY createdAtMillis DESC LIMIT 30")
    fun observeRecent(): Flow<List<TranslationRecord>>

    @Query("DELETE FROM translation_history")
    suspend fun clear()
}
