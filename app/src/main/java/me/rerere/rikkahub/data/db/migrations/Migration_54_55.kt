package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        applyMigration54To55Sql(db::execSQL)
    }
}

/**
 * ExTV port: `workspaces` gains a shell compatibility toggle. When enabled the proot runner
 * injects `PROOT_NO_SECCOMP=1`, disabling proot's seccomp acceleration for kernels/ROMs where
 * the fast path fails to start. Column mirrors ExTV's `shell_compatibility_mode INTEGER NOT NULL
 * DEFAULT 0`, so existing rows default to the accelerated (mode off) behaviour.
 */
internal fun applyMigration54To55Sql(executeSql: (String) -> Unit) {
    executeSql(
        "ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0"
    )
}
