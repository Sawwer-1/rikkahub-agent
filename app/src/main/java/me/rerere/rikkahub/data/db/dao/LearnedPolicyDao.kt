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
    suspend fun insertAll(policies: List<LearnedPolicyEntity>): List<Long>

    @Query("SELECT * FROM learned_policy WHERE scope_id = :scopeId AND status = :status ORDER BY created_at DESC")
    fun observeByScopeAndStatus(scopeId: String, status: String): Flow<List<LearnedPolicyEntity>>

    @Query("SELECT * FROM learned_policy WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: String): Flow<List<LearnedPolicyEntity>>

    @Query("SELECT * FROM learned_policy WHERE scope_id = :scopeId AND status = 'CONFIRMED' ORDER BY reviewed_at DESC LIMIT :limit")
    suspend fun listConfirmed(scopeId: String, limit: Int): List<LearnedPolicyEntity>

    @Query("UPDATE learned_policy SET status = :status, reviewed_at = :reviewedAt WHERE id = :policyId AND status = 'PENDING'")
    suspend fun review(policyId: String, status: String, reviewedAt: Long): Int

    @Query("DELETE FROM learned_policy WHERE id = :policyId")
    suspend fun deleteById(policyId: String)

    /** Duplicate-content guard: same normalized content in the same scope already exists. */
    @Query("SELECT COUNT(*) > 0 FROM learned_policy WHERE scope_id = :scopeId AND content = :content")
    suspend fun existsByContent(scopeId: String, content: String): Boolean
}
