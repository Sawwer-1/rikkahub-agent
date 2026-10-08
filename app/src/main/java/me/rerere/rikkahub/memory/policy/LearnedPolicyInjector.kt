package me.rerere.rikkahub.memory.policy

import me.rerere.rikkahub.data.db.dao.PolicyNoteDao
import me.rerere.rikkahub.data.db.entity.PolicyNoteEntity

/**
 * Builds the `[From role: 策略]` prompt block from CONFIRMED policies. Called by the
 * system-prompt builder for the matching assistant; bounded so the block can never crowd
 * out the core memory budget.
 */
class LearnedPolicyInjector(
    private val policyDao: PolicyNoteDao,
) {
    suspend fun buildPromptBlock(scopeId: String): String? {
        val confirmed = policyDao.listConfirmed(scopeId, MAX_INJECTED)
        if (confirmed.isEmpty()) return null
        val body = confirmed.joinToString("\n") { policy -> "- ${policy.content}" }
        return "[From role: 策略]\n$body"
    }

    companion object {
        const val MAX_INJECTED = 10
    }
}
