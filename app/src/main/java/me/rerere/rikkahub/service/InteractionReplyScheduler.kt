package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Schedules background auto-reply checks for Moments / anonymous question box interactions.
 *
 * The repositories attach a randomized human-feel due window to every user interaction
 * (3-9 min for moments, up to 20 min for question-box items), so this scheduler never fires
 * instantly: the initial check is debounced, and [InteractionReplyWorker] chains itself to
 * the exact next due instant once it has processed whatever came due.
 */
class InteractionReplyScheduler(
    private val context: Context,
) {
    private val wm get() = WorkManager.getInstance(context)

    fun schedule(assistantId: Uuid, initialDelayMs: Long = INITIAL_CHECK_DELAY_MS) {
        val req = OneTimeWorkRequestBuilder<InteractionReplyWorker>()
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                Data.Builder().putString(InteractionReplyWorker.KEY_ASSISTANT_ID, assistantId.toString()).build()
            )
            .build()
        // REPLACE = debounce: a burst of user interactions ends with one check that
        // processes everything due at that moment; the worker re-chains for the rest.
        wm.enqueueUniqueWork(workNameFor(assistantId), ExistingWorkPolicy.REPLACE, req)
    }

    /** Chain continuation from the worker itself; same unique slot, explicit delay. */
    fun scheduleChain(assistantId: Uuid, delayMs: Long) {
        schedule(assistantId, initialDelayMs = delayMs)
    }

    private fun workNameFor(assistantId: Uuid): String = "interaction-reply:$assistantId"

    companion object {
        // Debounce window for the first check after a user interaction. Short on purpose:
        // the worker re-chains itself to the next exact due time when nothing is due yet.
        private const val INITIAL_CHECK_DELAY_MS = 60_000L
    }
}
