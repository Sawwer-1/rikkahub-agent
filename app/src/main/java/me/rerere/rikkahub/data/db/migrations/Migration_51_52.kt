package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_51_52 = object : Migration(51, 52) {
    override fun migrate(db: SupportSQLiteDatabase) {
        applyMigration51To52Sql(db::execSQL)
    }
}

/**
 * Ordered SQL shared by Room and raw import; transaction/version/identity belong to the caller.
 *
 * Social surfaces (ported from jude, batch 3): Moments timeline + anonymous question box.
 * Every statement below is copied verbatim from jude's
 * `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/27.json` (only `${TABLE_NAME}` was
 * substituted) so Room's schema hash validation sees exactly what the entities declare.
 */
internal fun applyMigration51To52Sql(executeSql: (String) -> Unit) {
    // --- moments ---
    executeSql(
        "CREATE TABLE IF NOT EXISTS `moments` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, " +
            "`author` TEXT NOT NULL, `content` TEXT NOT NULL, `context_note` TEXT NOT NULL, " +
            "`image_description` TEXT NOT NULL, `images` TEXT NOT NULL, `reply_due_at` INTEGER NOT NULL, " +
            "`reply_status` TEXT NOT NULL, `ai_liked` INTEGER NOT NULL, `ai_reply_content` TEXT NOT NULL, " +
            "`replied_at` INTEGER, `ai_reply_seen_at` INTEGER, `user_liked` INTEGER NOT NULL, " +
            "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    executeSql("CREATE INDEX IF NOT EXISTS `index_moments_assistant_id_created_at` ON `moments` (`assistant_id`, `created_at`)")
    executeSql("CREATE INDEX IF NOT EXISTS `index_moments_assistant_id_reply_status_reply_due_at` ON `moments` (`assistant_id`, `reply_status`, `reply_due_at`)")

    // --- moment_comments ---
    executeSql(
        "CREATE TABLE IF NOT EXISTS `moment_comments` (`id` TEXT NOT NULL, `moment_id` TEXT NOT NULL, " +
            "`author` TEXT NOT NULL, `content` TEXT NOT NULL, `reply_due_at` INTEGER, " +
            "`reply_status` TEXT NOT NULL, `seen_at` INTEGER, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`moment_id`) REFERENCES `moments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    executeSql("CREATE INDEX IF NOT EXISTS `index_moment_comments_moment_id_created_at` ON `moment_comments` (`moment_id`, `created_at`)")
    executeSql("CREATE INDEX IF NOT EXISTS `index_moment_comments_author_reply_status_reply_due_at` ON `moment_comments` (`author`, `reply_status`, `reply_due_at`)")

    // --- moment_profiles ---
    executeSql(
        "CREATE TABLE IF NOT EXISTS `moment_profiles` (`assistant_id` TEXT NOT NULL, " +
            "`cover_uri` TEXT NOT NULL, `last_viewed_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`))"
    )

    // --- anonymous_questions ---
    executeSql(
        "CREATE TABLE IF NOT EXISTS `anonymous_questions` (`id` TEXT NOT NULL, `scope_id` TEXT NOT NULL, " +
            "`author` TEXT NOT NULL, `content` TEXT NOT NULL, `reply_due_at` INTEGER, " +
            "`reply_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    executeSql("CREATE INDEX IF NOT EXISTS `index_anonymous_questions_scope_id_created_at` ON `anonymous_questions` (`scope_id`, `created_at`)")
    executeSql("CREATE INDEX IF NOT EXISTS `index_anonymous_questions_scope_id_author_reply_status_reply_due_at` ON `anonymous_questions` (`scope_id`, `author`, `reply_status`, `reply_due_at`)")

    // --- anonymous_question_replies ---
    executeSql(
        "CREATE TABLE IF NOT EXISTS `anonymous_question_replies` (`id` TEXT NOT NULL, `question_id` TEXT NOT NULL, " +
            "`author` TEXT NOT NULL, `kind` TEXT NOT NULL, `content` TEXT NOT NULL, `reply_due_at` INTEGER, " +
            "`reply_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`question_id`) REFERENCES `anonymous_questions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    executeSql("CREATE INDEX IF NOT EXISTS `index_anonymous_question_replies_question_id_created_at` ON `anonymous_question_replies` (`question_id`, `created_at`)")
    executeSql("CREATE UNIQUE INDEX IF NOT EXISTS `index_anonymous_question_replies_question_id_author_kind` ON `anonymous_question_replies` (`question_id`, `author`, `kind`)")
    executeSql("CREATE INDEX IF NOT EXISTS `index_anonymous_question_replies_author_reply_status_reply_due_at` ON `anonymous_question_replies` (`author`, `reply_status`, `reply_due_at`)")

    // --- anonymous_question_profiles ---
    executeSql(
        "CREATE TABLE IF NOT EXISTS `anonymous_question_profiles` (`scope_id` TEXT NOT NULL, " +
            "`last_viewed_at` INTEGER NOT NULL, PRIMARY KEY(`scope_id`))"
    )
}
