package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 工单 T-11：`scheduled_jobs` 增加 enabled 变更留痕列。
 *
 * 此前 enabled 被改（如模型调用 pause_job）既无 updated_at 也无来源字段，五条任务
 * 被整组静默停用后无从对时间轴。新增：
 * - enabledUpdatedAtMs：最近一次 enabled 变更的时刻（毫秒）
 * - enabledReason：变更来源（created / tool:pause_job / tool:resume_job / user）
 */
val MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scheduled_jobs ADD COLUMN enabledUpdatedAtMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE scheduled_jobs ADD COLUMN enabledReason TEXT NOT NULL DEFAULT ''")
    }
}
