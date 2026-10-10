package me.rerere.rikkahub.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.rikkahub.BuildConfig

/**
 * DIAGNOSTIC build only (2.4.2-diag1 / 202). Shipped to extract a crash report from a
 * device that cannot run the app: the stock [CrashHandler] records the stack trace into
 * the private "crash_handler" SharedPreferences, which no UI can reach while the app
 * crash-loops. This installer, wired as the VERY FIRST call in [Application.onCreate]:
 *
 *  1. Reads the historical crash stack recorded by [CrashHandler] (if any) and exports it.
 *  2. Runs a read-only health check of the raw `rikka_hub` database file: user_version,
 *     room_master_table identity hash, and the full table list — the three facts needed to
 *     pin down a Room migration/validation crash without adb.
 *  3. Installs a pre-handler that exports any NEW crash (full cause chain + a fresh DB
 *     snapshot) to the same locations, then chains to whatever handler was installed
 *     before it (including the stock [CrashHandler] installed later in onCreate).
 *
 * Output locations, first one that succeeds wins but all attempted:
 *  - Public Downloads via MediaStore (API 29+, no permission needed) — visible to any
 *    stock file manager under Download/.
 *  - App external files dir `Android/data/me.rerere.rikkahub/files/diag/` — reachable via
 *    USB/MTP on every supported API level.
 *  - Logcat tag "CrashDiag" as the last resort.
 *
 * Every step is wrapped in runCatching: the diagnostic layer itself must never become the
 * new crash source, and it never touches the database in write mode.
 */
object CrashDiag {

    private const val TAG = "CrashDiag"
    private const val DB_NAME = "rikka_hub"

    fun install(context: Context) {
        val appContext = context.applicationContext
        // 1) Export the historical crash stack recorded by CrashHandler (if any) together
        //    with a DB snapshot. Silent no-op on a healthy install: nothing is written
        //    unless a crash was actually recorded, so this build can ship to users.
        exportHistoricalCrash(appContext)
        // 2) Chain a pre-handler so a fresh crash is exported before the process dies.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = buildString {
                    appendLine("=== NEW CRASH (${versionLabel(appContext)}) ===")
                    appendLine("Thread: ${thread.name}")
                    appendLine(throwable.stackTraceToString())
                    appendLine("=== DB SNAPSHOT AT CRASH ===")
                    appendLine(buildDbReport(appContext))
                }
                exportReport(appContext, text, tag = "crash")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun exportHistoricalCrash(context: Context) {
        runCatching {
            if (!CrashHandler.hasCrashed(context)) return
            val stack = CrashHandler.getStackTrace(context) ?: return
            val text = buildString {
                appendLine("=== HISTORICAL CRASH (from crash_handler prefs, ${versionLabel(context)}) ===")
                appendLine(stack)
                appendLine("=== DB SNAPSHOT NOW ===")
                appendLine(buildDbReport(context))
            }
            exportReport(context, text, tag = "historical_crash")
        }
    }

    private fun buildDbReport(context: Context): String = buildString {
        appendLine("version: ${versionLabel(context)}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("timestamp: ${nowStamp()}")
        val dbFile = context.getDatabasePath(DB_NAME)
        appendLine("db file: ${dbFile.absolutePath} exists=${dbFile.exists()} size=${if (dbFile.exists()) dbFile.length() else 0}")
        val wal = File(dbFile.parentFile, "$DB_NAME-wal")
        appendLine("wal file: exists=${wal.exists()} size=${if (wal.exists()) wal.length() else 0}")
        if (!dbFile.exists()) return@buildString
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                appendLine("user_version: ${db.version}")
                runCatching {
                    // room_master_table is a single-column table (identity_hash); the
                    // 2.4.2-diag1 build wrongly queried a non-existent `name` column.
                    db.rawQuery("SELECT identity_hash FROM room_master_table", null).use { c ->
                        if (c.moveToFirst()) {
                            appendLine("room identity_hash: ${c.getString(0)}")
                        } else {
                            appendLine("room identity_hash: <room_master_table empty>")
                        }
                    }
                }.onFailure { appendLine("room identity_hash: <unreadable: ${it.message}>") }
                runCatching {
                    db.rawQuery(
                        "SELECT name, type FROM sqlite_master WHERE type IN ('table','index') ORDER BY type, name",
                        null,
                    ).use { c ->
                        appendLine("--- objects (${c.count}) ---")
                        while (c.moveToNext()) {
                            appendLine("${c.getString(1)}: ${c.getString(0)}")
                        }
                    }
                }.onFailure { appendLine("sqlite_master unreadable: ${it.message}") }
            }
        }.onFailure {
            appendLine("DB OPEN FAILED: ${it.javaClass.name}: ${it.message}")
        }
    }

    /** Try MediaStore Downloads first (API 29+), then the app external files dir. */
    private fun exportReport(context: Context, text: String, tag: String) {
        val fileName = "rikkahub_diag_${tag}_${nowStamp()}.txt"
        var delivered = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                delivered = true
                Log.i(TAG, "report written to Downloads: $fileName")
            }.onFailure { Log.w(TAG, "MediaStore export failed for $tag", it) }
        }
        runCatching {
            val dir = context.getExternalFilesDir("diag") ?: return@runCatching
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText(text, Charsets.UTF_8)
            delivered = true
            Log.i(TAG, "report written to ${dir.absolutePath}/$fileName")
        }.onFailure { Log.w(TAG, "external files export failed for $tag", it) }
        if (!delivered) {
            Log.e(TAG, "ALL export paths failed for $tag; dumping to logcat:\n$text")
        }
    }

    private fun versionLabel(context: Context): String =
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun nowStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
