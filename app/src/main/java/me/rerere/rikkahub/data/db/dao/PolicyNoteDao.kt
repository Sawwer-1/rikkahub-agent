package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PolicyNoteEntity

@Dao
interface PolicyNoteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(policies: List<PolicyNoteEntity>): List<Long>

    @Query("SELECT * FROM policy_note WHERE scope_id = :scopeId AND status = :status ORDER BY created_at DESC")
    fun observeByScopeAndStatus(scopeId: String, status: String): Flow<List<PolicyNoteEntity>>

    @Query("SELECT * FROM policy_note WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: String): Flow<List<PolicyNoteEntity>>

    @Query("SELECT * FROM policy_note WHERE scope_id = :scopeId AND status = 'CONFIRMED' ORDER BY reviewed_at DESC LIMIT :limit")
    suspend fun listConfirmed(scopeId: String, limit: Int): List<PolicyNoteEntity>

    @Query("UPDATE policy_note SET status = :status, reviewed_at = :reviewedAt WHERE id = :policyId AND status = 'PENDING'")
    suspend fun review(policyId: String, status: String, reviewedAt: Long): Int

    @Query("DELETE FROM policy_note WHERE id = :policyId")
    suspend fun deleteById(policyId: String)

    @Query("SELECT COUNT(*) > 0 FROM policy_note WHERE scope_id = :scopeId AND content = :content")
    suspend fun existsByContent(scopeId: String, content: String): Boolean
}
