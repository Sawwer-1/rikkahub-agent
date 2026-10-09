package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import me.rerere.rikkahub.data.db.entity.PolicyNoteEntity

@Dao
interface PolicyNoteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(policies: List<PolicyNoteEntity>)
}
