package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A lightweight learned policy distilled from recent dream experiences and gated by
 * explicit human review before it may be injected into a generation prompt.
 *
 * Lifecycle: the distill worker inserts rows as [STATUS_PENDING]; the MemoryCenter review
 * surface flips them to [STATUS_CONFIRMED] or [STATUS_REJECTED]. Only CONFIRMED rows are
 * ever read by the prompt builder.
 */
@Entity(
    tableName = "policy_note",
    // MUST match Migration_53_54: Room validates the post-migration schema (including
    // indices) against the entity definition — an extra index created by the migration
    // but absent here fails validation and crashes on first database open.
    indices = [Index(value = ["scope_id", "status"], name = "index_policy_note_scope_id_status")],
)
data class PolicyNoteEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** Assistant-scoped; the empty string means the global scope. */
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    /** The distilled rule, phrased as an instruction addressed to the assistant. */
    @ColumnInfo(name = "content")
    val content: String,
    /** One of [STATUS_PENDING], [STATUS_CONFIRMED], [STATUS_REJECTED]. */
    @ColumnInfo(name = "status")
    val status: String,
    /** JSON array of the dream experience ids that supported the distillation. */
    @ColumnInfo(name = "support_experience_ids")
    val supportExperienceIds: String,
    /** Number of distinct supporting experiences at distill time. */
    @ColumnInfo(name = "support_count")
    val supportCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "reviewed_at")
    val reviewedAt: Long?,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_REJECTED = "REJECTED"
    }
}
