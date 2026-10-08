package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {
        applyMigration53To54Sql(db::execSQL)
    }
}

/**
 * Lightweight learned policies (Part B rebuild, 2.4.0): distilled candidates from dream
 * experiences, human-reviewed in MemoryCenter, CONFIRMED rows injected by the prompt builder.
 */
internal fun applyMigration53To54Sql(executeSql: (String) -> Unit) {
    executeSql(
        "CREATE TABLE IF NOT EXISTS `learned_policy` (`id` TEXT NOT NULL, " +
            "`scope_id` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, " +
            "`support_experience_ids` TEXT NOT NULL, `support_count` INTEGER NOT NULL, " +
            "`created_at` INTEGER NOT NULL, `reviewed_at` INTEGER, " +
            "PRIMARY KEY(`id`))"
    )
    executeSql("CREATE INDEX IF NOT EXISTS `index_learned_policy_scope_id_status` ON `learned_policy` (`scope_id`, `status`)")
}
