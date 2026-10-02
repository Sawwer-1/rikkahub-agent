package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_50_51 = object : Migration(50, 51) {
    override fun migrate(db: SupportSQLiteDatabase) {
        applyMigration50To51Sql(db::execSQL)
    }
}

/** Ordered SQL shared by Room and raw import; transaction/version/identity belong to the caller. */
internal fun applyMigration50To51Sql(executeSql: (String) -> Unit) {
    // Rolling-summary compression (ported from jude). All three columns mirror the
    // ConversationEntity defaults: empty string means "no summary / no config".
    executeSql("ALTER TABLE `ConversationEntity` ADD COLUMN `compressed_summary` TEXT NOT NULL DEFAULT ''")
    executeSql("ALTER TABLE `ConversationEntity` ADD COLUMN `compressed_message_node_ids` TEXT NOT NULL DEFAULT '[]'")
    executeSql("ALTER TABLE `ConversationEntity` ADD COLUMN `auto_compress_config` TEXT NOT NULL DEFAULT ''")
}
