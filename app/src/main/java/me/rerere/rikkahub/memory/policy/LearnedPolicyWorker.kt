package me.rerere.rikkahub.memory.policy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.db.dao.DreamExperienceDao
import me.rerere.rikkahub.data.db.dao.PolicyNoteDao
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.memory.dreaming.model.DreamPairScopeId
import java.util.concurrent.TimeUnit

/**
 * Daily policy distillation: recent dream experiences -> candidate rules -> PENDING rows.
 * Runs on the same WorkManager plumbing as the memory scheduler; the provider call reuses
 * the dream model selection so the user configures the model once.
 */
class LearnedPolicyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = org.koin.core.context.GlobalContext.get()
        val settingsStore = koin.get<SettingsStore>()
        val settings = settingsStore.settingsFlow.first()
        val policyDao = koin.get<PolicyNoteDao>()
        val experienceDao = koin.get<DreamExperienceDao>()
        val providerManager = koin.get<ProviderManager>()

        val assistants = settings.assistants
        var anyStored = false
        for (assistant in assistants) {
            if (assistant.enableMemory.not()) continue
            val pairScopeId = DreamPairScopeId.forAssistant(assistant.id).value
            val state = experienceDao.getState(pairScopeId) ?: continue
            val experiences = experienceDao.listSynthesisExperiences(
                pairScopeId = pairScopeId,
                afterExclusiveEpoch = 0L,
                throughInclusiveEpoch = state.experienceEpoch,
                limit = INPUT_EXPERIENCE_LIMIT,
            )
            if (experiences.isEmpty()) continue
            val scopeId = assistant.id.toString()
            val result = LearnedPolicyDistiller(policyDao, providerManager)
                .distill(settings, assistantId = scopeId, scopeId = scopeId, experiences = experiences)
            if (result is LearnedPolicyDistillResult.Stored) anyStored = true
        }
        Log.i(TAG, "policy distill run finished, stored=$anyStored")
        return Result.success()
    }

    companion object {
        private const val TAG = "LearnedPolicy"
        const val INPUT_EXPERIENCE_LIMIT = 24
        const val PERIODIC_WORK_NAME = "learned_policy_distill_daily"
        const val ONE_TIME_WORK_NAME = "learned_policy_distill_once"

        fun armDaily(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LearnedPolicyWorker>(1, TimeUnit.DAYS)
                    .build(),
            )
        }

        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LearnedPolicyWorker>().build(),
            )
        }
    }
}
