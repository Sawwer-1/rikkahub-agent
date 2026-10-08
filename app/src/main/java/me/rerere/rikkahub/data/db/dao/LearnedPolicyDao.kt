package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.LearnedPolicyEntity

@Dao
interface LearnedPolicyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(policies: List<LearnedPolicyEntity>)

    @Query("SELECT * FROM learned_policy WHERE scope_id = :scopeId AND status = :status ORDER BY created_at DESC")
    fun observeByScopeAndStatus(scopeId: String, status: String): Flow<List<LearnedPolicyEntity>>
}
