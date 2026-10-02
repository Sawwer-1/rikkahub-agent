package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_52_53 = object : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        applyMigration52To53Sql(db::execSQL)
    }
}

/**
 * Ordered SQL shared by Room and raw import; transaction/version/identity belong to the caller.
 *
 * Conversation folders (ported from ExTV, batch 10). The statements mirror the exact schema
 * Room expects for FolderEntity (table conversation_folder, index on assistant_id), copied
 * verbatim from ExTV's app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json with only
 * `${TABLE_NAME}` substituted.
 */
internal fun applyMigration52To53Sql(executeSql: (String) -> Unit) {
    executeSql(
        "CREATE TABLE IF NOT EXISTS `conversation_folder` (`id` TEXT NOT NULL, " +
            "`assistant_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`sort_index` INTEGER NOT NULL DEFAULT 0, `create_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"
    )
    executeSql("CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` ON `conversation_folder` (`assistant_id`)")
}
