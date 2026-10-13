package me.rerere.rikkahub.memory.dreaming.store

import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-10-13 device evidence: seven consecutive Dream runs all reported the same STORE_FAILURE
 * code because [DreamSynthesisCommitRejection] collapsed eleven of its fifteen members into that
 * single catch-all. The run row's failure_code is the only field the user (and the next debugging
 * round) can read, so this contract pins one distinct code per rejection.
 */
class DreamSynthesisCommitRejectionMappingTest {
    @Test
    fun `every commit rejection names its own run failure code`() {
        val codes = DreamSynthesisCommitRejection.entries.map { it.toRunFailureCode() }

        // Sharing a code is exactly what hid the failing check; keep the mapping injective.
        assertEquals(
            DreamSynthesisCommitRejection.entries.size.toLong(),
            codes.toSet().size.toLong(),
        )
        assertEquals(
            "expected one code per rejection: " + DreamSynthesisCommitRejection.entries
                .map { "${it.name}->${it.toRunFailureCode().name}" },
            DreamSynthesisCommitRejection.entries.size,
            codes.toSet().size,
        )
    }

    @Test
    fun `store corruption is reserved for the corruption rejection alone`() {
        val corruptionCodes = DreamSynthesisCommitRejection.entries
            .filter { it.toRunFailureCode() == DreamRunFailureCode.STORE_CORRUPTION }

        assertEquals(
            listOf(DreamSynthesisCommitRejection.STORE_CORRUPTION),
            corruptionCodes,
        )
    }

    @Test
    fun `recoverable conflicts stay distinguishable from corruption`() {
        val conflictCodes = listOf(
            DreamSynthesisCommitRejection.MEMORY_EPOCH_CONFLICT,
            DreamSynthesisCommitRejection.DREAM_REVISION_CONFLICT,
            DreamSynthesisCommitRejection.ACTIVE_SNAPSHOT_CONFLICT,
            DreamSynthesisCommitRejection.CLAIM_REVISION_CONFLICT,
            DreamSynthesisCommitRejection.LEASE_MISSING,
            DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH,
            DreamSynthesisCommitRejection.EVIDENCE_TOMBSTONED,
        ).map { it.toRunFailureCode() }

        assertEquals(conflictCodes.size, conflictCodes.toSet().size)
        check(DreamRunFailureCode.STORE_CORRUPTION !in conflictCodes)
        check(DreamRunFailureCode.STORE_FAILURE !in conflictCodes)
        check(DreamRunFailureCode.FENCE_CONFLICT !in conflictCodes)
        check(DreamRunFailureCode.OWNER_MISMATCH !in conflictCodes)
        check(DreamRunFailureCode.LEASE_EXPIRED !in conflictCodes)
    }
}
